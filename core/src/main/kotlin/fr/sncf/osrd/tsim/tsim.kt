package fr.sncf.osrd.tsim

import com.google.common.collect.ImmutableRangeMap
import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import fr.sncf.osrd.envelope_sim.*
import fr.sncf.osrd.envelope_sim.etcs.BrakingType
import fr.sncf.osrd.path.interfaces.PhysicsPath
import fr.sncf.osrd.train.RollingStock
import kotlin.math.abs

typealias Seconds = Double

typealias Meters = Double

typealias MetersPerSecond = Double

typealias MeterArray = DoubleArray

typealias MeterPerSecondArray = DoubleArray

/**
 * Update a [RangeMap] representing a Speed Profile, accounting for the length of the rolling stock.
 *
 * The given [RangeMap] contains the ranges on the path with speed limits (indicated by signs or
 * signals). The returned [RangeMap] will report, for given positions of the rolling stock's head,
 * ranges on the path where the rolling stock cannot exceed a certain speed limit, because even if
 * pass the sign, as long as its tail is behind the sign the speed limit is still enforced.
 */
fun RangeMap<Meters, MetersPerSecond>.withStockLength(
    stockLength: Meters
): RangeMap<Meters, MetersPerSecond> {
    val map = TreeRangeMap.create<Meters, MetersPerSecond>()
    for (entry in asMapOfRanges()) {
        val range = entry.key
        val speedLimit = entry.value

        val extendedRange =
            Range.closed(range.lowerEndpointOrInf(), range.upperEndpointOrInf() + stockLength)
        map.putLower(extendedRange, speedLimit)
    }
    return map
}

interface MaxSpeedConstraint {
    /** The speed limit at the given [position]. */
    fun at(position: Meters): MetersPerSecond

    data class MaxSpeedChange(val position: Meters, val speed: MetersPerSecond)

    /** Unordered speed limit changes starting from (and excluding) the given position [from]. */
    fun changes(from: Meters = 0.0): Sequence<MaxSpeedChange>
}

/**
 * A max speed constraint implemented as a single [RangeMap].
 */
@JvmInline
value class SpeedLimit(val map: RangeMap<Meters, MetersPerSecond> = ImmutableRangeMap.of()): MaxSpeedConstraint {
    override fun at(position: Meters): MetersPerSecond =
        map.get(position) ?: MetersPerSecond.POSITIVE_INFINITY

    override fun changes(from: Meters): Sequence<MaxSpeedConstraint.MaxSpeedChange> =
        map
            .asDescendingMapOfRanges()
            .asSequence()
            .map { entry ->
                val range = entry.key
                MaxSpeedConstraint.MaxSpeedChange(
                    position = range.lowerEndpointOrInf(),
                    speed = entry.value
                )
            }
            .takeWhile { change -> change.position > from }
}

/**
 * A max speed constraint implemented as multiple [RangeMap]s, whose ranges may overlap each other's.
 *
 * The speed limit at a given position is taken from the minimum of all speed limits.
 */
class OverlayingSpeedLimits(val overlays: List<RangeMap<Meters, MetersPerSecond>>): MaxSpeedConstraint {
    override fun at(position: Meters): MetersPerSecond =
        overlays
            .asSequence()
            .mapNotNull { overlay -> overlay.get(position) }
            .minOrNull()
            ?: MetersPerSecond.POSITIVE_INFINITY

    override fun changes(from: Meters): Sequence<MaxSpeedConstraint.MaxSpeedChange> =
        overlays
            .asSequence()
            .flatMap { overlay -> SpeedLimit(overlay).changes(from) }

}

internal class DecelerationTarget(
    /** Target position where [speed] must be reached. */
    val position: Meters,

    /** Braking type used to achieve the target [speed], or `null` if coasting */
    val brake: BrakingType?,

    /** Target speed to reach at [position] */
    val speed: MetersPerSecond,
)

/**
 * A 2D curve.
 *
 * This class represents a list of 2D points `(xs[i],ys[i])`. [xs] and [ys] must have the same size. [xs] isn't supposed to be empty, and its elements are expected to be strictly increasing.
 */
class Curve(val xs: DoubleArray, val ys: DoubleArray) {
    init {
        require(xs.size == ys.size) { "xs and ys must be the same size" }
        require(xs.isNotEmpty()) { "curve must have at least one point" }
    }

