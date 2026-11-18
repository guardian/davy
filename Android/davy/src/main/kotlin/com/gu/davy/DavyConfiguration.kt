package com.gu.davy

import java.io.File
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Where and how [Davy] delivers events.
 *
 * @property endpoint The URL that events are sent to.
 * @property environment The kind of build sending events, for example `debug`, `beta`, or
 *   `production`, sent as the session's `environment`. Omitted when `null`. Use it to keep test
 *   data apart from real usage.
 * @property info Additional details about the app and session, sent with every event as the
 *   request's `session.info`. Omitted when empty. As with event `info`, don't include personal data
 *   or values that identify a person or device across sessions.
 * @property onInvalidEventName What [Davy.log] does with an event whose name isn't lower snake
 *   case, such as `checkoutStarted` or `checkout-started` instead of `checkout_started`. A common
 *   setup is [InvalidEventNameAction.CRASH] in debug builds, so inconsistent names are caught
 *   during development, and [InvalidEventNameAction.SEND] or [InvalidEventNameAction.IGNORE] in
 *   release builds.
 * @property maxEventSize The largest an event can be, in bytes, measured as the UTF-8 length of a
 *   single-event JSON request body, including the session data and an error's diagnostics, so a
 *   single large event can't make a batch request too large for the server to accept. An event over
 *   this size is replaced with the error `davy_event_too_large`, with its name in `info` under
 *   `rejected_name` and its size under `size`; if that's also too large, nothing is sent. Defaults
 *   to 4,096.
 * @property logsAppLifecycleEvents Whether to log a breadcrumb named `app_backgrounded` each time
 *   the app moves to the background, and `app_foregrounded` each time it returns. Launching isn't
 *   logged as `app_foregrounded`, so every `app_foregrounded` follows an `app_backgrounded` in the
 *   same session.
 * @property headers Additional HTTP header fields sent with every request, for example an
 *   `Authorization` header. `Content-Type`, `User-Agent`, and `Accept-Language` are always set by
 *   Davy.
 * @property batchSize The number of pending events that triggers an immediate send, and the
 *   maximum number of events in a single request. Values below 1 are treated as 1. To send each
 *   event as soon as it's logged, set it to 1.
 * @property debounceInterval How long to wait after the most recent event before sending a
 *   partially filled batch.
 * @property maxEventAge Events older than this are discarded without being sent.
 * @property storageDirectory The directory where pending events are stored, or `null` for
 *   [Davy.defaultStorageDirectory]. Each [Davy] instance should use its own directory.
 * @property debugHistoryLimit How many sent events, and how many requests, to keep in memory for
 *   [Davy.debugSnapshot] and [DavyDebugActivity]. The oldest are dropped once there are more. `0`
 *   keeps none. The history isn't stored on disk, so it starts empty each time an instance is
 *   created. Each request is kept with its body, so lower this if memory is tight.
 * @property allowsInsecureHttp Allows plain HTTP for development. Defaults to `false`.
 */
public data class DavyConfiguration(
    val endpoint: URI,
    val environment: String? = null,
    val info: Map<String, Any> = emptyMap(),
    val onInvalidEventName: InvalidEventNameAction = InvalidEventNameAction.SEND,
    val maxEventSize: Int = 4096,
    val logsAppLifecycleEvents: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val batchSize: Int = 100,
    val debounceInterval: Duration = 10.seconds,
    val maxEventAge: Duration = 30.days,
    val storageDirectory: File? = null,
    val debugHistoryLimit: Int = 200,
    val allowsInsecureHttp: Boolean = false,
)

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
