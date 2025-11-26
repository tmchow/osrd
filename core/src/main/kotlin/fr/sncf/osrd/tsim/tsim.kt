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
import kotlin.math.max
import kotlin.math.min

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

class Vec2(val x: Double, val y: Double)

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
     * The index of the first point in [xs];[ys] whose X coordinate is strictly
     * higher than the given [x], or a negative value if [x] is out of bounds.
     */
    fun firstAfterStrict(x: Double): Int {
        if (x < xs.first() || xs[xs.size - 1] <= x) {
            return -1
        }

        val result = xs.binarySearch(x)

        return if (result >= 0) {
            result + 1
        } else {
            -result - 1
        }
    }

    fun intersectsAt(x1: Double, y1: Double, x2: Double, y2: Double): Vec2? {
        require(x1 < x2)

        val r1 = xs.binarySearch(x1)

        // points is the sequence of points from [xs];[ys] that start with the
        // one just before [x1] (or -inf if none) and ends with +inf;[ys.last()]
        var points = sequenceOf<Vec2>()
        val i1: Int
        if (r1 >= 0) {
            i1 = r1
        } else if (r1 == -1) {
            i1 = 0
            points += sequenceOf(Vec2(Double.NEGATIVE_INFINITY, ys.first()))
        } else {
            i1 = -r1 - 2
        }

        points += generateSequence(i1) { i -> i + 1 }
            .takeWhile { i -> i < size }
            .map { i -> Vec2(xs[i], ys[i]) }
        points += sequenceOf(Vec2(Double.POSITIVE_INFINITY, ys.last()))

        return points
            .windowed(2)
            .takeWhile { window -> window[0].x < x2 }
            .mapNotNull { window ->
                val vA = window[0]
                val vB = window[1]

                val xlo = max(x1, vA.x)
                val xhi = min(x2, vB.x)

                val y1lo = y1 + (y2 - y1) * (xlo - x1) / (x2 - x1)
                val y1hi = y1 + (y2 - y1) * (xhi - x1) / (x2 - x1)
                val yAlo = if (vA.y == vB.y) vA.y else vA.y + (vB.y - vA.y) * (xlo - vA.x) / (vB.x - vA.x)
                val yAhi = if (vA.y == vB.y) vA.y else vA.y + (vB.y - vA.y) * (xhi - vA.x) / (vB.x - vA.x)

                val mix = intersectAt(yAlo, yAhi, y1lo, y1hi)
                    ?: return@mapNotNull null

                val xmid = xlo + (xhi - xlo) * mix
                val ymid = y1lo + (y1hi - y1lo) * mix

                Vec2(xmid, ymid)
            }
            .firstOrNull()
    }
}

/**
 * The X coordinate where the following segments intersect
 *
 * - the segment from `(0.0;`[yAlo]`)` to `(1.0;`[yAhi]`)`
 * - the segment from `(0.0;`[yBlo]`)` to `(1.0;`[yBhi]`)`
 */
