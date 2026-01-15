package fr.sncf.osrd.tsim

import com.google.common.collect.Range
import com.google.common.collect.RangeMap
import fr.sncf.osrd.api.RangeValues
import fr.sncf.osrd.path.interfaces.TrainPath
import fr.sncf.osrd.utils.units.Offset
import kotlin.math.min

internal fun Range<Long>.lowerEndpointOrMin(): Long =
    if (hasLowerBound()) lowerEndpoint() else Long.MIN_VALUE

internal fun Range<Long>.upperEndpointOrMax(): Long =
    if (hasUpperBound()) upperEndpoint() else Long.MAX_VALUE

/**
 * Make it so the [RangeMap] associates keys in the given [range] with the given [value], while
 * leaving alone the sub-ranges in [range] that had associations with values lower than [value].
 *
 * # Example
 *
 * ```kotlin
 * val map = TreeRangeMap.create<Double, Double>()
 * map.put(Range.closed(3.0, 5.0), 42.0)
 * map.putLower(Range.all(), 69.0)
 *
 * assert(map.get(4.0) == 42.0)
 * assert(map.get(0.0) == 69.0)
 * ```
 */
internal fun RangeMap<Long, Long>.putLower(range: Range<Long>, value: Long) {
    merge(range, value) { old, new -> min(old, new!!) }
}

/**
 * Convert a [RangeMap] to a [RangeValues], transforming values in the process.
 *
 * The first and the last range of the [RangeMap] span from and to infinity respectively in the
 * result.
 *
 * When the given ranges aren't connected, this function fills the gaps in between. In this case,
 * [transform] is given a `null` argument.
 */
internal fun <T : Any, U> RangeMap<Micrometers, T>.toRangeValues(
    transform: (T?) -> U
): RangeValues<U> {
    val internalBoundaries = mutableListOf<Offset<TrainPath>>()
    val values = mutableListOf<U>()

    val iter = asDescendingMapOfRanges().iterator()
    if (!iter.hasNext()) {
        return RangeValues()
    }

    val first = iter.next()
    var lastBoundary = first.key.upperEndpointOrMax()
    values.add(transform(first.value))

    while (iter.hasNext()) {
        val e = iter.next()
        val range = e.key
        val value = e.value

        if (lastBoundary < range.lowerEndpointOrMin()) {
            // This range isn't connected to the last one, add the gap
            internalBoundaries.add(Offset(lastBoundary.micrometers))
            values.add(transform(null))
            lastBoundary = range.lowerEndpointOrMin()
        }

        internalBoundaries.add(Offset(lastBoundary.micrometers))
        values.add(transform(value))
        lastBoundary = range.upperEndpointOrMax()
    }

    return RangeValues(internalBoundaries, values)
}
