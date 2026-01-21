package fr.sncf.osrd.trainsim

import fr.sncf.osrd.envelope_sim.Action
import fr.sncf.osrd.envelope_sim.EnvelopeSimContext
import fr.sncf.osrd.envelope_sim.IntegrationStep
import fr.sncf.osrd.envelope_sim.TrainPhysicsIntegrator
import fr.sncf.osrd.envelope_sim.etcs.BrakingType
import fr.sncf.osrd.tsim.MicrometerArray
import fr.sncf.osrd.tsim.MicrometerPerSecondArray
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
 * Given a pantograph [position] and its time to fully lower when fully raised [lowerTime], return
 * its position after [dt] microseconds.
 */
internal fun lowerPantograph(position: Double, lowerTime: Microseconds, dt: Microseconds): Double {
    val dx = dt.toDouble() / lowerTime.toDouble()
    return if (position <= dx) {
        0.0
    } else {
        position - dx
    }
}

/**
 * Given a pantograph [position] and its time to fully raise when fully lowered [raiseTime], return
 * its position after [dt] microseconds.
 */
internal fun raisePantograph(position: Double, raiseTime: Microseconds, dt: Microseconds): Double {
    val dx = dt.toDouble() / raiseTime.toDouble()
    return if (position + dx >= 1.0) {
        1.0
    } else {
        position + dx
    }
}

sealed interface PantographState {
    class Up : PantographState

    class Down : PantographState

    class GoingUp(
        /** time until the pantograph is fully raised */
        val remainingTime: Microseconds
    ) : PantographState

    class GoingDown(
        /** time until the pantograph is fully lowered */
        val remainingTime: Microseconds
    ) : PantographState

    fun merge(other: PantographState): PantographState =
        when (this) {
            is Down -> this
            is GoingDown -> when (other) {
                is Down -> other
                is GoingDown -> if (remainingTime < other.remainingTime) {
                    this
                } else {
                    other
                }
                is GoingUp -> this
                is Up -> this
            }
            is GoingUp -> when (other) {
                is Down -> other
                is GoingDown -> other
                is GoingUp -> if (remainingTime < other.remainingTime) {
                    other
                } else {
                    this
                }
                is Up -> this
            }
            is Up -> other
        }
}

interface Decision {
    val time: Microseconds?
    val position: Micrometers?

    fun merge(current: TrainState, mostConstrained: TrainState): TrainState
}

class TrainState(
    override val time: Microseconds,
    override val position: Micrometers,
    val speed: MicrometersPerSecond,
    val pantograph: PantographState,
) : Decision {
    init {
        require(time >= 0) { "train time must be positive or zero" }
        require(position >= 0) { "train position must be positive or zero" }
        require(speed >= 0) { "train speed must be positive or zero" }
    }

    override fun merge(current: TrainState, mostConstrained: TrainState): TrainState {
        val acceleration = speed - current.speed
        val constrainedAcceleration = mostConstrained.speed - current.speed
        if (acceleration < constrainedAcceleration) {
            if (mostConstrained.time < time) {
                TODO("truncate this")
            }
            // TODO merge pantograph states
            return this
        }
        // TODO merge pantograph states
        return mostConstrained
    }
}

/**
 * Start raising the pantograph at [position], given a time to fully raise from its current position
 * [lowerPantographTime]
 */
class RaisePantograph(override val position: Micrometers, val raisePantographTime: Microseconds) : Decision {
    override val time: Microseconds? = null

    override fun merge(current: TrainState, mostConstrained: TrainState): TrainState {
        if (mostConstrained.position < position) {
            return mostConstrained
        }
        val truncated: TrainState = TODO("truncate mostConstrained to this.position")
        return TrainState(
            time = truncated.time,
            position = truncated.position,
            speed = truncated.speed,
            pantograph = PantographState.GoingUp(raisePantographTime),
        )
    }
}

/**
 * Start lowering the pantograph at [position], given a time to fully lower from its current
 * position [lowerPantographTime]
 */
class LowerPantograph(override val position: Micrometers, val lowerPantographTime: Microseconds) : Decision {
    override val time: Microseconds? = null

    override fun merge(current: TrainState, mostConstrained: TrainState): TrainState {
        if (mostConstrained.position < position) {
            return mostConstrained
        }
        val truncated: TrainState = TODO("truncate mostConstrained to this.position")
        return TrainState(
            time = truncated.time,
            position = truncated.position,
            speed = truncated.speed,
            pantograph = PantographState.GoingDown(lowerPantographTime),
        )
    }
}

