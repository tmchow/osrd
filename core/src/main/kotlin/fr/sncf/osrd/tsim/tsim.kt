package fr.sncf.osrd.tsim

import com.google.common.collect.ImmutableRangeMap
import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import com.google.common.collect.TreeRangeMap
import fr.sncf.osrd.envelope_sim.*
import fr.sncf.osrd.envelope_sim.etcs.BrakingType
import fr.sncf.osrd.path.interfaces.PhysicsPath
import fr.sncf.osrd.train.RollingStock
import kotlin.math.max
import kotlin.math.min

typealias Microseconds = Long

typealias Meters = Double

typealias Micrometers = Long

typealias MicrometersPerSecond = Long

typealias MicrometersPerSecond2 = Long

typealias MicrometerArray = LongArray

typealias MicrometerPerSecondArray = LongArray

fun Double.toMicros(): Long = (this * 1e6).toLong()

fun Long.toSI(): Double = this.toDouble() / 1e6

class NanoIntegrationStep(
    val timeDelta: Microseconds,
    val positionDelta: Micrometers,
    val startSpeed: MicrometersPerSecond,
    val endSpeed: MicrometersPerSecond,
    val acceleration: MicrometersPerSecond2,
) {
    companion object {
        fun fromNaiveStep(
            timeDelta: Microseconds,
            positionDelta: Micrometers,
            startSpeed: MicrometersPerSecond,
            endSpeed: MicrometersPerSecond,
            acceleration: MicrometersPerSecond2,
            directionSign: Double,
        ): NanoIntegrationStep =
            IntegrationStep.fromNaiveStep(
                    timeDelta.toSI(),
                    positionDelta.toSI(),
                    startSpeed.toSI(),
                    endSpeed.toSI(),
                    acceleration.toSI(),
                    directionSign,
                )
                .toMicros()
    }
}

fun IntegrationStep.toMicros(): NanoIntegrationStep =
    NanoIntegrationStep(
        timeDelta = timeDelta.toMicros(),
        positionDelta = positionDelta.toMicros(),
        startSpeed = startSpeed.toMicros(),
        endSpeed = endSpeed.toMicros(),
        acceleration = acceleration.toMicros(),
    )

/**
 * Update a [RangeMap] representing a Speed Profile, accounting for the length of the rolling stock.
 *
 * The given [RangeMap] contains the ranges on the path with speed limits (indicated by signs or
 * signals). The returned [RangeMap] will report, for given positions of the rolling stock's head,
 * ranges on the path where the rolling stock cannot exceed a certain speed limit, because even if
 * pass the sign, as long as its tail is behind the sign the speed limit is still enforced.
 */
