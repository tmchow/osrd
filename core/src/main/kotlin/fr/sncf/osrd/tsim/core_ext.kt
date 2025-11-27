package fr.sncf.osrd.tsim

import fr.sncf.osrd.envelope.EnvelopeTimeInterpolate.EnvelopePoint
import fr.sncf.osrd.utils.units.Distance
import fr.sncf.osrd.utils.units.Duration
import fr.sncf.osrd.utils.units.Offset
import fr.sncf.osrd.utils.units.Speed

// Extension methods for types in core that are specific to tsim

internal fun EnvelopePoint(time: Microseconds, speed: MicrometersPerSecond, position: Micrometers): EnvelopePoint =
    EnvelopePoint(time.toSI(), speed.toSI(), position.toSI())

internal val Distance.micrometers
    get() = millimeters * 1000

internal val Long.micrometers: Distance
    get() = Distance(this / 1000)

internal val <T> Offset<T>.micrometers: Long
    get() = distance.micrometers

internal val Duration.microseconds
    get() = milliseconds * 1000

internal val Speed.micrometersPerSecond
    get() = millimetersPerSecond * 1000u