class Driver(
    /**
     * Maximum acceleration in the driver can perform.
     *
     * This may be higher than the rolling stock's maximum acceleration, in which case this value
     * has no effect.
     */
    val maxAcceleration: MicrometersPerSecond2,

    /**
     * Maximum deceleration in the driver can perform.
     *
     * This may be higher than the rolling stock's maximum deceleration, in which case this value
     * has no effect.
     */
    val maxDeceleration: MicrometersPerSecond2,

    /**
     * Ratio between the self-imposed speed limit and the railway-imposed speed limit.
     *
     * Must be strictly positive. If [vMaxFactor] is one, then the driver may reach and will respect
     * speed limits. When [vMaxFactor] is lower, the driver won't reach speed limits. When higher,
     * the driver will violate speed limits.
     */
    val vMaxFactor: Double,

    /**
     * Length of the rolling stock according to the driver.
     *
     * Used e.g. when instructions only apply after the full rolling stock has passed a signal.
     */
    val perceivedStockLength: Micrometers,

    /** Factor between `0.0` and `1.0` to apply to the path's sight distance */
    val sightDistanceFactor: Double,
    val sightDistance: Micrometers,
) {
    init {
        assert(vMaxFactor > 0.0)
    }

    // TODO
}

/** A constraint that may influence the driving of the train. */
interface Constraint {
    /** Whether the constraint applies */
    fun doesApply(context: EnvelopeSimContext, currentState: TrainState, driver: Driver): Boolean =
        true

    /**
     * Apply the constraint given the [currentState] of the train and return the state of the train
     * after `dt` where `dt` is between 0.0 exclusive and `context.timeStep` inclusive.
     */
    fun enactDecision(context: EnvelopeSimContext, currentState: TrainState): Decision?

    /**
     * Apply the constraint given the [currentState] of the train and return the state of the train
     * at `potentialState.time`.
     */
    fun truncateStep(
        context: EnvelopeSimContext,
        currentState: TrainState,
        potentialState: TrainState,
    ): TrainState
}

/**
 * A driving constraint that only constrains the speed of the train.
 *
 * Implementers of this interface only need to implement [speedCurve], and the constraint will limit
 * the speed of the train to below the curve.
 */
interface SpeedConstraint : Constraint {
    /**
     * The speed constraint represented as a curve where X is the position and Y is the speed.
     *
     * It may depend on the [currentState] of the train, for example if the curve evolves over time.
     */
    fun speedCurve(context: EnvelopeSimContext, currentState: TrainState): Curve

    override fun enactDecision(context: EnvelopeSimContext, currentState: TrainState): TrainState {
        val curve = speedCurve(context, currentState)

        val startSpeedLimit = curve.lerp(currentState.position)

        val accelerateStep =
            TrainPhysicsIntegrator.step(
                    context,
                    initialLocation = currentState.position.toSI(),
                    initialSpeed = currentState.speed.toSI(),
                    action = Action.ACCELERATE,
                    directionSign = +1.0,
                    brakingType = BrakingType.CONSTANT,
                )
                .toMicros()

        if (accelerateStep.startSpeed == startSpeedLimit) {
            // The stock is on the curve, so we return the next point on the curve.

            // Snap on the curve
            val startSpeed = startSpeedLimit

            if (currentState.position < curve.xs.first()) {
                val positionDelta =
                    min(accelerateStep.positionDelta, curve.xs.first() - currentState.position)
                val timeDelta = positionDelta / startSpeed
                return TrainState(
                    time = currentState.time + timeDelta,
                    position = currentState.position + positionDelta,
                    speed = startSpeed,
                    pantograph = currentState.pantograph,
                )
            }

            if (currentState.position < curve.xs.last()) {
                val nextPointIndex = curve.firstAfterStrict(currentState.position)!!
                val endPos = curve.xs[nextPointIndex]
                val endSpeed = curve.ys[nextPointIndex]
                val positionDelta = endPos - currentState.position
                val timeDelta =
                    if (endSpeed + startSpeed == 0L) {
                        context.timeStep.toMicros()
                    } else {
                        (2L * positionDelta) / (endSpeed + startSpeed)
                    }
                return TrainState(
                    time = currentState.time + timeDelta,
                    position = currentState.position + positionDelta,
                    speed = endSpeed,
                    pantograph = currentState.pantograph,
                )
            }

            // Here, currentState.position >= curve.xs.last()
            val positionDelta = accelerateStep.positionDelta
            val timeDelta = positionDelta / startSpeed
            return TrainState(
                time = currentState.time + timeDelta,
                position = currentState.position + positionDelta,
                speed = startSpeed,
                pantograph = currentState.pantograph,
            )
        }

        if (accelerateStep.startSpeed < startSpeedLimit) {
            // The stock is below the curve, so we truncate accelerateStep to land
            // on the curve.

            val s = truncateStepRaw(accelerateStep, currentState.position, curve)
            return TrainState(
                time = currentState.time + s.timeDelta,
                position = currentState.position + s.positionDelta,
                speed = s.endSpeed,
                pantograph = currentState.pantograph,
            )
        }

        // The stock is above the curve, so we brake. We might still need to
        // truncate the braking step if its endSpeed is lower than constraint.ys.last()

        val brakingStep =
            TrainPhysicsIntegrator.step(
                    context,
                    initialLocation = currentState.position.toSI(),
                    initialSpeed = currentState.speed.toSI(),
                    action = Action.BRAKE,
                    directionSign = +1.0,
                    brakingType = BrakingType.CONSTANT,
                )
                .toMicros()
        val s = truncateStepRaw(brakingStep, currentState.position, curve)
        return TrainState(
            time = currentState.time + s.timeDelta,
            position = currentState.position + s.positionDelta,
            speed = s.endSpeed,
            pantograph = currentState.pantograph,
        )
    }