    val size: Int
        get() = xs.size

    /**
     * Linear intERPolation of the Y value of the curve at the given [x] position
     *
     * If [x] is out of bounds, returns the first or the last value of [ys].
     */
    fun lerp(x: Double): Double {
        // Edge cases where we don't have two points to interpolate
        if (x >= xs.last()) {
            return ys.last()
        }
        if (x <= xs.first()) {
            return ys.first()
        }

        val result = xs.binarySearch(x)
        if (result >= 0) {
            // Landed right on a point of the curve
            return ys[result]
        }

        // Index where `x` should be inserted in `xs` to preserve order. It is
        // ensured to be higher than 1 and lower than `size-1`, otherwise `x`
        // is lower than `x.first()` or higher than `xs.last()` and these cases
        // are handled above.
        val hi = -result - 1

        val lo = hi - 1

        return ys[lo] + (ys[hi] - ys[lo]) * (x - xs[lo]) / (xs[hi] - xs[lo])
    }

    /**
     * QUADratic interpolation of the Y value of the curve at the given [x] position
     *
     * If [x] is out of bounds, returns the first or the last value of [ys].
     */
    fun quad(x: Double): Double {
        if (x <= xs.first()) {
            return ys.first()
        }
        if (x >= xs.last()) {
            return ys.last()
        }
        if (size < 3) {
            return lerp(x)
        }

        val result = xs.binarySearch(x)
        if (result >= 0) {
            // Landed right on a point of the curve
            return ys[result]
        }

        // Index where `x` should be inserted in `xs` to preserve order. It is
        // ensured to be higher than 1 and lower than `size-1`, otherwise `x`
        // is lower than `x.first()` or higher than `xs.last()` and these cases
        // are handled above.
        val i = -result - 1

        val lo: Double
        val mi: Double
        val hi: Double
        val ylo: Double
        val ymi: Double
        val yhi: Double
        if (i == size-1) {
            lo = xs[i - 2]
            mi = xs[i - 1]
            hi = xs[i]
            ylo = ys[i - 2]
            ymi = ys[i - 1]
            yhi = ys[i]
        } else {
            lo = xs[i - 1]
            mi = xs[i]
            hi = xs[i + 1]
            ylo = ys[i - 1]
            ymi = ys[i]
            yhi = ys[i + 1]
        }

        // ref: https://en.wikipedia.org/wiki/Polynomial_interpolation#Lagrange_interpolation
        return ylo * (x - mi) * (x - hi) / (lo - mi) / (lo - hi) +
            ymi * (x - lo) * (x - hi) / (mi - lo) / (mi - hi) +
            yhi * (x - lo) * (x - mi) / (hi - lo) / (hi - mi)

    }
}

interface NeutralZoneConstraint {

}

data class Instructions(
    /**
     * Ranges on the path where a speed limit is enforced.
     *
     * Maps positions of the head of the train along the path to the highest allowed speed.
     */
    val maxSpeed: MaxSpeedConstraint = SpeedLimit(),

    /**
     * Ranges of the path that aren't electrified.
     *
     * Maps ranges of the path to whether the neutral zone requires lowering the pantograph.
     */
    val neutralZones: NeutralZoneConstraint? = null,

    // TODO Stop?
)

/**
 * Context of a simulation, caching expensive values for a given [path], rolling [stock] and
 * tractive [effortCurveMap].
 */
