package fr.sncf.osrd.api

import fr.sncf.osrd.api.standalone_sim.PhysicsConsistModel
import fr.sncf.osrd.api.stdcm.RequestConsistSchedule
import fr.sncf.osrd.envelope_sim.PhysicsRollingStock
import fr.sncf.osrd.envelope_sim.PhysicsRollingStock.TractiveEffortPoint
import fr.sncf.osrd.envelope_sim.etcs.toEtcsBrakeParams
import fr.sncf.osrd.graph.PathfindingConstraint
import fr.sncf.osrd.pathfinding.constraints.CachedBlockConstraintCombiner
import fr.sncf.osrd.pathfinding.constraints.initConstraints
import fr.sncf.osrd.railjson.schema.rollingstock.RJSEffortCurves.*
import fr.sncf.osrd.railjson.schema.rollingstock.RJSLoadingGaugeType
import fr.sncf.osrd.railjson.schema.rollingstock.RJSRollingResistance
import fr.sncf.osrd.railjson.schema.rollingstock.RJSRollingResistance.Davis
import fr.sncf.osrd.reporting.exceptions.ErrorType
import fr.sncf.osrd.reporting.exceptions.OSRDError
import fr.sncf.osrd.sim_infra.api.TrackSectionId
import fr.sncf.osrd.train.RollingStock
import fr.sncf.osrd.train.RollingStock.*
import kotlin.collections.get

/** Parse the rolling stock model into something the backend can work with */
fun parseRawRollingStock(
    rawPhysicsConsist: PhysicsConsistModel,
    loadingGaugeType: RJSLoadingGaugeType = RJSLoadingGaugeType.G1,
    rollingStockSupportedSignalingSystems: List<String> = listOf(),
): RollingStock {
    // Parse effort_curves
    val rawModes = rawPhysicsConsist.effortCurves.modes

    if (!rawModes.containsKey(rawPhysicsConsist.effortCurves.defaultMode))
        throw OSRDError.newInvalidRollingStockError(
            ErrorType.InvalidRollingStockDefaultModeNotFound,
            rawPhysicsConsist.effortCurves.defaultMode,
        )

    // Parse tractive effort curves modes
    val modes = HashMap<String, ModeEffortCurves>()
    for ((key, value) in rawModes) {
        modes[key] = parseModeEffortCurves(value, "effort_curves.modes.$key")
    }

    val rollingResistance = parseRollingResistance(rawPhysicsConsist.rollingResistance)

    return RollingStock(
        "placeholder_name",
        rawPhysicsConsist.length.meters,
        rawPhysicsConsist.mass.toDouble(),
        rawPhysicsConsist.inertiaCoefficient,
        rollingResistance.A,
        rollingResistance.B,
        rollingResistance.C,
        rawPhysicsConsist.maxSpeed,
        rawPhysicsConsist.startupTime.seconds,
        rawPhysicsConsist.startupAcceleration,
        rawPhysicsConsist.comfortAcceleration,
        rawPhysicsConsist.constGamma,
        rawPhysicsConsist.etcsBrakeParams?.toEtcsBrakeParams(),
        loadingGaugeType,
        modes,
        rawPhysicsConsist.effortCurves.defaultMode,
        rawPhysicsConsist.basePowerClass,
        rawPhysicsConsist.powerRestrictions,
        rawPhysicsConsist.electricalPowerStartupTime?.seconds,
        rawPhysicsConsist.raisePantographTime?.seconds,
        rollingStockSupportedSignalingSystems.toTypedArray(),
    )
}

/**
 * Associates a list of rolling stocks with their related pathfinding constraints. This class
 * provides two ways to be built:
 * - From a list of STDCM query inputs.
 * - From a list of rolling stocks and their pathfinding constraints. This approach is mostly useful
 *   for testing purposes.
 */
