package fr.sncf.osrd.trainsim

import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import fr.sncf.osrd.api.FullInfra
import fr.sncf.osrd.api.RangeValues
import fr.sncf.osrd.api.standalone_sim.CompleteReportTrain
import fr.sncf.osrd.api.standalone_sim.MarginValue
import fr.sncf.osrd.api.standalone_sim.ReportTrain
import fr.sncf.osrd.api.standalone_sim.SimulationScheduleItem
import fr.sncf.osrd.api.standalone_sim.SimulationSuccess
import fr.sncf.osrd.envelope_sim.Comfort
import fr.sncf.osrd.envelope_sim.EnvelopeSimContext
import fr.sncf.osrd.path.interfaces.PhysicsPath
import fr.sncf.osrd.path.interfaces.TrainPath
import fr.sncf.osrd.path.legacy_objects.electrification.Neutral
import fr.sncf.osrd.railjson.schema.schedule.RJSAllowanceDistribution
import fr.sncf.osrd.sim_infra.api.SpeedLimitProperty
import fr.sncf.osrd.standalone_sim.buildSignalingRanges
import fr.sncf.osrd.standalone_sim.makeElectricalProfiles
import fr.sncf.osrd.standalone_sim.makeSafetySpeedRanges
import fr.sncf.osrd.standalone_sim.result.ElectrificationRange
import fr.sncf.osrd.train.RollingStock
import fr.sncf.osrd.utils.DistanceRangeMap
import fr.sncf.osrd.utils.entries
import fr.sncf.osrd.utils.simplifyEnvelopePoints
import fr.sncf.osrd.utils.toRangeMap
import fr.sncf.osrd.utils.units.Distance
import fr.sncf.osrd.utils.units.Duration
import fr.sncf.osrd.utils.units.Offset
import fr.sncf.osrd.utils.units.Speed

