package com.gu.davy

import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Converts `info` values to the types sent as JSON: [String], [Long], [Double], or [Boolean].
 *
 * Whole numbers become [Long] and decimals [Double], so the same value compares equal whether it was
 * logged as an `Int` or read back from disk. Infinity and NaN, which JSON can't represent, become the
 * strings `inf`, `-inf`, and `nan`, as on Apple platforms. Any other value becomes its `toString()`.
 */
internal object InfoValues {
    fun normalize(info: Map<String, Any>): Map<String, Any> = info.mapValues { normalize(it.value) }

    fun normalize(value: Any): Any = when (value) {
        is String, is Boolean, is Long -> value
        is Int, is Short, is Byte, is AtomicInteger, is AtomicLong -> (value as Number).toLong()
        is BigInteger -> if (value.bitLength() < Long.SIZE_BITS) value.toLong() else decimal(value.toDouble())
        // A Float's own digits, so 0.1f is sent as 0.1 rather than 0.10000000149011612.
        is Float -> decimal(value.toString().toDouble())
        is Double -> decimal(value)
        is BigDecimal -> decimal(value.toDouble())
        is Number -> decimal(value.toDouble())
        else -> value.toString()
    }

    private fun decimal(value: Double): Any = when {
        value.isNaN() -> "nan"
        value == Double.POSITIVE_INFINITY -> "inf"
        value == Double.NEGATIVE_INFINITY -> "-inf"
        else -> value
    }
}
