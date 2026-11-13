package com.gu.davy

import java.io.File
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Where and how [Davy] delivers events.
 *
 * @property endpoint The URL that events are sent to, in the configured [format].
 * @property format How events are encoded into requests: in batches as a JSON body (the default),
 *   or one request per event with every field in the URL.
 * @property environment The kind of build sending events, for example `debug`, `beta`, or
 *   `production`, sent as the session's `environment`. Omitted when `null`. Use it to keep test
 *   data apart from real usage.
 * @property onInvalidEventName What [Davy.log] does with an event whose name isn't lower snake
 *   case, such as `checkoutStarted` or `checkout-started` instead of `checkout_started`. A common
 *   setup is [InvalidEventNameAction.CRASH] in debug builds, so inconsistent names are caught
 *   during development, and [InvalidEventNameAction.SEND] or [InvalidEventNameAction.IGNORE] in
 *   release builds.
 * @property maxEventSize The largest an event can be, in bytes, measured as the length of the URL
 *   it's sent to with [RequestFormat.QueryString], including the endpoint, session data, and an
 *   error's diagnostics. Applies whatever the [format], so any event can be sent as a URL. An event
 *   over this size is replaced with the error `davy_event_too_large`, with its name in `info` under
 *   `rejected_name` and its size under `size`; if that's also too large, nothing is sent. The
 *   default, 2,048 bytes, is within the URL limits of common servers, proxies, and CDNs.
 * @property logsAppLifecycleEvents Whether to log a breadcrumb named `app_backgrounded` each time
 *   the app moves to the background, and `app_foregrounded` each time it returns. Launching isn't
 *   logged as `app_foregrounded`, so every `app_foregrounded` follows an `app_backgrounded` in the
 *   same session.
 * @property headers Additional HTTP header fields sent with every request, for example an
 *   `Authorization` header.
 * @property batchSize The number of pending events that triggers an immediate send. With
 *   [RequestFormat.JsonBatch], also the maximum number of events in a single request. Values below
 *   1 are treated as 1.
 * @property debounceInterval How long to wait after the most recent event before sending a
 *   partially filled batch.
 * @property maxEventAge Events older than this are discarded without being sent.
 * @property storageDirectory The directory where pending events are stored, or `null` for
 *   [Davy.defaultStorageDirectory]. Each [Davy] instance should use its own directory.
 * @property debugHistoryLimit How many sent events to keep in memory for [Davy.debugSnapshot] and
 *   [DavyDebugActivity]. The oldest are dropped once there are more. `0` keeps none. The history
 *   isn't stored on disk, so it starts empty each time an instance is created.
 */
public data class DavyConfiguration(
    val endpoint: URI,
    val format: RequestFormat = RequestFormat.JsonBatch,
    val environment: String? = null,
    val onInvalidEventName: InvalidEventNameAction = InvalidEventNameAction.SEND,
    val maxEventSize: Int = 2048,
    val logsAppLifecycleEvents: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val batchSize: Int = 100,
    val debounceInterval: Duration = 10.seconds,
    val maxEventAge: Duration = 30.days,
    val storageDirectory: File? = null,
    val debugHistoryLimit: Int = 200,
)

/** How events are encoded into HTTP requests. */
public sealed class RequestFormat {
    /**
     * Events are `POST`ed in batches of up to [DavyConfiguration.batchSize] as a JSON body,
     * `{ "session": { … }, "events": [ … ] }`, one request at a time.
     */
    public data object JsonBatch : RequestFormat()

    /**
     * Each event is sent as its own request with an empty body. The event's name is appended to the
     * endpoint's path, and every other field is a query parameter:
     * `<endpoint>/checkout_started?eventId=…&type=breadcrumb&timestamp=…&sessionId=…`.
     *
     * Suits servers that can log fields from the URL but not from the body, such as a CDN that
     * logs requests straight to a data warehouse.
     *
     * @property method The HTTP method. With `GET`, the server should respond with
     *   `Cache-Control: no-store` so proxies don't cache it.
     * @property maxConcurrentRequests How many requests can be in flight at once, so events can
     *   arrive out of order. Values below 1 are treated as 1.
     */
    public data class QueryString(
        val method: HttpMethod = HttpMethod.POST,
        val maxConcurrentRequests: Int = 4,
    ) : RequestFormat()
}

/** The HTTP method used by [RequestFormat.QueryString]. */
public enum class HttpMethod {
    GET,
    POST,
}

/**
 * What [Davy.log] does with an event whose name isn't lower snake case: lowercase ASCII letters and
 * digits in words separated by single underscores, starting with a letter, for example
 * `checkout_started` or `step_2_completed`.
 */
public enum class InvalidEventNameAction {
    /** Throws an [IllegalArgumentException] from the `log` call, so the name is fixed before it ships. */
    CRASH,

    /** Logs an error to Logcat and discards the event. */
    LOG_ERROR,

    /**
     * Replaces the event with the error `davy_invalid_event_name`, with the rejected name in its
     * `info` under `rejected_name`, so invalid names are reported without mixing them into your
     * other events. The original event's `info` isn't sent.
     */
    SEND_ERROR,

    /** Silently discards the event. */
    IGNORE,

    /** Sends the event as is, without checking its name. */
    SEND,
}
