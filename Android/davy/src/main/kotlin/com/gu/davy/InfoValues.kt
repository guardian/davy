package com.gu.davy

import android.util.Log
import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Converts `info` values to the types sent as JSON: [String], [Long], [Double], or [Boolean].
 *
 * Whole numbers become [Long] and decimals [Double], so the same value compares equal whether it was
 * logged as an `Int` or read back from disk. Infinity and NaN, which JSON can't represent, become the
 * strings `inf`, `-inf`, and `nan`, as on Apple platforms. Other text becomes a [String], and enums
 * their [Enum.name].
 *
 * Any other value is dropped, with an error in Logcat, rather than sent as its `toString()`, which
 * for an object such as a data class could include personal data. This matches the Swift client,
 * whose `InfoValue` only holds strings, numbers, and Booleans.
 */
internal object InfoValues {
    private const val TAG = "Davy"

    fun normalize(info: Map<String, Any>): Map<String, Any> = buildMap {
        for ((key, value) in info) {
            val normalized = normalize(value)
            if (normalized == null) {
                Log.e(TAG, "Dropped info \"$key\": a ${value.javaClass.name} isn't a string, number, Boolean, or enum")
            } else {
                put(key, normalized)
            }
        }
    }

    /** [value] as a JSON type, or `null` if it isn't text, a number, a Boolean, or an enum. */
    fun normalize(value: Any): Any? = when (value) {
        is String, is Boolean, is Long -> value
        is CharSequence, is Char -> value.toString()
        is Enum<*> -> value.name
        is Int, is Short, is Byte, is AtomicInteger, is AtomicLong -> (value as Number).toLong()
        is UByte -> value.toLong()
        is UShort -> value.toLong()
        is UInt -> value.toLong()
        is ULong -> if (value <= Long.MAX_VALUE.toULong()) value.toLong() else decimal(value.toDouble())
        is BigInteger -> if (value.bitLength() < Long.SIZE_BITS) value.toLong() else decimal(value.toDouble())
        // A Float's own digits, so 0.1f is sent as 0.1 rather than 0.10000000149011612.
        is Float -> decimal(value.toString().toDouble())
        is Double -> decimal(value)
        is BigDecimal -> decimal(value.toDouble())
        is Number -> decimal(value.toDouble())
        else -> null
    }

    private fun decimal(value: Double): Any = when {
        value.isNaN() -> "nan"
        value == Double.POSITIVE_INFINITY -> "inf"
        value == Double.NEGATIVE_INFINITY -> "-inf"
        else -> value
    }
}
