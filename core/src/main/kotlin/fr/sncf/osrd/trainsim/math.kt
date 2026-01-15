package fr.sncf.osrd.trainsim

@JvmInline
internal value class SignalingLong(val raw: Long) : Comparable<SignalingLong> {
    operator fun plus(other: SignalingLong): SignalingLong =
        SignalingLong(Math.addExact(this.raw, other.raw))

    operator fun minus(other: SignalingLong): SignalingLong =
        SignalingLong(Math.subtractExact(this.raw, other.raw))

    operator fun times(other: SignalingLong): SignalingLong =
        SignalingLong(Math.multiplyExact(this.raw, other.raw))

    operator fun div(other: SignalingLong): SignalingLong =
        SignalingLong(Math.divideExact(this.raw, other.raw))

    override fun compareTo(other: SignalingLong): Int = this.raw compareTo other.raw
}

/** Compute the addition of [this] and [that], returning [Long.MAX_VALUE] if it overflows. */
internal infix fun Long.saturatingAdd(that: Long): Long {
    val sum = this + that
    return if ((sum < this) == (that < 0)) {
        sum
    } else {
        Long.MAX_VALUE
    }
}