fun RangeMap<Micrometers, MicrometersPerSecond>.withStockLength(
    stockLength: Micrometers
): RangeMap<Micrometers, MicrometersPerSecond> {
    val map = TreeRangeMap.create<Micrometers, MicrometersPerSecond>()
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

interface MaxSpeedConstraint {
    /** The speed limit at the given [position]. */
    fun at(position: Micrometers): MicrometersPerSecond

    data class MaxSpeedChange(val position: Micrometers, val speed: MicrometersPerSecond)

    /** Unordered speed limit changes starting from (and excluding) the given position [from]. */
    fun changes(from: Micrometers = 0): Sequence<MaxSpeedChange>
}

/** A max speed constraint implemented as a single [RangeMap]. */
@JvmInline
value class SpeedLimit(
    val map: RangeMap<Micrometers, MicrometersPerSecond> = ImmutableRangeMap.of()
) : MaxSpeedConstraint {
    override fun at(position: Micrometers): MicrometersPerSecond =
        map.get(position) ?: MicrometersPerSecond.MAX_VALUE

    override fun changes(from: Micrometers): Sequence<MaxSpeedConstraint.MaxSpeedChange> =
        map.asDescendingMapOfRanges()
            .asSequence()
            .map { entry ->
                val range = entry.key
                MaxSpeedConstraint.MaxSpeedChange(
                    position = range.lowerEndpointOrMin(),
                    speed = entry.value,
                )
            }
            .takeWhile { change -> change.position > from }
}

/**
 * A max speed constraint implemented as multiple [RangeMap]s, whose ranges may overlap each
 * other's.
 *
 * The speed limit at a given position is taken from the minimum of all speed limits.
 */
class OverlayingSpeedLimits(val overlays: List<RangeMap<Micrometers, MicrometersPerSecond>>) :
    MaxSpeedConstraint {
    override fun at(position: Micrometers): MicrometersPerSecond =
        overlays.asSequence().mapNotNull { overlay -> overlay.get(position) }.minOrNull()
            ?: MicrometersPerSecond.MAX_VALUE

    override fun changes(from: Micrometers): Sequence<MaxSpeedConstraint.MaxSpeedChange> =
        overlays.asSequence().flatMap { overlay -> SpeedLimit(overlay).changes(from) }
}

internal class DecelerationTarget(
    /** Target position where [speed] must be reached. */
    val position: Micrometers,

    /** Braking type used to achieve the target [speed], or `null` if coasting */
    val brake: BrakingType?,

    /** Target speed to reach at [position] */
    val speed: MicrometersPerSecond,
)

data class Vec2(val x: Long, val y: Long)

/**
 * A 2D curve.
 *
 * This class represents a list of 2D points `(xs[i],ys[i])`. [xs] and [ys] must have the same size.
 * [xs] isn't supposed to be empty, and its elements are expected to be strictly increasing.
 */
class Curve(val xs: LongArray, val ys: LongArray) {
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
    fun lerp(x: Long): Long {
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
     * The index of the first point in [xs];[ys] whose X coordinate is strictly higher than the
     * given [x], or `null` if [x] is out of bounds.
     */
    fun firstAfterStrict(x: Long): Int? {
        if (x < xs.first() || xs[xs.size - 1] <= x) {
            return null
        }

        val result = xs.binarySearch(x)

        return if (result >= 0) {
            result + 1
        } else {
            -result - 1
        }
    }

    fun intersectsAt(x1: Long, y1: Long, x2: Long, y2: Long): Vec2? {
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
            points += sequenceOf(Vec2(x1, ys.first()))
        } else {
            i1 = -r1 - 2
        }

        points +=
            generateSequence(i1) { i -> i + 1 }
                .takeWhile { i -> i < size }
                .map { i -> Vec2(xs[i], ys[i]) }
        points += sequenceOf(Vec2(x2, ys.last()))

        return points
            .windowed(2)
            .takeWhile { window -> window[0].x < x2 }
            .mapNotNull { window ->
                val vA = window[0]
                val vAx = SignalingLong(vA.x)
                val vAy = SignalingLong(vA.y)
                val vB = window[1]
                val vBx = SignalingLong(vB.x)
                val vBy = SignalingLong(vB.y)

                val xlo = SignalingLong(max(x1, vA.x))
                val xhi = SignalingLong(min(x2, vB.x))

                val x1 = SignalingLong(x1)
                val y1 = SignalingLong(y1)
                val x2 = SignalingLong(x2)
                val y2 = SignalingLong(y2)

                val y1lo = y1 + (y2 - y1) * ((xlo - x1) / (x2 - x1))
                val y1hi = y1 + (y2 - y1) * ((xhi - x1) / (x2 - x1))
                val yAlo = if (vAy == vBy) vAy else vAy + (vBy - vAy) * ((xlo - vAx) / (vBx - vAx))
                val yAhi = if (vAy == vBy) vAy else vAy + (vBy - vAy) * ((xhi - vAx) / (vBx - vAx))

                if ((yAlo < y1lo) == (yAhi < y1hi)) {
                    return@mapNotNull null
                }

                val ymid = (yAhi * y1lo - yAlo * y1hi) / ((yAhi - yAlo) + (y1lo - y1hi))

                val xmid =
                    if (yAhi != yAlo) {
                        xlo + (xhi - xlo) * (ymid - yAlo) / (yAhi - yAlo)
                    } else {
                        // yBhi != yBlo, or else we would have returned null above
                        xlo + (xhi - xlo) * (ymid - y1lo) / (y1hi - y1lo)
                    }

                Vec2(xmid.raw, ymid.raw)
            }
            .firstOrNull()
    }
}

interface NeutralZoneConstraint {}

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
    val stockMaxSpeed: MicrometersPerSecond = stock.maxSpeed.toMicros()

    internal fun step(
        dt: Microseconds,
        position: Micrometers,
        speed: MicrometersPerSecond,
        action: Action,
    ): NanoIntegrationStep {
        val evsimCtx = EnvelopeSimContext(stock, path, dt.toSI(), effortCurveMap)
        val s = TrainPhysicsIntegrator.step(evsimCtx, position.toSI(), speed.toSI(), action, 1.0)
        return s.toMicros()
    }

    /**
     * Maps [DecelerationTarget]s to [Curve]s that specify the highest speed the rolling [stock] can
     * have at each given position in order to reach the speed target with the given brakes.
     *
     * The curve ends right on the speed target, and starts right before the speed limit is lower
     * than the rolling stock's max speed.
     */
    private val decelerationCurves = mutableMapOf<DecelerationTarget, Curve>()

    internal fun decelerationCurve(target: DecelerationTarget, dt: Microseconds): Curve {
        var curve = decelerationCurves[target]
        if (curve != null) {
            return curve
        }

        val dtSI = dt.toSI()

        val evsimCtx = EnvelopeSimContext(stock, path, dtSI, effortCurveMap)
        val action = if (target.brake != null) Action.BRAKE else Action.COAST

        var position = target.position
        var speed = target.speed
        var stepCount = 0
        stepCount++ // count the step (target.position, target.speed)
        while (speed < stockMaxSpeed && position > 0) {
            val s =
                TrainPhysicsIntegrator.step(
                        evsimCtx,
                        position.toSI(),
                        speed.toSI(),
                        action,
                        -1.0,
                        target.brake!!,
                    )
                    .toMicros()
            assert(s.timeDelta == dt)
            position += s.positionDelta
            speed = s.endSpeed
            stepCount++
        }

        val positions = MicrometerArray(stepCount)
        val speeds = MicrometerPerSecondArray(stepCount)

        var i = stepCount - 1
        positions[i] = target.position
        speeds[i] = target.speed
        while (i > 0) {
            i--
            val s =
                TrainPhysicsIntegrator.step(
                        evsimCtx,
                        positions[i + 1].toSI(),
                        speeds[i + 1].toSI(),
                        action,
                        -1.0,
                        target.brake!!,
                    )
                    .toMicros()
            positions[i] = positions[i + 1] + s.positionDelta
            speeds[i] = min(s.endSpeed, stockMaxSpeed)
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
    dt: Microseconds,

    /** Must be positive */
    position: Micrometers,

    /** Must be positive */
    speed: MicrometersPerSecond,
): NanoIntegrationStep {
    require(dt > 0) { "dt must be strictly positive" }
    require(position >= 0) { "position must be positive" }
    require(speed >= 0) { "speed must be positive" }

    val currentSpeedLimit = instructions.maxSpeed.at(position)

    val maxSpeedChanges =
        instructions.maxSpeed.changes(position) +
            sequenceOf(MaxSpeedConstraint.MaxSpeedChange(position, currentSpeedLimit))

    val naiveStep = ctx.step(dt, position, speed, Action.ACCELERATE)
    val reactions =
        maxSpeedChanges.map { change ->
            val target =
                DecelerationTarget(
                    position = change.position,
                    brake = BrakingType.CONSTANT,
                    speed = change.speed,
                )
            val constraint = ctx.decelerationCurve(target, dt)
            val step =
                reactToSpeedConstraint(ctx, constraint, naiveStep.timeDelta, position, naiveStep)

            assert(step.positionDelta > 0)
            assert(step.endSpeed <= ctx.stockMaxSpeed)
            val nextSpeedLimit = constraint.lerp(step.startSpeed + step.positionDelta)
            assert(step.startSpeed > currentSpeedLimit || step.endSpeed <= nextSpeedLimit)
            assert(step.timeDelta > 0)

            step
        }

    val step =
        reactions.minWithOrNull(
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
            compareBy<NanoIntegrationStep> { step -> step.acceleration }
                .thenBy { step -> step.endSpeed }
                .thenBy { step -> step.timeDelta }
        )!!

    // Assert the stock doesn't go above a speed limit if it wasn't above a speed limit before.
    val nextSpeedLimit = instructions.maxSpeed.at(position + step.positionDelta)
    if (step.endSpeed <= nextSpeedLimit || step.startSpeed > currentSpeedLimit) {
        // TODO change this to an assert
    } else {
        println(
            "oupsi on a dépasser à $position alant a $speed < $currentSpeedLimit vers ${step.endSpeed} > $nextSpeedLimit"
        )
        assert(reactions.all { step -> step.endSpeed > nextSpeedLimit })
        // assert(false)
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
    dt: Microseconds,
    startPos: Micrometers,
    accelerateStep: NanoIntegrationStep,
): NanoIntegrationStep {
    val startSpeedLimit = constraint.lerp(startPos)

    if (accelerateStep.startSpeed == startSpeedLimit) {
        // The stock is on the curve, so we return the next point on the curve.

        // Snap on the curve
        val startSpeed = startSpeedLimit

        if (startPos < constraint.xs.first()) {
            val positionDelta = min(accelerateStep.positionDelta, constraint.xs.first() - startPos)
            val timeDelta = positionDelta / startSpeed
            return NanoIntegrationStep.fromNaiveStep(
                timeDelta,
                positionDelta,
                startSpeed,
                startSpeed,
                0,
                +1.0,
            )
        }

        if (startPos < constraint.xs.last()) {
            val nextPointIndex = constraint.firstAfterStrict(startPos)!!
            val endPos = constraint.xs[nextPointIndex]
            val endSpeed = constraint.ys[nextPointIndex]
            val positionDelta = endPos - startPos
            val timeDelta =
                if (endSpeed + startSpeed == 0L) {
                    dt
                } else {
                    (2L * positionDelta) / (endSpeed + startSpeed)
                }
            val acceleration = if (timeDelta == 0L) 0 else (endSpeed - startSpeed) / timeDelta
            return NanoIntegrationStep.fromNaiveStep(
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
        return NanoIntegrationStep.fromNaiveStep(
            timeDelta,
            positionDelta,
            startSpeed,
            startSpeed,
            0,
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

private fun truncateStep(
    step: NanoIntegrationStep,
    startPos: Micrometers,
    constraint: Curve,
): NanoIntegrationStep {
    val endPos = startPos + step.positionDelta

    val point =
        constraint.intersectsAt(startPos, step.startSpeed, endPos, step.endSpeed) ?: return step

    val newEndPos = point.x
    val newEndSpeed = point.y
    val newPositionDelta = newEndPos - startPos
    val timeDelta =
        if (newEndSpeed + step.startSpeed == 0L) {
            step.timeDelta
        } else {
            // This is like (2*newPositionDelta)/(newEndSpeed+startSpeed),
            // but with less likeliness of timeDelta becoming zero.
            (2L * newEndPos) / (newEndSpeed + step.startSpeed) -
                (2L * startPos) / (newEndSpeed + step.startSpeed)
        }
    val acceleration = if (timeDelta == 0L) 0 else (newEndSpeed - step.startSpeed) / timeDelta

    if (point.x < startPos) {
        println(constraint.intersectsAt(startPos, step.startSpeed, endPos, step.endSpeed))
    }

    return NanoIntegrationStep.fromNaiveStep(
        timeDelta,
        newPositionDelta,
        step.startSpeed,
        newEndSpeed,
        acceleration,
        +1.0,
    )
}
