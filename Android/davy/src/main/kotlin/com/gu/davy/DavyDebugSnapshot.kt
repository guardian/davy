package com.gu.davy

import java.util.UUID

/**
 * The events a [Davy] instance is waiting to send, the events it has recently sent, the most recent
 * failure to send, and how many events it logged and sent each minute over the last hour, for
 * checking your events during development.
 *
 * Get one with [Davy.debugSnapshot], or a new one each time something changes with
 * [Davy.addDebugListener]. [DavyDebugActivity] shows the same information. Times are in
 * milliseconds since 1970.
 *
 * @property pending Events stored on disk that the server hasn't accepted or rejected yet, oldest
 *   first, which is the order they're sent in. Includes events left over from previous launches
 *   and events in requests that haven't finished.
 * @property sent Events the server has accepted or rejected since this instance was created,
 *   oldest first, up to [DavyConfiguration.debugHistoryLimit].
 * @property lastFailure The most recent failure to send, if no request has succeeded since.
 * @property activity How many events were logged and sent in each minute of the last hour, oldest
 *   first. Minutes in which nothing was logged or sent are left out. Includes events left over from
 *   earlier launches that hadn't been sent when this instance was created. Unlike [sent], this
 *   isn't limited by [DavyConfiguration.debugHistoryLimit] or emptied by [Davy.clearDebugHistory].
 * @property sessionStarts When each session's first event was logged, oldest first, for sessions
 *   whose first event was logged in the last hour. Only events this instance knows about count: its
 *   own, and events left over from earlier launches that were still pending when it was created.
 */
public data class DavyDebugSnapshot(
    val pending: List<LoggedEvent>,
    val sent: List<SentEvent>,
    val lastFailure: Failure?,
    val activity: List<Activity>,
    val sessionStarts: List<SessionStart>,
) {
    /** A logged event, with the ID, timestamp, and session it's sent with. */
    public class LoggedEvent internal constructor(internal val record: EventRecord, internal val session: Session) {
        /** A random identifier generated when the event was logged and sent unchanged with every attempt. */
        public val id: UUID get() = record.id

        /**
         * The breadcrumb or error that was logged, after any replacement for an invalid name or for
         * being over [DavyConfiguration.maxEventSize].
         */
        public val event: Event
            get() = when (record.type) {
                EventRecord.Type.BREADCRUMB -> Event.Breadcrumb(name, info)
                EventRecord.Type.ERROR -> Event.Error(name, info)
            }

        /** The event's name, for example `checkout_started`. */
        public val name: String get() = record.name

        /** The event's additional details. */
        public val info: Map<String, String> get() = record.info.orEmpty()

        /** When the event was logged. */
        public val timestampMillis: Long get() = record.timestampMillis

        /** The ID of the session the event was logged in, which differs from [Davy.sessionId] for events left over from a previous launch. */
        public val sessionId: UUID get() = session.id

        /** Whether the event is an error rather than a breadcrumb. */
        public val isError: Boolean get() = record.type == EventRecord.Type.ERROR

        /** The event and its session as indented JSON with sorted keys, the way they're stored on disk. */
        internal val prettyJson: String get() = DavyJson.prettyPendingEvent(session, record)

        override fun equals(other: Any?): Boolean = other is LoggedEvent && record == other.record && session == other.session

        override fun hashCode(): Int = 31 * record.hashCode() + session.hashCode()

        override fun toString(): String = "LoggedEvent(id=$id, name=$name, sessionId=$sessionId)"
    }

    /**
     * An event the server responded to, so it's no longer pending.
     *
     * @property event The event that was sent.
     * @property sentAtMillis When the server responded.
     * @property outcome How the server responded.
     */
    public data class SentEvent(val event: LoggedEvent, val sentAtMillis: Long, val outcome: Outcome) {
        /** How the server responded to an event. */
        public sealed class Outcome {
            /** The server accepted the event with a 2xx response. */
            public data object Delivered : Outcome()

            /** The server permanently rejected the event, for example with 400 Bad Request, so it was discarded. */
            public data class Rejected(val statusCode: Int) : Outcome()
        }
    }

    /**
     * A request that failed, leaving its events pending to be retried.
     *
     * @property reason Why the request failed, for example `HTTP 500` or a network error.
     * @property dateMillis When the request failed.
     */
    public data class Failure(val reason: String, val dateMillis: Long)

    /**
     * How many events were logged and sent in one minute.
     *
     * @property minuteMillis The start of the minute.
     * @property logged How many events were logged during the minute, by this instance or, if they
     *   were still pending when it was created, by earlier launches.
     * @property sent How many events the server accepted or rejected during the minute, including
     *   events left over from earlier launches.
     */
    public data class Activity(val minuteMillis: Long, val logged: Int, val sent: Int) {
        internal companion object {
            /** The start of the minute containing [millis]. */
            fun minuteContaining(millis: Long): Long = millis.floorDiv(MINUTE_MILLIS) * MINUTE_MILLIS
        }
    }

    /**
     * When a session's first event was logged.
     *
     * @property sessionId The session's ID, as in [LoggedEvent.sessionId].
     * @property dateMillis When the session's first event was logged.
     */
    public data class SessionStart(val sessionId: UUID, val dateMillis: Long)

    public companion object {
        /** A snapshot with nothing pending, sent, or logged. */
        public val EMPTY: DavyDebugSnapshot = DavyDebugSnapshot(emptyList(), emptyList(), null, emptyList(), emptyList())

        private const val MINUTE_MILLIS = 60_000L

        /** How far back [activity] goes. */
        private const val ACTIVITY_WINDOW_MILLIS = 60 * MINUTE_MILLIS

        /** The start of the oldest minute in [activity] at [millis]. */
        internal fun activityStart(millis: Long): Long = Activity.minuteContaining(millis) - ACTIVITY_WINDOW_MILLIS
    }
}