fun runSimulation(
    infra: FullInfra,
    trainPath: TrainPath,
    rollingStock: RollingStock,
    comfort: Comfort,
    constraintDistribution: RJSAllowanceDistribution,
    speedLimitTag: String?,
    powerRestrictions: DistanceRangeMap<String>,
    useElectricalProfiles: Boolean,
    useSpeedLimits: Boolean,
    timeStep: Double,
    schedule: List<SimulationScheduleItem>,
    initialSpeed: Double,
    margins: RangeValues<MarginValue>,
    pathItemPositions: List<Offset<PhysicsPath>>,
    driver: Driver = Driver.default(),
): SimulationSuccess {
    val electrificationMap =
        trainPath.getElectrificationMap(
            rollingStock.basePowerClass,
            powerRestrictions.toRangeMap(),
            rollingStock.powerRestrictions,
            !useElectricalProfiles,
        )
    val curvesAndConditions = rollingStock.mapTractiveEffortCurves(electrificationMap, comfort)
    val effortCurveMap = curvesAndConditions.curves
    val context = EnvelopeSimContext(rollingStock, trainPath, timeStep, effortCurveMap)

    val constraints = mutableListOf<Constraint>()
    var trainState =
        TrainState(
            0.microseconds,
            0.micrometers,
            initialSpeed.metersPerSecond,
            PantographState.up(),
        )
    val trainStates = mutableListOf(trainState)
    var mrsp: RangeMap<PreciseDistance, PreciseSpeed> = TreeRangeMap.create()
    mrsp.put(Range.all(), rollingStock.maxSpeed.metersPerSecond)
    if (useSpeedLimits) {
        val props = trainPath.getSpeedLimitProperties(speedLimitTag, null)
        for (prop in props) {
            val lower = prop.lower.micrometers
            val upper = prop.upper.micrometers
            val speed = prop.value.speed.micrometersPerSecond
            if (speed != 0.micrometersPerSecond) {
                mrsp.putLower(Range.closed(lower, upper), speed)
            }
        }
        mrsp = mrsp.withStockLength(rollingStock.length.meters)

        val signalingRanges = buildSignalingRanges(infra, trainPath)
        val safetySpeedRanges = makeSafetySpeedRanges(infra, trainPath, schedule, signalingRanges)
        for (range in safetySpeedRanges) {
            val lower = range.lower.micrometers
            val upper = range.upper.micrometers
            val speed = range.value.micrometersPerSecond
            mrsp.putLower(Range.closed(lower, upper), speed)
        }
    }

    /*
    schedule.map {
        val stopPosition = it.pathOffset.micrometers
        val stopDuration = it.stopFor ?: Duration.ZERO
        constraints.add(Stop(stopPosition, stopDuration.microseconds))
    }
    // */

    for (entry in mrsp.entries) {
        val range = entry.key
        if (range.upperEndpoint() == 0.micrometers) continue
        val speed = entry.value

        constraints.add(SpeedLimitedZone(range.lowerEndpoint(), range.upperEndpoint(), speed))
    }

    for (entry in electrificationMap.entries) {
        val lowerPantograph = (entry.value as? Neutral)?.lowerPantograph ?: continue
        val section =
            NeutralSection(
                start = entry.key.lowerEndpoint().meters,
                end = entry.key.upperEndpoint().meters,
                lowerPantograph = lowerPantograph,
            )
        constraints.add(section)
    }

    while (trainState.position < trainPath.length.meters) {
        trainState = step(context, constraints, driver, trainState)
        trainStates.add(trainState)
    }

    val envelopePoints = trainStates.map(TrainState::toEnvelopePoint)
    val simplifiedPoints = simplifyEnvelopePoints(envelopePoints, 5.0, 0.2)

    val baseReport =
        ReportTrain(
            positions =
                simplifiedPoints.map { point -> Offset(Distance.fromMeters(point.position)) },
            times = simplifiedPoints.map { point -> Duration.fromSeconds(point.time) },
            speeds = simplifiedPoints.map { point -> point.speed },
            energyConsumption = 0.0, // TODO
            pathItemTimes =
                pathItemPositions.map { offset ->
                    val position = offset.meters
                    var res = simplifiedPoints.binarySearchBy(position) { point -> point.position }
                    if (res < 0) {
                        val insertAt = -res - 1
                        // Get the element before where we would insert [position]
                        // to get the arrival time
                        res = (insertAt - 1).coerceIn(0, simplifiedPoints.size - 1)
                    }
                    Duration.fromSeconds(simplifiedPoints[res].time)
                },
        )

    val completeReport =
        CompleteReportTrain(
            positions = baseReport.positions,
            times = baseReport.times,
            speeds = baseReport.speeds,
            energyConsumption = baseReport.energyConsumption,
            pathItemTimes = baseReport.pathItemTimes,
            signalCriticalPositions = listOf(), // TODO
            zoneUpdates = listOf(), // TODO
            spacingRequirements = listOf(), // TODO
            routingRequirements = listOf(), // TODO
        )

    val electrificationRanges =
        ElectrificationRange.from(curvesAndConditions.conditions, electrificationMap)

    return SimulationSuccess(
        base = baseReport,
        provisional = baseReport, // TODO margins
        finalOutput = completeReport,
        mrsp =
            mrsp.subRangeMap(Range.closed(0.micrometers, trainPath.length.meters)).toRangeValues {
                speed ->
                SpeedLimitProperty(
                    speed =
                        Speed.fromMetersPerSecond(
                            speed?.metersPerSecond ?: Double.POSITIVE_INFINITY
                        ),
                    source = null,
                )
            },
        electricalProfiles = makeElectricalProfiles(electrificationRanges),
    )
}

/**
 * Update a [RangeMap] representing a Speed Profile, accounting for the length of the rolling stock.
 *
 * The given [RangeMap] contains the ranges on the path with speed limits (indicated by signs or
 * signals). The returned [RangeMap] will report, for given positions of the rolling stock's head,
 * ranges on the path where the rolling stock cannot exceed a certain speed limit, because even if
 * pass the sign, as long as its tail is behind the sign the speed limit is still enforced.
 */
private fun RangeMap<PreciseDistance, PreciseSpeed>.withStockLength(
    stockLength: PreciseDistance
): RangeMap<PreciseDistance, PreciseSpeed> {
    val map = TreeRangeMap.create<PreciseDistance, PreciseSpeed>()
    for (entry in asMapOfRanges()) {
        val range = entry.key
        val speedLimit = entry.value

        val extendedRange =
            Range.closed(
                range.lowerEndpointOrMin(),
                range.upperEndpointOrMax() saturatingAdd stockLength,
            )
        map.putLower(extendedRange, speedLimit)
    }
    return map
}