    override fun truncateStep(
        context: EnvelopeSimContext,
        currentState: TrainState,
        potentialState: TrainState,
    ): TrainState {
        val curve = speedCurve(context, currentState)
        TODO()
    }

    private fun truncateStepRaw(
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

        return NanoIntegrationStep.fromNaiveStep(
            timeDelta,
            newPositionDelta,
            step.startSpeed,
            newEndSpeed,
            acceleration,
            +1.0,
        )
    }
}

/**
 * Speed limit signal
 *
 * From [start] to [end], the train must go no higher than [limit].
 */
sealed class SpeedLimitedZone(
    val start: Micrometers,
    val end: Micrometers,
    val limit: MicrometersPerSecond,
) : SpeedConstraint {
    init {
        require(start < end) { "speed limit zone start must be strictly lower than end" }
    }

    override fun speedCurve(context: EnvelopeSimContext, currentState: TrainState): Curve =
        decelerationCurve(context, start, limit) + Vec2(end, limit)
}

/**
 * Temporary speed limit
 *
 * Between [startTime] and [endTime], and possibly from [startPosition] to [endPosition], the train
 * must go no higher than [limit]. The limit only apply if both [startTime] and [startPosition] are
 * reached. If both [endPosition] and [endTime] are `null`, the speed limit doesn't end.
 */
class TemporarySpeedLimit(
    val startPosition: Micrometers?,
    val endPosition: Micrometers?,
    val startTime: Microseconds,
    val endTime: Microseconds?,
    val limit: MicrometersPerSecond,
) : SpeedConstraint {
    override fun speedCurve(context: EnvelopeSimContext, currentState: TrainState): Curve {
        TODO("Curve needs time info")
    }
}

/**
 * Start of a neutral zone
 *
 * From [start] on, the train has no access to electricity. If [lowerPantograph] is `true`, the
 * pantograph must begin to lower no further than [signalPosition].
 */
