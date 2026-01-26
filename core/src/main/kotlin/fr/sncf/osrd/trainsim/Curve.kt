package fr.sncf.osrd.trainsim

import kotlin.math.max
import kotlin.math.min

data class Vec2(val x: Long, val y: Long) {
    constructor(xy: Pair<Long, Long>) : this(x = xy.first, y = xy.second)
}

/**
 * Represents a simple segment ((x1, y1), (x2, y2))
 *
 * TODO: This may be removed in the future. At first I thought it would be useful, but turns out it
 *   just wraps coordinates for now
 */
data class Segment(val first: Vec2, val second: Vec2) {
    constructor(x1: Long, y1: Long, x2: Long, y2: Long) : this(Vec2(x1, y1), Vec2(x2, y2))

    val x1: Long
        get() = first.x

    val y1: Long
        get() = first.y

    val x2: Long
        get() = second.x

    val y2: Long
        get() = second.y
}

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

    private constructor(
        points: Pair<List<Long>, List<Long>>
    ) : this(points.first.toLongArray(), points.second.toLongArray())

    constructor(points: Iterable<Vec2>) : this(points.map { point -> point.x to point.y }.unzip())

    constructor(vararg points: Vec2) : this(points.asIterable())

    constructor(points: Sequence<Vec2>) : this(points.asIterable())

    val size: Int
        get() = xs.size

    operator fun plus(point: Vec2): Curve =
        Curve(xs.asSequence().zip(ys.asSequence()).map { Vec2(it) } + sequenceOf(point))

    operator fun plus(points: Iterable<Vec2>): Curve =
        Curve(xs.asSequence().zip(ys.asSequence()).map { Vec2(it) } + points.asSequence())

    /**
     * Creates a [Vec2] from the curve data at index [idx].
     *
     * Returns `null` if [idx] is out of bounds.
     */
    internal fun getPointAt(idx: Int): Vec2? {
        if (idx !in 0..<size) {
            return null
        }

        return Vec2(xs[idx], ys[idx])
    }

    /** Checks if the curve is below a given [point] */
    internal fun isBelow(point: Vec2): Boolean {
        val index = xs.binarySearch(point.x)

        if (index < 0) {
            // Exact match not found. Let's check with the segment from the previous to the next
            // point
            val nextPointIdx = -index - 1
            val previousPointIdx = nextPointIdx - 1
            val nextPoint = getPointAt(nextPointIdx)
            val previousPoint = getPointAt(previousPointIdx)

            // TODO: This might be wrong
            if (nextPoint == null || previousPoint == null) {
                return true
            }

            val v1 = Vec2(nextPoint.x - previousPoint.x, nextPoint.y - previousPoint.y)
            val v2 = Vec2(nextPoint.x - point.x, nextPoint.y - point.y)
            val xp = (v1.x * v2.y) - (v1.y * v2.x)

            return xp < 0
        }

        // Direct match, just compare the y values
        return ys[index] < point.y
    }

    /**
     * Linear intERPolation of the Y value of the curve at the given [x] position
     *
     * If [x] is out of bounds, returns the first or the last value of [ys].
     */
    internal fun lerp(x: Long): Long {
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
    internal fun firstAfterStrict(x: Long): Int? {
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

    /**
     * Returns the last point on the curve, optionally offset by [n], as a [Vec2]. For example,
     * `curve.last()` returns the last point on the curve whereas `curve.last(1)` returns the second
     * to last point.
     *
     * Returns `null` if [n] is out of bounds
     */
    internal fun last(n: Int = 0): Vec2? {
        if (n !in 0..<size) {
            return null
        }

        return Vec2(xs[size - n - 1], ys[size - n - 1])
    }

    internal fun intersectsAt(segment: Segment): Vec2? {
        val x1 = segment.x1
        val y1 = segment.y1
        val x2 = segment.x2
        val y2 = segment.y2
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
