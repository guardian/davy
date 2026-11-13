package com.gu.davy

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** What a response means for the events in its request. */
internal sealed class DeliveryOutcome {
    /** A 2xx response: the events were accepted. */
    data object Delivered : DeliveryOutcome()

    /**
     * The server will never accept these events, so they are discarded rather than blocking the
     * queue: 400 Bad Request, 413 Content Too Large, 414 URI Too Long, 422 Unprocessable Content,
     * or 431 Request Header Fields Too Large.
     */
    data class Rejected(val statusCode: Int) : DeliveryOutcome()

    /**
     * A 429 Too Many Requests or 503 Service Unavailable response with a valid `Retry-After`
     * header: send nothing for this many seconds.
     */
    data class Throttled(val retryAfterSeconds: Double) : DeliveryOutcome()

    /**
     * Anything else, such as a network error, a 5xx response, or a 401, 403, or 404 response that
     * likely means the endpoint is misconfigured: keep the events and retry with backoff.
     */
    data class Failed(val reason: String) : DeliveryOutcome()

    companion object {
        val rejectedStatusCodes: Set<Int> = setOf(400, 413, 414, 422, 431)

        /**
         * Classifies a response with [statusCode], reading header fields with [header], which
         * should ignore the case of field names.
         */
        fun of(
            statusCode: Int,
            header: (String) -> String?,
            nowMillis: Long = System.currentTimeMillis(),
        ): DeliveryOutcome = when (statusCode) {
            in 200..299 -> Delivered
            429, 503 -> retryAfterSeconds(header("Retry-After"), header("Date"), nowMillis)
                ?.let(::Throttled)
                ?: Failed("HTTP $statusCode")
            in rejectedStatusCodes -> Rejected(statusCode)
            else -> Failed("HTTP $statusCode")
        }

        /**
         * The number of seconds to wait from a `Retry-After` header, given either as a number of
         * seconds or as an HTTP date. `null` if it's missing or invalid.
         *
         * An HTTP date is measured from the response's `Date` header when present, so a wrong
         * device clock doesn't change how long to wait.
         */
        fun retryAfterSeconds(retryAfter: String?, date: String?, nowMillis: Long): Double? {
            val value = retryAfter?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (value.all { it in '0'..'9' }) return value.toDoubleOrNull()
            val retryAt = HttpDate.parse(value) ?: return null
            val reference = date?.let(HttpDate::parse) ?: nowMillis
            return maxOf(0.0, (retryAt - reference) / 1000.0)
        }
    }
}

/** Parses the date formats that RFC 9110 requires HTTP recipients to accept. */
internal object HttpDate {
    private val formats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss 'GMT'", // IMF-fixdate: Sun, 06 Nov 1994 08:49:37 GMT
        "EEEE, dd-MMM-yy HH:mm:ss 'GMT'", // Obsolete RFC 850: Sunday, 06-Nov-94 08:49:37 GMT
        "EEE MMM d HH:mm:ss yyyy", // Obsolete asctime: Sun Nov  6 08:49:37 1994
    )

    /** Milliseconds since 1970, or `null` if [string] isn't an HTTP date. */
    fun parse(string: String): Long? {
        // asctime pads single-digit days with a second space.
        val normalized = string.trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        for (format in formats) {
            val formatter = SimpleDateFormat(format, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            val position = ParsePosition(0)
            val date = formatter.parse(normalized, position)
            if (date != null && position.index == normalized.length) return date.time
        }
        return null
    }
}
