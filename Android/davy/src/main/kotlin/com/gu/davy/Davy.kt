package com.gu.davy

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToLong

/**
 * Logs breadcrumbs and errors, persists them to disk, and sends them to your endpoint.
 *
 * ```kotlin
 * val telemetry = Davy(context, DavyConfiguration(endpoint = URI("https://api.example.com/events")))
 * telemetry.log(Event.Breadcrumb("checkout_started"))
 * telemetry.log(Event.Error("payment_declined"))
 * ```
 *
 * Create one instance when the app starts, for example in `Application.onCreate`, and keep it for
 * the app's lifetime. Each instance starts a new session and owns the events in its storage
 * directory.
 *
 * [log] and [flush] return immediately and can be called from any thread. Events are processed in
 * the order they are logged, and sent as described by the shared spec, `Spec/openapi.yaml`.
 *
 * Events are sent when [DavyConfiguration.batchSize] events are pending, when
 * [DavyConfiguration.debounceInterval] passes without a new event, when the app moves to the
 * background, and on the next launch for anything left over. Events are only removed after the
 * server responds with a 2xx status, or with a status meaning they will never be accepted, such as
 * 400; otherwise they are retried later with exponential backoff. A 429 or 503 response with a
 * `Retry-After` header pauses all sending for that long. Events older than
 * [DavyConfiguration.maxEventAge] are discarded.
 */
