package com.gu.davy

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Records requests and answers each with the current status code and headers, after [delayMillis]. */
internal class MockTransport : EventTransport {
    private val lock = Any()
    private val requestList = mutableListOf<HttpRequest>()
    private val deliveredList = mutableListOf<HttpRequest>()
    private var statusCode = 200
    private var headers = emptyMap<String, String>()

    @Volatile
    var delayMillis = 0L

    /** Every request, in the order they started. */
    val requests: List<HttpRequest> get() = synchronized(lock) { requestList.toList() }

    /** Requests answered with a 2xx status. */
    val delivered: List<HttpRequest> get() = synchronized(lock) { deliveredList.toList() }

    val attempts: Int get() = requests.size

    /** JSON bodies of requests answered with a 2xx status. */
    val payloads: List<JSONObject> get() = delivered.map { JSONObject(it.body.decodeToString()) }

    val sentEvents: List<JSONObject> get() = payloads.flatMap { it.getJSONArray("events").objects() }
    val sentNames: List<String> get() = sentEvents.map { it.getString("name") }

    fun setFailing(failing: Boolean) = respond(if (failing) 500 else 200)

    fun respond(statusCode: Int, headers: Map<String, String> = emptyMap()) {
        synchronized(lock) {
            this.statusCode = statusCode
            this.headers = headers
        }
    }

    override fun send(request: HttpRequest): HttpResponse {
        val (statusCode, headers) = synchronized(lock) {
            requestList += request
            statusCode to headers
        }
        if (delayMillis > 0) Thread.sleep(delayMillis)
        if (statusCode in 200..299) synchronized(lock) { deliveredList += request }
        return HttpResponse(statusCode, headers)
    }
}

/** Drives a real [LifecycleTracker] as if activities started and stopped. */
internal class FakeLifecycle : LifecycleSource {
    private var tracker: LifecycleTracker? = null

    override fun observe(onForeground: () -> Unit, onBackground: () -> Unit): Closeable {
        tracker = LifecycleTracker(onForeground, onBackground)
        return Closeable { tracker = null }
    }

    fun activityStarted() = tracker!!.activityStarted()

    fun activityStopped(isChangingConfigurations: Boolean = false) = tracker!!.activityStopped(isChangingConfigurations)
}

/** Diagnostics like Android's, so tests don't depend on the device. */
internal val testDiagnostics = mapOf<String, Any>(
    Diagnostics.CPU_PERCENT to 3L,
    Diagnostics.MEMORY_USED_MB to 42L,
    Diagnostics.MEMORY_AVAILABLE_MB to 214L,
    Diagnostics.THERMAL_STATE to "none",
    Diagnostics.LOW_POWER_MODE to false,
)

internal fun temporaryDirectory(): File = Files.createTempDirectory("DavyTests").toFile()

internal fun testSession(environment: String? = null, info: Map<String, Any>? = null) = Session(
    id = UUID.randomUUID(),
    environment = environment,
    appVersion = "1.2.0",
    build = "45",
    os = Session.OS,
    osVersion = "15.0.0",
    deviceModel = "Pixel 9",
    locale = "en_GB",
    info = info?.takeIf { it.isNotEmpty() },
)

internal fun makeDavy(
    directory: File,
    transport: EventTransport,
    endpoint: String = "https://example.com/events",
    environment: String? = null,
    info: Map<String, Any> = emptyMap(),
    onInvalidEventName: InvalidEventNameAction = InvalidEventNameAction.SEND,
    maxEventSize: Int = 4096,
    logsAppLifecycleEvents: Boolean = false,
    headers: Map<String, String> = emptyMap(),
    batchSize: Int = 100,
    debounceInterval: Duration = 60.seconds,
    debugHistoryLimit: Int = 200,
    sendsEvents: Boolean = true,
    lifecycle: LifecycleSource? = null,
    session: Session = testSession(environment, info),
): Davy = Davy(
    configuration = DavyConfiguration(
        endpoint = java.net.URI(endpoint),
        environment = environment,
        info = info,
        onInvalidEventName = onInvalidEventName,
        maxEventSize = maxEventSize,
        logsAppLifecycleEvents = logsAppLifecycleEvents,
        headers = headers,
        batchSize = batchSize,
        debounceInterval = debounceInterval,
        storageDirectory = directory,
        debugHistoryLimit = debugHistoryLimit,
        sendsEvents = sendsEvents,
    ),
    session = session,
    storageDirectory = directory,
    transport = transport,
    retryPolicy = RetryPolicy(initialDelay = 60.seconds, maxDelay = 60.seconds),
    captureDiagnostics = { testDiagnostics },
    lifecycle = lifecycle,
)

/** Runs [block] as a coroutine, without kotlinx.coroutines, and waits for it to finish. */
internal fun runSuspending(block: suspend () -> Unit) {
    val finished = CountDownLatch(1)
    var failure: Throwable? = null
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { result ->
            failure = result.exceptionOrNull()
            finished.countDown()
        },
    )
    check(finished.await(30, TimeUnit.SECONDS)) { "Timed out" }
    failure?.let { throw it }
}

internal val testDestination = RequestBuilder(DavyConfiguration(java.net.URI("https://example.com/events"))).destination

internal fun Davy.flushAndWaitBlocking() = runSuspending { flushAndWait() }

internal fun Davy.debugSnapshotBlocking(): DavyDebugSnapshot {
    var snapshot: DavyDebugSnapshot? = null
    runSuspending { snapshot = debugSnapshot() }
    return snapshot!!
}

/** Polls [condition] until it holds or [timeoutMillis] elapses. */
internal fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

internal fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith(::getString)

/** An `info` object's values as [String], [Long], [Double], or [Boolean]. */
internal fun JSONObject.infoValues(): Map<String, Any> = keys().asSequence().associateWith { InfoValues.normalize(get(it)) }

/** `info` without the diagnostics added to errors. */
internal fun JSONObject.loggedInfo(): Map<String, Any>? =
    optJSONObject("info")?.infoValues()?.filterKeys { it !in Diagnostics.KEYS }?.takeIf { it.isNotEmpty() }