private fun intersectAt(yAlo: Double, yAhi: Double, yBlo: Double, yBhi: Double): Double? {
    if ((yAlo < yBlo) == (yAhi < yBhi)) {
        return null
    }

    val ymid = (yAhi * yBlo - yAlo * yBhi) / (yAhi - yAlo + yBlo - yBhi)

    return if (yAhi != yAlo) {
        (ymid - yAlo) / (yAhi - yAlo)
    } else {
        // yBhi != yBlo, or else we would have returned null above
        (ymid - yBlo) / (yBhi - yBlo)
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
            speeds[i] = min(s.endSpeed, stock.maxSpeed)
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
            val step = reactToSpeedConstraint(
                ctx,
                constraint,
                naiveStep.timeDelta,
                position,
                naiveStep,
            )

            assert(!(step.positionDelta approxEqualTo 0.0))
            assert(step.endSpeed approxLowerThan ctx.stock.maxSpeed)
            val nextSpeedLimit = constraint.lerp(step.startSpeed + step.positionDelta)
            assert(!(step.startSpeed approxLowerThan currentSpeedLimit) || step.endSpeed approxLowerThan nextSpeedLimit)
            assert(!(step.timeDelta approxEqualTo 0.0))

            step
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
    startPos: Meters,
    accelerateStep: IntegrationStep,
): IntegrationStep {
    val startSpeedLimit = constraint.lerp(startPos)

    if (accelerateStep.startSpeed approxEqualTo startSpeedLimit) {
        // The stock is on the curve, so we return the next point on the curve.

        // Snap on the curve
        val startSpeed = startSpeedLimit

        if (startPos < constraint.xs.first()) {
            val positionDelta = min(accelerateStep.positionDelta, constraint.xs.first() - startPos)
            val timeDelta = positionDelta / startSpeed
            return IntegrationStep.fromNaiveStep(
                timeDelta,
                positionDelta,
                startSpeed,
                startSpeed,
                0.0,
                +1.0,
            )
        }

        if (startPos < constraint.xs.last()) {
            val nextPointIndex = constraint.firstAfterStrict(startPos)
            val endPos = constraint.xs[nextPointIndex]
            val endSpeed = constraint.ys[nextPointIndex]
            val positionDelta = endPos - startPos
            val timeDelta = if (endSpeed + startSpeed == 0.0) {
                dt
            } else {
                2.0 * positionDelta / (endSpeed + startSpeed)
            }
            val acceleration = if (timeDelta == 0.0) 0.0 else (endSpeed - startSpeed) / timeDelta
            return IntegrationStep.fromNaiveStep(
                timeDelta,
                positionDelta,
                startSpeed,
                endSpeed,
                acceleration,
                +1.0,
            )
        }

        // Here, startPos >= constraint.xs.last()
        val positionDelta = accelerateStep.positionDelta
        val timeDelta = positionDelta / startSpeed
        return IntegrationStep.fromNaiveStep(
            timeDelta,
            positionDelta,
            startSpeed,
            startSpeed,
            0.0,
            +1.0,
        )
    }

    if (accelerateStep.startSpeed < startSpeedLimit) {
        // The stock is below the curve, so we truncate accelerateStep to land
        // on the curve.

        return truncateStep(accelerateStep, startPos, constraint)
    }

    // The stock is above the curve, so we brake. We might still need to
    // truncate the braking step if its endSpeed is lower than constraint.ys.last()

    val brakingStep = ctx.step(dt, startPos, accelerateStep.startSpeed, Action.BRAKE)
    return truncateStep(brakingStep, startPos, constraint)
}

private fun truncateStep(step: IntegrationStep, startPos: Meters, constraint: Curve): IntegrationStep {
    val endPos = startPos + step.positionDelta
    val point = constraint.intersectsAt(startPos, step.startSpeed, endPos, step.endSpeed)
        ?: return step

    val newEndPos = point.x
    val newEndSpeed = point.y
    val newPositionDelta = newEndPos - startPos
    val timeDelta = if (newEndSpeed + step.startSpeed == 0.0) {
        step.timeDelta
    } else {
        2.0 * newPositionDelta / (newEndSpeed + step.startSpeed)
    }
    val acceleration = if (timeDelta == 0.0) 0.0 else (newEndSpeed - step.startSpeed) / timeDelta
    return IntegrationStep.fromNaiveStep(
        timeDelta,
        newPositionDelta,
        step.startSpeed,
        newEndSpeed,
        acceleration,
        +1.0,
    )
}

/** Whether [this] and [that] are sufficiently close to each other. */
internal infix fun Double.approxEqualTo(that: Double): Boolean = abs(this - that) < 1e-4

/** Whether [this] is lower, equal or slightly larger than [that]. */
internal infix fun Double.approxLowerThan(that: Double): Boolean = this - that < 1e-4
