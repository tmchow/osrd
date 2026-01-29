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
import fr.sncf.osrd.railjson.schema.schedule.RJSAllowanceDistribution
import fr.sncf.osrd.sim_infra.api.SpeedLimitProperty
import fr.sncf.osrd.standalone_sim.buildSignalingRanges
import fr.sncf.osrd.standalone_sim.makeElectricalProfiles
import fr.sncf.osrd.standalone_sim.makeSafetySpeedRanges
import fr.sncf.osrd.standalone_sim.result.ElectrificationRange
import fr.sncf.osrd.train.RollingStock
import fr.sncf.osrd.tsim.Micrometers
import fr.sncf.osrd.tsim.MicrometersPerSecond
import fr.sncf.osrd.tsim.micrometers
import fr.sncf.osrd.tsim.micrometersPerSecond
import fr.sncf.osrd.tsim.microseconds
import fr.sncf.osrd.tsim.putLower
import fr.sncf.osrd.tsim.toMicros
import fr.sncf.osrd.tsim.toRangeValues
import fr.sncf.osrd.tsim.toSI
import fr.sncf.osrd.tsim.withStockLength
import fr.sncf.osrd.utils.DistanceRangeMap
import fr.sncf.osrd.utils.entries
import fr.sncf.osrd.utils.simplifyEnvelopePoints
import fr.sncf.osrd.utils.toRangeMap
import fr.sncf.osrd.utils.units.Duration
import fr.sncf.osrd.utils.units.Offset
import fr.sncf.osrd.utils.units.meters
import fr.sncf.osrd.utils.units.metersPerSecond
import fr.sncf.osrd.utils.units.seconds

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
    val ctx = EnvelopeSimContext(rollingStock, trainPath, timeStep, effortCurveMap)

    val constraints = mutableListOf<Constraint>()
    var trainState = TrainState(0L, 0L, initialSpeed.toMicros(), PantographState.Up())
    val trainStates = mutableListOf(trainState)
    var mrsp: RangeMap<Micrometers, MicrometersPerSecond> = TreeRangeMap.create()
    mrsp.put(Range.all(), rollingStock.maxSpeed.toMicros())
    if (useSpeedLimits) {
        val props = trainPath.getSpeedLimitProperties(speedLimitTag, null)
        for (prop in props) {
            val lower = prop.lower.micrometers
            val upper = prop.upper.micrometers
            val speed = prop.value.speed.micrometersPerSecond.toLong()
            if (speed != 0L) {
                mrsp.putLower(Range.closed(lower, upper), speed)
            }
        }
        mrsp = mrsp.withStockLength(rollingStock.length.toMicros())

        val signalingRanges = buildSignalingRanges(infra, trainPath)
        val safetySpeedRanges = makeSafetySpeedRanges(infra, trainPath, schedule, signalingRanges)
        for (range in safetySpeedRanges) {
            val lower = range.lower.micrometers
            val upper = range.upper.micrometers
            val speed = range.value.micrometersPerSecond.toLong()
            mrsp.putLower(Range.closed(lower, upper), speed)
        }
    }

    schedule.map {
        val stopPosition = it.pathOffset.micrometers
        val stopDuration = it.stopFor ?: Duration.ZERO
        constraints.add(Stop(stopPosition, stopDuration.microseconds))
    }

    for (entry in mrsp.entries) {
        val range = entry.key
        if (range.upperEndpoint() == 0L) continue
        val speed = entry.value

        constraints.add(SpeedLimitedZone(range.lowerEndpoint(), range.upperEndpoint(), speed))
    }

    while (trainState.position < trainPath.length.toMicros()) {
        trainState = step(ctx, constraints, driver, trainState)
        trainStates.add(trainState)
    }

    val envelopePoints = trainStates.map(TrainState::toEnvelopePoint)
    val simplifiedPoints = simplifyEnvelopePoints(envelopePoints, 5.0, 0.2)

    val baseReport =
        ReportTrain(
            positions = simplifiedPoints.map { point -> Offset(point.position.meters) },
            times = simplifiedPoints.map { point -> point.time.seconds },
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
                    simplifiedPoints[res].time.seconds
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
            mrsp.subRangeMap(Range.closed(0, trainPath.length.toMicros())).toRangeValues { speed ->
                SpeedLimitProperty(
                    speed = (speed?.toSI() ?: Double.POSITIVE_INFINITY).metersPerSecond,
                    source = null,
                )
            },
        electricalProfiles = makeElectricalProfiles(electrificationRanges),
    )
}
