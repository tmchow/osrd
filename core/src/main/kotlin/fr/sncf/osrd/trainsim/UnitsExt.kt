package fr.sncf.osrd.trainsim

import fr.sncf.osrd.utils.units.Distance
import fr.sncf.osrd.utils.units.Speed

/* Extension functions for millimeter-based units */

internal val Distance.micrometers: PreciseDistance
    get() = PreciseDistance(micrometers = millimeters * 1000)

internal val Speed.micrometersPerSecond: PreciseSpeed
    get() = PreciseSpeed(micrometersPerSecond = millimetersPerSecond.toLong() * 1000)