class Context(
    val path: PhysicsPath,
    val stock: RollingStock,
    val effortCurveMap: RangeMap<Meters, Array<PhysicsRollingStock.TractiveEffortPoint>>,
) {
    internal fun step(dt: Seconds, position: Meters, speed: MetersPerSecond, action: Action): IntegrationStep {
        val evsimCtx = EnvelopeSimContext(stock, path, dt, effortCurveMap)
        val s = TrainPhysicsIntegrator.step(evsimCtx, position, speed, action, 1.0)
        return s
    }

    /**
     * Maps [DecelerationTarget]s to [Curve]s that specify the highest speed the rolling [stock] can
     * have at each given position in order to reach the speed target with the given brakes.
     *
     * The curve ends right on the speed target, and starts right before the speed limit is lower
     * than the rolling stock's max speed.
     */
    private val decelerationCurves = mutableMapOf<DecelerationTarget, Curve>()

    internal fun decelerationCurve(target: DecelerationTarget, dt: Seconds): Curve {
        var curve = decelerationCurves[target]
        if (curve != null) {
            return curve
        }

        val evsimCtx = EnvelopeSimContext(stock, path, dt, effortCurveMap)
        val action = if (target.brake != null) Action.BRAKE else Action.COAST

        var position = target.position
        var speed = target.speed
        var stepCount = 0
        stepCount++ // count the step (target.position, target.speed)
        while (speed < stock.maxSpeed && position > 0.0) {
            val s =
                TrainPhysicsIntegrator.step(evsimCtx, position, speed, action, -1.0, target.brake)
            assert(s.timeDelta == dt)
            position += s.positionDelta
            speed = s.endSpeed
            stepCount++
        }

        val positions = MeterArray(stepCount)
        val speeds = MeterPerSecondArray(stepCount)

        var i = stepCount - 1
        positions[i] = target.position
        speeds[i] = target.speed
        while (i > 0) {
            i--
            val s =
                TrainPhysicsIntegrator.step(
                    evsimCtx,
                    positions[i + 1],
                    speeds[i + 1],
                    action,
                    -1.0,
                    target.brake,
                )
            positions[i] = positions[i + 1] + s.positionDelta
            speeds[i] = s.endSpeed
        }

        curve = Curve(positions, speeds)
        decelerationCurves[target] = curve
        return curve
    }
}

/**
 * Simulate a given rolling stock going along a given path starting at [position] and going at
 * [speed] for a given time [dt].
 *
 * The context [ctx] may be reused between calls to improve performance.
 */
fun step(
    ctx: Context,
    instructions: Instructions,

    /** Must be strictly positive */
    dt: Seconds,

    /** Must be positive */
    position: Meters,

    /** Must be positive */
    speed: MetersPerSecond,
): IntegrationStep {
    require(dt > 0.0) { "dt must be strictly positive" }
    require(position >= 0.0) { "position must be positive" }
    require(speed >= 0.0) { "speed must be positive" }

    val currentSpeedLimit = instructions.maxSpeed.at(position)

    val maxSpeedChanges = instructions.maxSpeed.changes(position) +
        sequenceOf(MaxSpeedConstraint.MaxSpeedChange(position, currentSpeedLimit))

    val naiveStep = ctx.step(dt, position, speed, Action.ACCELERATE)
    val reactions = maxSpeedChanges
        .map { change ->
            val target = DecelerationTarget(
                position = change.position,
                brake = BrakingType.CONSTANT,
                speed = change.speed,
            )
            val constraint = ctx.decelerationCurve(target, dt)
            reactToSpeedConstraint(
                ctx,
                constraint,
                naiveStep.timeDelta,
                position,
                naiveStep,
            )
        }

    val step = reactions
        .minWithOrNull(
            // Take the most restrictive reaction. First, we pick those with the
            // the lowest acceleration: if a constraint requires the rolling
            // stock to brake (e.g. a signal, or a speed limit), the rolling
            // stock must decelerate. Then, amongst those -- e.g. multiple
            // braking steps, or multiple full accelerations -- take the one
            // with the lowest speed: if all constraints accelerate but one
            // stops accelerating at a speed limit, we want the rolling stock to
            // stop accelerating at the speed limit. Inversely, if a constraint
            // makes the stock brake until a speed limit but another one makes
            // it brake the full step, we can have the stock brake for the full
            // step.
            // Finally, amongst steps that have the minimal acceleration and end
            // speed, we want to pick those that have the lowest timeDelta. This
            // differentiation only makes sense for steps that have 0.0
            // acceleration.
            compareBy<IntegrationStep> { step -> step.acceleration }
                .thenBy { step -> step.endSpeed }
                .thenBy { step -> step.timeDelta }
        )!!

    // Assert the stock doesn't go above a speed limit if it wasn't above a speed limit before.
    val nextSpeedLimit = instructions.maxSpeed.at(position + step.positionDelta)
    if (step.endSpeed approxLowerThan nextSpeedLimit || !(step.startSpeed approxLowerThan currentSpeedLimit)) {
        // TODO change this to an assert
    } else {
        println("oupsi on a dépasser à $position alant a $speed < $currentSpeedLimit vers ${step.endSpeed} > $nextSpeedLimit")
        assert(reactions.all { step -> !(step.endSpeed approxLowerThan nextSpeedLimit) })
        //assert(false)
    }

    assert(step.endSpeed approxLowerThan ctx.stock.maxSpeed)

    return step
}

