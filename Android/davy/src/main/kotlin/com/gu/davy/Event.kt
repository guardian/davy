package com.gu.davy

/**
 * Something that happened in the app: either a breadcrumb or an error, identified by a name you
 * choose.
 *
 * Names are for counting and grouping across many sessions, so keep them short and stable, for
 * example `checkout_started` or `payment_declined`. Don't include personal data or values that vary
 * per user.
 *
 * Use [info] for additional details, for example `mapOf("payment_method" to "google_pay")`. The
 * same rules apply: no personal data.
 */
public sealed class Event {
    public abstract val name: String
    public abstract val info: Map<String, String>

    /** A step the user or app took, for example `checkout_started`. */
    public data class Breadcrumb(
        override val name: String,
        override val info: Map<String, String> = emptyMap(),
    ) : Event()

    /** The fact that an error occurred, for example `payment_declined`. */
    public data class Error(
        override val name: String,
        override val info: Map<String, String> = emptyMap(),
    ) : Event()

    internal companion object {
        /** The error sent in place of an event with an invalid name. */
        const val INVALID_NAME_ERROR_NAME = "davy_invalid_event_name"

        /** The error sent in place of an event over the maximum event size. */
        const val TOO_LARGE_ERROR_NAME = "davy_event_too_large"

        /** The `info` key holding the rejected event's name in either of those errors. */
        const val REJECTED_NAME_INFO_KEY = "rejected_name"

        /** The `info` key holding the rejected event's size in bytes in a too-large error. */
        const val SIZE_INFO_KEY = "size"

        /** The `info` key holding the class of a [Throwable] logged with `Davy.log(error, name)`. */
        const val ERROR_TYPE_INFO_KEY = "error_type"

        /** The `info` key holding the duration of an operation timed with `Davy.measure`. */
        const val DURATION_INFO_KEY = "duration_ms"

        /** Logged when the app returns to the foreground, if lifecycle events are enabled. */
        val APP_FOREGROUNDED = Breadcrumb("app_foregrounded")

        /** Logged when the app moves to the background, if lifecycle events are enabled. */
        val APP_BACKGROUNDED = Breadcrumb("app_backgrounded")

        /** Logged by [Davy.addSessionInfo] after updating the session's `info`. */
        const val SESSION_UPDATED_NAME = "session_updated"

        /**
         * Whether [name] is lowercase ASCII letters and digits in words separated by single
         * underscores, starting with a letter, for example `checkout_started` or `step_2`.
         */
        fun isLowerSnakeCase(name: String): Boolean {
            var previous: Char? = null
            for (char in name) {
                when (char) {
                    in 'a'..'z' -> Unit
                    in '0'..'9' -> if (previous == null) return false
                    '_' -> if (previous == null || previous == '_') return false
                    else -> return false
                }
                previous = char
            }
            return previous != null && previous != '_'
        }
    }
}