public class Davy internal constructor(
    /** The configuration this instance was created with. */
    public val configuration: DavyConfiguration,
    session: Session,
    storageDirectory: File,
    transport: EventTransport,
    retryPolicy: RetryPolicy,
    private val captureDiagnostics: () -> Map<String, Any>,
    lifecycle: LifecycleSource?,
) : Closeable {
    /**
     * Creates an instance with a new session and starts sending any events left over from a
     * previous session.
     */
    public constructor(context: Context, configuration: DavyConfiguration) : this(
        configuration = configuration,
        session = Session.start(context, configuration.environment, configuration.info),
        storageDirectory = configuration.storageDirectory ?: defaultStorageDirectory(context),
        transport = OkHttpTransport(configuration.allowsInsecureHttp),
        retryPolicy = RetryPolicy(),
        captureDiagnostics = Diagnostics(context.applicationContext)::capture,
        lifecycle = (context.applicationContext as? Application)?.let(::ActivityLifecycleSource),
    )

    /**
     * A random identifier sent with this instance's events, as the request's `session.id`.
     *
     * Generated each time an instance is created, typically once per app launch, and never reused,
     * so it links events within a session without identifying the user or device across sessions.
     */
    public val sessionId: UUID = session.id

    private val pipeline = EventPipeline(session, configuration, storageDirectory, transport, retryPolicy).also { it.start() }

    private val lifecycleObservation: Closeable? = lifecycle?.observe(
        onForeground = {
            if (configuration.logsAppLifecycleEvents) pipeline.log(EventRecord.from(Event.APP_FOREGROUNDED, now()))
        },
        onBackground = {
            if (configuration.logsAppLifecycleEvents) pipeline.log(EventRecord.from(Event.APP_BACKGROUNDED, now()))
            pipeline.flush()
        },
    )

    /**
     * Records a breadcrumb or error. Returns immediately; the event is persisted and sent in the
     * background with this instance's session data and the current time.
     *
     * Errors also have the app's CPU and memory usage, the memory it has left, the device's thermal
     * state, and whether Battery Saver is on added to `info`, captured during this call. They
     * replace any values already in `info` under the same keys, such as `cpu_percent`.
     *
     * Names that aren't lower snake case are handled according to
     * [DavyConfiguration.onInvalidEventName], and events over [DavyConfiguration.maxEventSize] are
     * replaced with an error.
     *
     * @throws IllegalArgumentException if the name isn't lower snake case and
     *   [DavyConfiguration.onInvalidEventName] is [InvalidEventNameAction.CRASH].
     */
    public fun log(event: Event) {
        var accepted = eventToLog(event) ?: return
        val timestamp = now()
        if (accepted is Event.Error) {
            accepted = accepted.copy(info = accepted.info + captureDiagnostics())
        }
        pipeline.log(EventRecord.from(accepted, timestamp))
    }

    /**
     * Records an error named [name], with the class of [error] in `info` under `error_type`, for
     * example `IOException`. Any `error_type` already in [info] is replaced.
     *
     * The error's message isn't sent, since it can contain personal data. In builds minified with
     * R8, your own exception classes are renamed unless you keep their names.
     */
    public fun log(error: Throwable, name: String, info: Map<String, Any> = emptyMap()) {
        val type = error.javaClass.simpleName.ifEmpty { error.javaClass.name }
        log(Event.Error(name, info + (Event.ERROR_TYPE_INFO_KEY to type)))
    }

    /**
     * Runs [block], returns its result, and logs a breadcrumb named [name] with how long it took in
     * milliseconds under `duration_ms` in `info`. The breadcrumb is logged even if [block] throws.
     * [block] can suspend when called from a coroutine.
     */
    public inline fun <T> measure(name: String, block: () -> T): T {
        val startNanos = System.nanoTime()
        try {
            return block()
        } finally {
            logDuration(name, startNanos)
        }
    }

    @PublishedApi
    internal fun logDuration(name: String, startNanos: Long) {
        val milliseconds = ((System.nanoTime() - startNanos) / 1_000_000.0).roundToLong()
        log(Event.Breadcrumb(name, mapOf(Event.DURATION_INFO_KEY to milliseconds)))
    }

    /**
     * Adds [info] to this session's `info`, replacing any existing values for the same keys, and
     * logs a `session_updated` breadcrumb.
     *
     * Returns immediately; the update and breadcrumb are processed in order with [log], so the
     * `session_updated` breadcrumb and every event logged after this call carry the updated
     * `session.info` (and each change starts a new batch). As with event `info`, don't include
     * personal data or values that identify a person or device across sessions.
     */
    public fun addSessionInfo(info: Map<String, Any>) {
        val record = EventRecord.from(Event.Breadcrumb(Event.SESSION_UPDATED_NAME, info), now())
        pipeline.addSessionInfo(InfoValues.normalize(info), record)
    }

    /**
     * Sends all pending events now, bypassing the debounce interval and any retry backoff, but not
     * a pause the server asked for with `Retry-After`. Returns immediately.
     */
    public fun flush() {
        pipeline.flush()
    }

    /**
     * Sends all pending events now, like [flush], and waits for the attempt to finish.
     *
     * Returns once the server has responded to every pending event, or sending stops because of an
     * error or a pause the server asked for with `Retry-After`. Events that weren't delivered stay
     * on disk and are sent later.
     */
    public suspend fun flushAndWait() {
        suspendCoroutine { continuation -> flush { continuation.resume(Unit) } }
    }

    /** Like [flushAndWait], calling [onComplete] on a background thread instead. */
    internal fun flush(onComplete: () -> Unit) {
        pipeline.flush(onComplete)
    }

    /**
     * Returns the events waiting to be sent, the events and requests recently sent, and the most
     * recent failure to send, for checking your events during development. [DavyDebugActivity]
     * shows the same information. Includes every event logged before this call.
     */
    public suspend fun debugSnapshot(): DavyDebugSnapshot = suspendCoroutine { continuation ->
        pipeline.debugSnapshot { continuation.resume(it) }
    }

    /**
     * Calls [listener] with a [DavyDebugSnapshot] right away, then each time events are logged,
     * sent, or fail to send, until you close the returned [Closeable]. Changes made close together
     * produce a single snapshot.
     *
     * [listener] is called on a background thread, one call at a time, so switch to the main
     * thread to update views.
     */
    public fun addDebugListener(listener: (DavyDebugSnapshot) -> Unit): Closeable = pipeline.addDebugListener(listener)

    /**
     * Forgets the events recorded as sent and the requests recorded as finished, so
     * [DavyDebugSnapshot.sent] and [DavyDebugSnapshot.requests] start empty again. Pending events
     * aren't affected.
     */
    public fun clearDebugHistory() {
        pipeline.clearDebugHistory()
    }

    /**
     * Stops observing the app's lifecycle and sending events. Events that haven't been sent stay on
     * disk, and are sent by the next instance that uses the same storage directory.
     */
    override fun close() {
        lifecycleObservation?.close()
        pipeline.close()
    }

    /** Applies [DavyConfiguration.onInvalidEventName] to [event], returning the event to log, if any. */
    private fun eventToLog(event: Event): Event? {
        val action = configuration.onInvalidEventName
        if (action == InvalidEventNameAction.SEND || Event.isLowerSnakeCase(event.name)) return event
        val problem = "Davy event name \"${event.name}\" isn't lower snake case, for example \"checkout_started\"."
        return when (action) {
            InvalidEventNameAction.CRASH -> throw IllegalArgumentException(problem)
            InvalidEventNameAction.LOG_ERROR -> {
                Log.e(TAG, "Discarded event. $problem")
                null
            }
            InvalidEventNameAction.SEND_ERROR -> Event.Error(Event.INVALID_NAME_ERROR_NAME, mapOf(Event.REJECTED_NAME_INFO_KEY to event.name))
            InvalidEventNameAction.IGNORE -> null
            InvalidEventNameAction.SEND -> event
        }
    }

    private fun now() = System.currentTimeMillis()

    public companion object {
        private const val TAG = "Davy"

        /** `davy` in the app's no-backup files directory, so pending events aren't backed up. */
        public fun defaultStorageDirectory(context: Context): File = File(context.noBackupFilesDir, "davy")
    }
}