/**
 * Adjust the behavior of the rolling stock according to a given speed [constraint].
 *
 * The function is given a naive TODO
 */
internal fun reactToSpeedConstraint(
    ctx: Context,
    constraint: Curve,
    dt: Seconds,
    beforePos: Meters,
    accelerateStep: IntegrationStep,
): IntegrationStep {
    val afterPos = beforePos + accelerateStep.positionDelta
    val beforeSpeedLimit = constraint.quad(beforePos)
    val afterSpeedLimit = constraint.quad(afterPos)

    if (accelerateStep.startSpeed approxEqualTo beforeSpeedLimit && (beforePos >= constraint.xs.last() || afterPos <= constraint.xs.first())) {
        // The stock is on the part of the constraint that is flat
        val s = ctx.step(dt, beforePos, accelerateStep.startSpeed, Action.MAINTAIN)
        assert(!(s.timeDelta approxEqualTo 0.0))
        return s
    }

    val truncatedStep = truncateStep(accelerateStep, beforeSpeedLimit, afterSpeedLimit)

    if (beforeSpeedLimit approxLowerThan accelerateStep.startSpeed) {
        // The stock's speed is on (or above) the curve and the curve is going DOWN ↓
        var brakingStep = ctx.step(dt, beforePos, accelerateStep.startSpeed, Action.BRAKE)

        val endLimit = constraint.xs.last()
        if (!(endLimit approxLowerThan brakingStep.endSpeed)) {
            // The rolling stock doesn't have to brake during all the time step to reach the target speed

            brakingStep = truncateStep(brakingStep, endLimit, endLimit)
        }

        assert(!(brakingStep.timeDelta approxEqualTo 0.0))

        val nextSpeedLimit = constraint.quad(beforePos + brakingStep.positionDelta)

        if (brakingStep.startSpeed approxLowerThan beforeSpeedLimit) {
            val acceleration = (nextSpeedLimit - brakingStep.startSpeed) / brakingStep.timeDelta
            assert(nextSpeedLimit approxLowerThan ctx.stock.maxSpeed)
            return IntegrationStep.fromNaiveStep(
                brakingStep.timeDelta,
                brakingStep.positionDelta,
                brakingStep.startSpeed,
                nextSpeedLimit,
                acceleration,
                1.0,
            )
        }

        // Assert we're sticking on the constraint if we weren't above the constraint before
        //assert(brakingStep.endSpeed approxEqualTo nextSpeedLimit || !(brakingStep.startSpeed approxLowerThan beforeSpeedLimit))

        return brakingStep
    }

    //assert(truncatedStep.endSpeed approxLowerThan constraint.quad(beforePos + truncatedStep.positionDelta) || !(truncatedStep.startSpeed approxLowerThan beforeSpeedLimit))

    return truncatedStep
}

/**
 * Given a speed limit defined as a line passing through the points `(0,vmax0)` and `(step.positionDelta,vmax1)`,
 * truncate the given [step] so that its speed ends up on the line.
 *
 * Assume speed and position are linear during the step.
 *
 * Return [step] if its speed and the speed limit are parallel.
 */
private fun truncateStep(step: IntegrationStep, vmax0: Double, vmax1: Double): IntegrationStep {
    val v0 = step.startSpeed
    val v1 = step.endSpeed

    if ((v0 < vmax0) == (v1 < vmax1)) {
        return step
    }

    val vmid = (v1 * vmax0 - v0 * vmax1) / (v1 - v0 + vmax0 - vmax1)
    val mix = ((vmid - v0) / (v1 - v0)).coerceIn(0.0, 1.0) // float errors

    return IntegrationStep.fromNaiveStep(
        step.timeDelta * mix,
        step.positionDelta * mix,
        step.startSpeed,
        vmid,
        step.acceleration,
        1.0,
    )
}

/** Whether [this] and [that] are sufficiently close to each other. */
internal infix fun Double.approxEqualTo(that: Double): Boolean = abs(this - that) < 1e-4

/** Whether [this] is lower, equal or slightly larger than [that]. */
internal infix fun Double.approxLowerThan(that: Double): Boolean = this - that < 1e-4