class NeutralSection(
    val start: Micrometers,
    val end: Micrometers,
    /** Whether the pantograph must be lowered when entering the zone */
    val lowerPantograph: Boolean,
) : Constraint {
    override fun enactDecision(context: EnvelopeSimContext, currentState: TrainState): Decision? {
        if (currentState.position < start) {
            if (lowerPantograph) {
                val time =
                    when (currentState.pantograph) {
                        is PantographState.Down -> 0
                        is PantographState.GoingDown -> currentState.pantograph.remainingTime
                        is PantographState.GoingUp ->
                            (context.rollingStock.lowerPantographTime *
                                (1.0 -
                                    currentState.pantograph.remainingTime.toSI() /
                                    context.rollingStock.raisePantographTime))
                                .toMicros()
                        is PantographState.Up -> context.rollingStock.lowerPantographTime.toMicros()
                    }
                return LowerPantograph(start, time)
            } else {
                return null
            }
        } else if (currentState.position < end) {
            val step =
                TrainPhysicsIntegrator.step(
                        context = context,
                        initialLocation = currentState.position.toSI(),
                        initialSpeed = currentState.speed.toSI(),
                        action = Action.COAST,
                        directionSign = +1.0,
                    )
                    .toMicros()
            if (step.positionDelta + currentState.position > end) {
                val ratio = (end - currentState.position).toDouble() / step.positionDelta.toDouble()
                val speedDelta = step.endSpeed - step.startSpeed
                val pantograph = when(currentState.pantograph) {
                    is PantographState.Down -> PantographState.GoingUp(context.rollingStock.raisePantographTime.toMicros())
                    is PantographState.GoingDown -> PantographState.GoingUp(
                        (context.rollingStock.raisePantographTime *
                            (1.0 -
                                currentState.pantograph.remainingTime.toSI() /
                                context.rollingStock.lowerPantographTime))
                            .toMicros()
                    )
                    is PantographState.GoingUp -> currentState.pantograph
                    is PantographState.Up -> currentState.pantograph
                }
                return TrainState(
                    time = currentState.time + (step.timeDelta * ratio).toLong(),
                    position = end,
                    speed = currentState.speed + (speedDelta * ratio).toLong(),
                    pantograph = pantograph,
                )
            }
            return TrainState(
                time = currentState.time + step.timeDelta,
                position = currentState.position + step.positionDelta,
                speed = step.endSpeed,
                pantograph = currentState.pantograph,
            )
        } else {
            return null
        }
    }

    override fun truncateStep(
        context: EnvelopeSimContext,
        currentState: TrainState,
        potentialState: TrainState,
    ): TrainState {
        TODO("Not yet implemented")
    }
}

/**
 * Short-slip stop signal
 *
 * When closer than 300 meters from this signal, the train must go no higher than 27kph. When closer
 * than 100 meters from this signal, the train must go no higher than 10kph.
 */
class ShortSlipStop(override val signalPosition: Micrometers) : SpeedConstraint {
    override fun speedCurve(context: EnvelopeSimContext, currentState: TrainState): Curve {
        TODO("Return curve")
    }
}

/**
 * Stop on the train path.
 *
 * The train must stop at [signalPosition] for the duration of [duration].
 */
class Stop(override val signalPosition: Micrometers, val duration: Microseconds) : SpeedConstraint {
    override fun speedCurve(context: EnvelopeSimContext, currentState: TrainState): Curve {
        val point = Vec2(TODO(), 0)
        return Curve(point)
    }
}

internal fun decelerationCurve(
    context: EnvelopeSimContext,
    targetPosition: Micrometers,
    targetSpeed: MicrometersPerSecond,
): Curve {
    val maxSpeed = context.rollingStock.maxSpeed.toMicros()

    var position = targetPosition
    var speed = targetSpeed
    var stepCount = 0
    stepCount++ // count the step (target.position, target.speed)
    while (speed < maxSpeed && position > 0) {
        val s =
            TrainPhysicsIntegrator.step(
                    context,
                    position.toSI(),
                    speed.toSI(),
                    Action.BRAKE,
                    -1.0,
                    BrakingType.CONSTANT,
                )
                .toMicros()
        position += s.positionDelta
        speed = s.endSpeed
        stepCount++
    }

    val positions = MicrometerArray(stepCount)
    val speeds = MicrometerPerSecondArray(stepCount)

    var i = stepCount - 1
    positions[i] = targetPosition
    speeds[i] = targetSpeed
    while (i > 0) {
        i--
        val s =
            TrainPhysicsIntegrator.step(
                    context,
                    positions[i + 1].toSI(),
                    speeds[i + 1].toSI(),
                    Action.BRAKE,
                    -1.0,
                    BrakingType.CONSTANT,
                )
                .toMicros()
        positions[i] = positions[i + 1] + s.positionDelta
        speeds[i] = min(s.endSpeed, maxSpeed)
    }

    return Curve(positions, speeds)
}

fun step(
    context: EnvelopeSimContext,
    constraints: List<Constraint>,
    driver: Driver,
    currentState: TrainState,
): TrainState {
    val decision =
        constraints
            .asSequence()
            .filter { it.doesApply(context, currentState, driver) }
            .mapNotNull { it.enactDecision(context, currentState) }
            .fold(naiveStep(context, currentState)) { mostConstrained, decision ->
                decision.merge(currentState, mostConstrained)
            }
}

fun naiveStep(context: EnvelopeSimContext, currentState: TrainState): TrainState = TODO()
