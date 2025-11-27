package fr.sncf.osrd.tsim

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
