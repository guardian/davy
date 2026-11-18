package com.gu.davy

import java.util.UUID

/**
 * The events a [Davy] instance is waiting to send, the events and requests it has recently sent,
 * how many events it has logged and sent, the most recent failure to send, and how many events it
 * logged and sent each minute over the last hour, for checking your events during development.
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
 * @property requests Requests that finished since this instance was created, in the order they
 *   finished, up to [DavyConfiguration.debugHistoryLimit]. Includes requests that failed or were
 *   throttled, so you can see why events are still pending.
 * @property loggedCount How many events this instance has logged, plus how many were left over
 *   from earlier launches when it was created. Each is either pending or sent, so this is always
 *   `pending.size + sentCount`. Events discarded without being sent, because they were older than
 *   [DavyConfiguration.maxEventAge] or couldn't be read, aren't counted.
 * @property sentCount How many events the server has accepted or rejected since this instance was
 *   created, including events left over from earlier launches. Unlike [sent], this isn't limited
 *   by [DavyConfiguration.debugHistoryLimit] or reset by [Davy.clearDebugHistory].
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
    val requests: List<Request>,
    val loggedCount: Int,
    val sentCount: Int,
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
        public val info: Map<String, Any> get() = record.info.orEmpty()

        /** When the event was logged. */
        public val timestampMillis: Long get() = record.timestampMillis

        /** The ID of the session the event was logged in, which differs from [Davy.sessionId] for events left over from a previous launch. */
        public val sessionId: UUID get() = session.id

        /** The session's additional details, set with [DavyConfiguration.info] when the session started. */
        public val sessionInfo: Map<String, Any> get() = session.info.orEmpty()

        /** Whether the event is an error rather than a breadcrumb. */
        public val isError: Boolean get() = record.type == EventRecord.Type.ERROR

        /** The event and its session as indented JSON with sorted keys, the way they're stored on disk. */
        internal val exampleRequestJson: String
            get() {
                val body = DavyJson.payload(session, listOf(record), sentAtMillis = timestampMillis)
                return DavyJson.prettyJson(body) ?: body
            }

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
     * An HTTP request Davy sent, with how the server responded.
     *
     * @property id A random identifier for the request.
     * @property headers The header fields Davy set: `Content-Type` and [DavyConfiguration.headers].
     * @property body The JSON body.
     * @property eventIds The IDs of the events in the request, oldest first.
     * @property sentAtMillis When the request was sent.
     * @property durationMillis How long until the response arrived or the request failed.
     * @property statusCode The response's HTTP status code, or `null` if no response was received,
     *   for example because the device is offline.
     * @property outcome What the response meant for the request's events.
     */
    public data class Request(
        val id: UUID,
        val headers: Map<String, String>,
        val body: String,
        val eventIds: List<UUID>,
        val sentAtMillis: Long,
        val durationMillis: Long,
        val statusCode: Int?,
        val outcome: Outcome,
    ) {
        /** What a response meant for a request's events. */
        public sealed class Outcome {
            /** The server accepted the events with a 2xx response. */
            public data object Delivered : Outcome()

            /** The server permanently rejected the events, for example with 400 Bad Request, so they were discarded. */
            public data object Rejected : Outcome()

            /**
             * The server asked for a pause with `Retry-After`, so the events were kept.
             *
             * @property retryAfterSeconds How long the server asked to wait before sending again.
             */
            public data class Throttled(val retryAfterSeconds: Double) : Outcome()

            /**
             * No response was received, or the response means the events should be retried.
             *
             * @property reason Why, as in [Failure.reason], for example `HTTP 500` or a network error.
             */
            public data class Failed(val reason: String) : Outcome()
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
        public val EMPTY: DavyDebugSnapshot = DavyDebugSnapshot(emptyList(), emptyList(), emptyList(), 0, 0, null, emptyList(), emptyList())

        private const val MINUTE_MILLIS = 60_000L

        /** How far back [activity] goes. */
        private const val ACTIVITY_WINDOW_MILLIS = 60 * MINUTE_MILLIS

        /** The start of the oldest minute in [activity] at [millis]. */
        internal fun activityStart(millis: Long): Long = Activity.minuteContaining(millis) - ACTIVITY_WINDOW_MILLIS
    }
}