data class ConsistSchedule(
    val rollingStocks: List<PhysicsRollingStock>,
    val constraints: List<PathfindingConstraint>?,
) {
    init {
        require(!rollingStocks.isEmpty())
        require(constraints == null || rollingStocks.size == constraints.size)
    }

    companion object {
        operator fun invoke(
            consistSchedule: RequestConsistSchedule,
            infra: FullInfra,
            allowedTrackSections: Set<TrackSectionId>? = null,
            totalSteps: Int,
        ): ConsistSchedule {
            val boundaries = consistSchedule.boundaries
            val rollingStocks =
                consistSchedule.values.map {
                    parseRawRollingStock(
                        it.physicsConsist,
                        it.loadingGaugeType,
                        it.supportedSignalingSystems,
                    )
                }
            return ConsistSchedule(
                rollingStocks,
                boundaries,
                infra,
                allowedTrackSections,
                totalSteps,
            )
        }

        operator fun invoke(
            rollingStocks: List<RollingStock>,
            boundaries: List<Int>,
            infra: FullInfra,
            allowedTrackSections: Set<TrackSectionId>? = null,
            totalSteps: Int,
        ): ConsistSchedule {
            // Input validation:
            when {
                (rollingStocks.size != boundaries.size + 1) -> {
                    throw OSRDError(ErrorType.InvalidSTDCMInputs)
                        .withContext(
                            "cause",
                            "${boundaries.size} boundaries and ${rollingStocks.size} consist configurations provided. There should be n-1 boundaries for n consist configurations",
                        )
                }
                (!boundaries.zipWithNext().all { (a, b) -> a < b }) -> {
                    throw OSRDError(ErrorType.InvalidSTDCMInputs)
                        .withContext(
                            "cause",
                            "Consist change boundaries are not strictly increasing",
                        )
                }
                (!(boundaries.isEmpty() ||
                    (boundaries.first() != 0 && boundaries.last() != totalSteps - 1))) -> {
                    throw OSRDError(ErrorType.InvalidSTDCMInputs)
                        .withContext(
                            "cause",
                            "Consist change specified on the first or last step of the path",
                        )
                }
            }

            // Build the rolling stock and constraint for each step:
            val rollingStocksPerStep = mutableListOf<RollingStock>()
            val constraints = mutableListOf<PathfindingConstraint>()
            var previousBoundary = 0
            for ((index, rollingStock) in rollingStocks.withIndex()) {
                val boundary = boundaries.getOrNull(index) ?: totalSteps
                val constraint =
                    CachedBlockConstraintCombiner(
                        initConstraints(infra, rollingStock, allowedTrackSections)
                    )
                (previousBoundary..<boundary).forEach { _ ->
                    rollingStocksPerStep.add(rollingStock)
                    constraints.add(constraint)
                }
                previousBoundary = boundary
            }
            return ConsistSchedule(rollingStocksPerStep, constraints)
        }
    }
}

private fun parseRollingResistance(rjsRollingResistance: RJSRollingResistance?): Davis {
    if (rjsRollingResistance == null)
        throw OSRDError.newMissingRollingStockFieldError("rolling_resistance")
    if (rjsRollingResistance.javaClass != Davis::class.java)
        throw OSRDError.newInvalidRollingStockFieldError(
            "rolling_resistance",
            "unsupported rolling resistance type",
        )
    return rjsRollingResistance as Davis
}

/** Parse an RJSEffortCurveConditions into a EffortCurveConditions */
private fun parseEffortCurveConditions(
    rjsCond: RJSEffortCurveConditions?,
    fieldKey: String,
): EffortCurveConditions {
    if (rjsCond == null) throw OSRDError.newMissingRollingStockFieldError(fieldKey)
    return EffortCurveConditions(
        rjsCond.comfort,
        rjsCond.electricalProfileLevel,
        rjsCond.powerRestrictionCode,
    )
}

/** Parse RJSModeEffortCurve into a ModeEffortCurve */
private fun parseModeEffortCurves(rjsMode: RJSModeEffortCurve, fieldKey: String): ModeEffortCurves {
    val defaultCurve = parseEffortCurve(rjsMode.defaultCurve, "$fieldKey.default_curve")
    val curves =
        Array(rjsMode.curves.size) { i ->
            val rjsCondCurve = rjsMode.curves[i]
            val curve =
                parseEffortCurve(
                    rjsCondCurve.curve,
                    String.format("%s.curves[%d].curve", fieldKey, i),
                )
            val cond =
                parseEffortCurveConditions(
                    rjsCondCurve.cond,
                    String.format("%s.curves[%d].cond", fieldKey, i),
                )
            ConditionalEffortCurve(cond, curve)
        }
    return ModeEffortCurves(rjsMode.isElectric, defaultCurve, curves)
}

private fun parseEffortCurve(
    rjsEffortCurve: RJSEffortCurve,
    fieldKey: String,
): Array<TractiveEffortPoint> {
    if (rjsEffortCurve.speeds == null)
        throw OSRDError.newMissingRollingStockFieldError("$fieldKey.speeds")
    if (!rjsEffortCurve.speeds.isStrictlyIncreasing())
        throw OSRDError.newInvalidRollingStockFieldError(fieldKey, "speeds not strictly increasing")
    if (rjsEffortCurve.maxEfforts == null)
        throw OSRDError.newMissingRollingStockFieldError("$fieldKey.max_efforts")
    if (rjsEffortCurve.speeds.size != rjsEffortCurve.maxEfforts.size)
        throw OSRDError(ErrorType.InvalidRollingStockEffortCurve)

    return Array(rjsEffortCurve.speeds.size) { i ->
        val speed = rjsEffortCurve.speeds[i]
        if (speed < 0) throw OSRDError.newInvalidRollingStockFieldError(fieldKey, "negative speed")
        val maxEffort = rjsEffortCurve.maxEfforts[i]
        if (maxEffort < 0)
            throw OSRDError.newInvalidRollingStockFieldError(fieldKey, "negative max effort")
        TractiveEffortPoint(speed, maxEffort)
    }
}

private fun DoubleArray.isStrictlyIncreasing(): Boolean =
    asSequence().zipWithNext { prev, next -> prev < next }.all { it }
