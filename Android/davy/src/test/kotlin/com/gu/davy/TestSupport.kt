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
    private val concurrencyList = mutableListOf<Int>()
    private var running = 0
    private var statusCode = 200
    private var headers = emptyMap<String, String>()

    @Volatile
    var delayMillis = 0L

    /** Every request, in the order they started. */
    val requests: List<HttpRequest> get() = synchronized(lock) { requestList.toList() }

    /** Requests answered with a 2xx status. */
    val delivered: List<HttpRequest> get() = synchronized(lock) { deliveredList.toList() }

    /** How many requests were in flight as each one started, including itself. */
    val concurrencyAtStart: List<Int> get() = synchronized(lock) { concurrencyList.toList() }

    val attempts: Int get() = requests.size

    /** JSON bodies of requests answered with a 2xx status. */
    val payloads: List<JSONObject> get() = delivered.mapNotNull { request -> request.body?.let { JSONObject(it.decodeToString()) } }

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
            running += 1
            concurrencyList += running
            statusCode to headers
        }
        try {
            if (delayMillis > 0) Thread.sleep(delayMillis)
            if (statusCode in 200..299) synchronized(lock) { deliveredList += request }
            return HttpResponse(statusCode, headers)
        } finally {
            synchronized(lock) { running -= 1 }
        }
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
internal val testDiagnostics = mapOf(
    Diagnostics.CPU_PERCENT to "3",
    Diagnostics.MEMORY_USED_MB to "42",
    Diagnostics.MEMORY_AVAILABLE_MB to "214",
    Diagnostics.THERMAL_STATE to "none",
    Diagnostics.LOW_POWER_MODE to "false",
)

internal fun temporaryDirectory(): File = Files.createTempDirectory("DavyTests").toFile()

internal fun testSession(environment: String? = null) = Session(
    id = UUID.randomUUID(),
    environment = environment,
    appVersion = "1.2.0",
    build = "45",
    os = Session.OS,
    osVersion = "15.0.0",
    deviceModel = "Pixel 9",
    locale = "en_GB",
)

internal fun makeDavy(
    directory: File,
    transport: EventTransport,
    endpoint: String = "https://example.com/events",
    format: RequestFormat = RequestFormat.JsonBatch,
    environment: String? = null,
    onInvalidEventName: InvalidEventNameAction = InvalidEventNameAction.SEND,
    maxEventSize: Int = 2048,
    logsAppLifecycleEvents: Boolean = false,
    headers: Map<String, String> = emptyMap(),
    batchSize: Int = 100,
    debounceInterval: Duration = 60.seconds,
    lifecycle: LifecycleSource? = null,
    session: Session = testSession(environment),
): Davy = Davy(
    configuration = DavyConfiguration(
        endpoint = java.net.URI(endpoint),
        format = format,
        environment = environment,
        onInvalidEventName = onInvalidEventName,
        maxEventSize = maxEventSize,
        logsAppLifecycleEvents = logsAppLifecycleEvents,
        headers = headers,
        batchSize = batchSize,
        debounceInterval = debounceInterval,
        storageDirectory = directory,
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

internal fun Davy.flushAndWaitBlocking() = runSuspending { flushAndWait() }

/** Polls [condition] until it holds or [timeoutMillis] elapses. */
internal fun waitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

/** The query parameters of [request]'s URL, percent-decoded. */
internal fun queryParameters(request: HttpRequest): Map<String, String> =
    request.url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { item ->
        val (name, value) = item.split('=', limit = 2)
        name to OpenApiSpec.percentDecoded(value)!!
    }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

internal fun JSONObject.strings(): Map<String, String> = keys().asSequence().associateWith(::getString)

/** `info` without the diagnostics added to errors. */
internal fun JSONObject.loggedInfo(): Map<String, String>? =
    optJSONObject("info")?.strings()?.filterKeys { it !in Diagnostics.KEYS }?.takeIf { it.isNotEmpty() }
