package fr.sncf.osrd.tsim

@JvmInline
internal value class SignalingLong(val n: Long) : Comparable<SignalingLong> {
    operator fun plus(other: SignalingLong): SignalingLong =
        SignalingLong(Math.addExact(this.n, other.n))

    operator fun minus(other: SignalingLong): SignalingLong =
        SignalingLong(Math.subtractExact(this.n, other.n))

    operator fun times(other: SignalingLong): SignalingLong =
        SignalingLong(Math.multiplyExact(this.n, other.n))

    operator fun div(other: SignalingLong): SignalingLong =
        SignalingLong(Math.divideExact(this.n, other.n))

    override fun compareTo(other: SignalingLong): Int =
        this.n compareTo other.n
}

internal infix fun Long.addX(that: Long): Long =
    Math.addExact(this, that)

internal infix fun Long.subX(that: Long): Long =
    Math.subtractExact(this, that)

internal infix fun Long.mulX(that: Long): Long =
    Math.multiplyExact(this, that)

internal infix fun Long.divX(that: Long): Long =
    Math.divideExact(this, that)

/**
 * Compute the addition of [this] and [that], returning [Long.MAX_VALUE] if it
 * overflows.
 */
internal infix fun Long.saturatingAdd(that: Long): Long {
    val sum = this + that
    return if ((sum < this) == (that < 0)) {
        sum
    } else {
        Long.MAX_VALUE
    }
}
