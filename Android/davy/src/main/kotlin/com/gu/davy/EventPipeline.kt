package com.gu.davy

import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Processes logged events in order: persists them, then sends them.
 *
 * Every change to its state runs on a single thread, one at a time, like an actor. Requests run on
 * other threads, so new events keep being written to disk while requests are in flight, and their
 * outcomes are handled back on that thread.
 *
 * Only one drain runs at a time; it sends pending events oldest first, with up to
 * `maxConcurrentRequests` requests in flight, until the store is empty, a request fails, or the
 * server asks for a pause.
 *
 * - A failure stops new requests and schedules a retry with exponential backoff. The retry starts
 *   with a single request, and only resumes full concurrency once that succeeds.
 * - A `Retry-After` pause stops all requests until it ends, even when flushing, and is persisted so
 *   a relaunch doesn't skip it.
 * - Rejected events are discarded so they don't block the queue.
 */
internal class EventPipeline(
    private val session: Session,
    private val configuration: DavyConfiguration,
    private val storageDirectory: File,
    private val transport: EventTransport,
    private val retryPolicy: RetryPolicy,
) {
    /** Pending events to send in one request, all from the same session. */
    private class Delivery(val stored: List<StoredEvent>, val session: Session, val events: List<EventRecord>) {
        val fileNames: List<String> get() = stored.map { it.fileName }
    }

    private class Pending(val stored: StoredEvent, val session: Session, val event: EventRecord)

    private val batchSize = configuration.batchSize.coerceAtLeast(1)
    private val eventsPerRequest: Int
    private val maxConcurrentRequests: Int

    init {
        when (val format = configuration.format) {
            RequestFormat.JsonBatch -> {
                eventsPerRequest = batchSize
                maxConcurrentRequests = 1
            }
            is RequestFormat.JsonEvent -> {
                eventsPerRequest = 1
                maxConcurrentRequests = format.maxConcurrentRequests.coerceAtLeast(1)
            }
            is RequestFormat.QueryString -> {
                eventsPerRequest = 1
                maxConcurrentRequests = format.maxConcurrentRequests.coerceAtLeast(1)
            }
        }
    }

    private val requestBuilder = RequestBuilder(configuration)
    private val pauseFile = File(storageDirectory, "paused-until")

    /** Runs every change to the state below, one at a time. */
    private val executor = ScheduledThreadPoolExecutor(1) { Thread(it, "Davy").apply { isDaemon = true } }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
        removeOnCancelPolicy = true
    }

    /** Sends requests, up to [maxConcurrentRequests] at once. */
    private val network: ExecutorService = Executors.newFixedThreadPool(maxConcurrentRequests) {
        Thread(it, "Davy network").apply { isDaemon = true }
    }

    private lateinit var store: FileEventStore
    private var debounce: ScheduledFuture<*>? = null

    /** Waits out a backoff or a server-requested pause, then sends again. */
    private var retry: ScheduledFuture<*>? = null
    private var isDraining = false
    private var drainFailed = false
    private var running = 0
    private var concurrency = 1
    private var consecutiveFailures = 0

    /** Whether the next drain starts with a single request, after a failure or pause. */
    private var isProbing = false

    /** File names of events in requests that haven't finished, so no event is sent twice at once. */
    private val inFlight = mutableSetOf<String>()

    /** When a server-requested pause ends, in milliseconds since 1970. */
    private var pausedUntil: Long? = null
    private val flushCompletions = mutableListOf<() -> Unit>()

    /** Events the server responded to, oldest first, up to the debug history limit. */
    private val sentHistory = ArrayDeque<DavyDebugSnapshot.SentEvent>()

    /** Requests in the order they finished, up to the debug history limit. */
    private val requestHistory = ArrayDeque<DavyDebugSnapshot.Request>()

    /** The most recent failure to send, cleared once the server responds to a request. */
    private var lastFailure: DavyDebugSnapshot.Failure? = null

    /** Events logged and sent each minute, oldest first, back to an hour ago. */
    private val activity = mutableListOf<DavyDebugSnapshot.Activity>()

    /** Events left over from earlier launches when the store was opened, plus the events stored since, including any since discarded. */
    private var loggedCount = 0

    /** Events the server accepted or rejected since this instance was created. */
    private var sentCount = 0

    /** When the first event this instance knows about was logged, for each session. */
    private val sessionStarts = mutableMapOf<UUID, Long>()
    private val debugListeners = linkedMapOf<Any, (DavyDebugSnapshot) -> Unit>()
    private var isDebugUpdateScheduled = false

    /** Reads pending events from disk and starts sending any left over from a previous session. */
    fun start() {
        submit {
            store = FileEventStore(File(storageDirectory, "events"))
            loggedCount = store.count
            pruneExpiredEvents()
            restorePause()
            recordLeftoverEventsAsLogged()
            if (store.count > 0) requestSend(force = false)
        }
    }

    fun log(record: EventRecord) {
        submit { persist(record) }
    }

    /**
     * Sends all pending events, ignoring any retry backoff but not a server-requested pause, then
     * calls [onComplete] once the attempt finishes.
     */
    fun flush(onComplete: (() -> Unit)? = null) {
        val submitted = submit {
            requestSend(force = true)
            if (onComplete != null) {
                if (isDraining) flushCompletions += onComplete else onComplete()
            }
        }
        if (!submitted) onComplete?.invoke()
    }

    /** Stops sending. Pending events stay on disk for the next launch. */
    fun close() {
        submit {
            debounce?.cancel(false)
            retry?.cancel(false)
            completeFlushes()
            debugListeners.clear()
        }
        executor.shutdown()
        network.shutdown()
    }

    /** Calls [onSnapshot] with the current [DavyDebugSnapshot], or an empty one if closed. */
    fun debugSnapshot(onSnapshot: (DavyDebugSnapshot) -> Unit) {
        if (!submit { onSnapshot(debugSnapshot()) }) onSnapshot(DavyDebugSnapshot.EMPTY)
    }

    /** Calls [listener] with a snapshot now and after each change, until the returned [Closeable] is closed. */
    fun addDebugListener(listener: (DavyDebugSnapshot) -> Unit): Closeable {
        val token = Any()
        submit {
            debugListeners[token] = listener
            listener(debugSnapshot())
        }
        return Closeable { submit { debugListeners.remove(token) } }
    }

    /** Forgets the events recorded as sent and the requests recorded as finished. */
    fun clearDebugHistory() {
        submit {
            sentHistory.clear()
            requestHistory.clear()
            debugStateDidChange()
        }
    }

    private fun persist(record: EventRecord) {
        val storedRecord = recordWithinMaxEventSize(record) ?: return
        try {
            store.append(DavyJson.pendingEvent(session, storedRecord).toByteArray(), storedRecord.timestampMillis)
        } catch (exception: IOException) {
            Log.e(TAG, "Failed to store event", exception)
            return
        }
        loggedCount += 1
        recordActivity(logged = 1, atMillis = storedRecord.timestampMillis)
        recordSessionEvent(session.id, storedRecord.timestampMillis)
        debugStateDidChange()
        if (store.count >= batchSize) requestSend(force = false) else scheduleDebouncedSend()
    }

    /**
     * Returns [record] if it fits in the maximum event size, otherwise a `davy_event_too_large`
     * error in its place, or `null` if even that doesn't fit.
     */
    private fun recordWithinMaxEventSize(record: EventRecord): EventRecord? {
        val maxSize = configuration.maxEventSize
        val size = requestBuilder.size(record, session)
        if (size <= maxSize) return record

        val info = mutableMapOf(Event.REJECTED_NAME_INFO_KEY to record.name, Event.SIZE_INFO_KEY to size.toString())
        // An error's diagnostics describe when it happened, so they're kept with the replacement.
        if (record.type == EventRecord.Type.ERROR) {
            record.info.orEmpty().filterKeys { it in Diagnostics.KEYS && it !in info }.forEach { (key, value) -> info[key] = value }
        }
        val replacement = EventRecord.from(Event.Error(Event.TOO_LARGE_ERROR_NAME, info), record.timestampMillis)
        if (requestBuilder.size(replacement, session) > maxSize) {
            Log.e(TAG, "Discarded event \"${record.name}\": $size bytes is over maxEventSize ($maxSize bytes)")
            return null
        }
        Log.e(TAG, "Replaced event \"${record.name}\" with \"${Event.TOO_LARGE_ERROR_NAME}\": $size bytes is over maxEventSize ($maxSize bytes)")
        return replacement
    }

    private fun scheduleDebouncedSend() {
        debounce?.cancel(false)
        debounce = schedule(configuration.debounceInterval.inWholeMilliseconds) {
            debounce = null
            requestSend(force = false)
        }
    }

    /**
     * Starts draining pending events unless a drain is already running or the server asked for a
     * pause. [force] skips any retry backoff currently in effect.
     */
    private fun requestSend(force: Boolean) {
        debounce?.cancel(false)
        debounce = null
        // A server-requested pause is honored even when flushing; it ends by itself.
        if (pausedUntil != null) return
        if (force) {
            retry?.cancel(false)
            retry = null
        }
        if (retry != null || isDraining) return

        isDraining = true
        drainFailed = false
        pruneExpiredEvents()
        concurrency = if (isProbing) 1 else maxConcurrentRequests
        fill()
    }

    /** Starts requests until the concurrency limit is reached, then finishes the drain once none are running. */
    private fun fill() {
        while (!drainFailed && pausedUntil == null && running < concurrency) {
            val delivery = nextDelivery() ?: break
            inFlight += delivery.fileNames
            running += 1
            try {
                network.execute {
                    val (outcome, request) = send(delivery)
                    submit { finish(delivery, outcome, request) }
                }
            } catch (_: RejectedExecutionException) {
                // Closed, so nothing more is sent.
                running -= 1
                inFlight -= delivery.fileNames.toSet()
                break
            }
        }
        if (running == 0) finishDrain()
    }

    /**
     * Builds and sends [delivery]'s request, returning what the result means for its events and, if
     * the request was sent and the debug history keeps requests, a record of it.
     */
    private fun send(delivery: Delivery): Pair<DeliveryOutcome, DavyDebugSnapshot.Request?> {
        val sentAt = now()
        val request = try {
            requestBuilder.request(delivery.session, delivery.events, sentAt)
        } catch (exception: IllegalArgumentException) {
            return DeliveryOutcome.Failed("Couldn't build request: ${exception.message}") to null
        }
        val start = System.nanoTime()
        val result = transport.deliver(request)
        val durationMillis = (System.nanoTime() - start) / 1_000_000
        if (configuration.debugHistoryLimit <= 0) return result.outcome to null

        val record = DavyDebugSnapshot.Request(
            id = UUID.randomUUID(),
            method = request.method,
            url = request.url,
            headers = request.headers.toMap(),
            body = request.body?.decodeToString(),
            eventIds = delivery.events.map { it.id },
            sentAtMillis = sentAt,
            durationMillis = durationMillis,
            statusCode = result.statusCode,
            outcome = when (val outcome = result.outcome) {
                DeliveryOutcome.Delivered -> DavyDebugSnapshot.Request.Outcome.Delivered
                is DeliveryOutcome.Rejected -> DavyDebugSnapshot.Request.Outcome.Rejected
                is DeliveryOutcome.Throttled -> DavyDebugSnapshot.Request.Outcome.Throttled(outcome.retryAfterSeconds)
                is DeliveryOutcome.Failed -> DavyDebugSnapshot.Request.Outcome.Failed(outcome.reason)
            },
        )
        return result.outcome to record
    }

    private fun finish(delivery: Delivery, outcome: DeliveryOutcome, request: DavyDebugSnapshot.Request?) {
        running -= 1
        inFlight -= delivery.fileNames.toSet()
        request?.let(::recordRequest)
        when (outcome) {
            DeliveryOutcome.Delivered -> {
                store.remove(delivery.stored)
                recordSent(delivery, DavyDebugSnapshot.SentEvent.Outcome.Delivered)
                consecutiveFailures = 0
                isProbing = false
                concurrency = maxConcurrentRequests
            }
            is DeliveryOutcome.Rejected -> {
                Log.e(TAG, "Discarding ${delivery.events.size} event(s) rejected with HTTP ${outcome.statusCode}")
                store.remove(delivery.stored)
                recordSent(delivery, DavyDebugSnapshot.SentEvent.Outcome.Rejected(outcome.statusCode))
                isProbing = false
                concurrency = maxConcurrentRequests
            }
            is DeliveryOutcome.Throttled -> {
                Log.i(TAG, "Server asked to retry after ${outcome.retryAfterSeconds} seconds")
                pause(outcome.retryAfterSeconds)
                recordFailure("Server asked to retry after ${ceil(outcome.retryAfterSeconds).toLong()} seconds")
            }
            is DeliveryOutcome.Failed -> {
                Log.e(TAG, "Failed to send ${delivery.events.size} event(s): ${outcome.reason}")
                drainFailed = true
                recordFailure(outcome.reason)
            }
        }
        fill()
    }

    private fun finishDrain() {
        isDraining = false
        if (pausedUntil != null) {
            isProbing = true
            schedulePauseEnd()
        } else if (drainFailed) {
            isProbing = true
            scheduleRetry()
        }
        completeFlushes()
    }

    private fun completeFlushes() {
        val completions = flushCompletions.toList()
        flushCompletions.clear()
        completions.forEach { it() }
    }

    /**
     * The oldest pending events not already in flight that can go in one request: up to
     * `eventsPerRequest` events from the same session.
     */
    private fun nextDelivery(): Delivery? {
        while (true) {
            val batch = store.oldest(eventsPerRequest, inFlight)
            if (batch.isEmpty()) return null
            val decoded = decode(batch)
            val session = decoded.firstOrNull()?.session ?: continue
            // Each request carries a single session, so take the leading run of events from the
            // oldest session; any later sessions go in the next request.
            val run = decoded.takeWhile { it.session.id == session.id }
            return Delivery(run.map { it.stored }, session, run.map { it.event })
        }
    }

    /** Decodes stored events, discarding any that can't be decoded so they don't block the queue. */
    private fun decode(batch: List<StoredEvent>): List<Pending> {
        val undecodable = mutableListOf<StoredEvent>()
        val decoded = batch.mapNotNull { stored ->
            val pending = DavyJson.parsePendingEvent(stored.data.decodeToString())
            if (pending == null) undecodable += stored
            pending?.let { (session, event) -> Pending(stored, session, event) }
        }
        if (undecodable.isNotEmpty()) {
            Log.e(TAG, "Discarding ${undecodable.size} undecodable event(s)")
            store.discard(undecodable)
        }
        return decoded
    }

    private fun scheduleRetry() {
        consecutiveFailures += 1
        val delay = retryPolicy.delay(consecutiveFailures)
        retry?.cancel(false)
        retry = schedule(delay.inWholeMilliseconds) {
            retry = null
            requestSend(force = false)
        }
    }

    /** Pauses sending for a server's `Retry-After` value, unless a longer pause is already set. */
    private fun pause(retryAfterSeconds: Double) {
        val until = now() + retryPolicy.pause(retryAfterSeconds).inWholeMilliseconds
        val current = pausedUntil
        if (current != null && current >= until) return
        pausedUntil = until
        try {
            val temporary = File(storageDirectory, "${pauseFile.name}.tmp")
            temporary.writeText(until.toString())
            if (!temporary.renameTo(pauseFile)) throw IOException("Failed to move $temporary into place")
        } catch (exception: IOException) {
            Log.e(TAG, "Failed to store pause", exception)
        }
    }

    /** Resumes a pause stored by a previous launch, if it hasn't ended yet. */
    private fun restorePause() {
        val stored = try {
            pauseFile.readText().trim().toLongOrNull()
        } catch (_: IOException) {
            null
        }
        val now = now()
        if (stored == null || stored <= now) {
            pauseFile.delete()
            return
        }
        // Capped in case the device clock moved backwards since the pause was stored.
        pausedUntil = minOf(stored, now + retryPolicy.maxRetryAfter.inWholeMilliseconds)
        isProbing = true
        schedulePauseEnd()
    }

    private fun schedulePauseEnd() {
        val until = pausedUntil ?: return
        retry?.cancel(false)
        retry = schedule((until - now()).coerceAtLeast(0)) {
            pausedUntil = null
            retry = null
            pauseFile.delete()
            requestSend(force = false)
        }
    }

    private fun pruneExpiredEvents() {
        val count = store.count
        store.prune(now() - configuration.maxEventAge.inWholeMilliseconds)
        if (store.count != count) debugStateDidChange()
    }

    private fun recordSent(delivery: Delivery, outcome: DavyDebugSnapshot.SentEvent.Outcome) {
        lastFailure = null
        val sentAt = now()
        sentCount += delivery.events.size
        recordActivity(sent = delivery.events.size, atMillis = sentAt)
        delivery.events.forEach { recordSessionEvent(delivery.session.id, it.timestampMillis) }
        val limit = configuration.debugHistoryLimit
        if (limit > 0) {
            delivery.events.takeLast(limit).forEach { record ->
                sentHistory.addLast(DavyDebugSnapshot.SentEvent(DavyDebugSnapshot.LoggedEvent(record, delivery.session), sentAt, outcome))
            }
            while (sentHistory.size > limit) sentHistory.removeFirst()
        }
        debugStateDidChange()
    }

    private fun recordFailure(reason: String) {
        lastFailure = DavyDebugSnapshot.Failure(reason, now())
        debugStateDidChange()
    }

    private fun recordRequest(request: DavyDebugSnapshot.Request) {
        requestHistory.addLast(request)
        while (requestHistory.size > configuration.debugHistoryLimit) requestHistory.removeFirst()
        debugStateDidChange()
    }

    /**
     * Counts events left over from earlier launches as logged when they were, so activity includes
     * every event this instance knows about. Called before any of this instance's events are
     * stored, so none are counted twice.
     */
    private fun recordLeftoverEventsAsLogged() {
        val start = DavyDebugSnapshot.activityStart(now())
        store.loggedTimes.filter { it >= start }.forEach { recordActivity(logged = 1, atMillis = it) }
    }

    /** Adds to the counts for the minute containing [atMillis], and forgets minutes that are now over an hour old. */
    private fun recordActivity(logged: Int = 0, sent: Int = 0, atMillis: Long) {
        val minute = DavyDebugSnapshot.Activity.minuteContaining(atMillis)
        val index = activity.indexOfFirst { it.minuteMillis == minute }
        if (index >= 0) {
            val current = activity[index]
            activity[index] = current.copy(logged = current.logged + logged, sent = current.sent + sent)
        } else {
            // Usually the newest minute, unless the clock moved backwards.
            val later = activity.indexOfFirst { it.minuteMillis > minute }
            activity.add(if (later >= 0) later else activity.size, DavyDebugSnapshot.Activity(minute, logged, sent))
        }
        val start = DavyDebugSnapshot.activityStart(now())
        activity.removeAll { it.minuteMillis < start }
    }

    /**
     * Moves the session's start earlier if this is the earliest of its events seen so far. Events
     * left over from earlier launches are seen as they're sent, or read back from disk for a
     * snapshot, so their sessions are known by the time a snapshot needs them.
     */
    private fun recordSessionEvent(sessionId: UUID, loggedAtMillis: Long) {
        val start = sessionStarts[sessionId]
        if (start == null || loggedAtMillis < start) sessionStarts[sessionId] = loggedAtMillis
    }

    /**
     * Every pending event read back from disk, discarding any that can't be decoded so the counts
     * add up, with the sent history, requests, last failure, counts, activity, and session starts.
     */
    private fun debugSnapshot(): DavyDebugSnapshot {
        val pending = decode(store.oldest(store.count)).map { DavyDebugSnapshot.LoggedEvent(it.event, it.session) }
        pending.forEach { recordSessionEvent(it.sessionId, it.timestampMillis) }
        val activityStart = DavyDebugSnapshot.activityStart(now())
        return DavyDebugSnapshot(
            pending = pending,
            sent = sentHistory.toList(),
            requests = requestHistory.toList(),
            loggedCount = loggedCount - store.discardedCount,
            sentCount = sentCount,
            lastFailure = lastFailure,
            activity = activity.filter { it.minuteMillis >= activityStart },
            sessionStarts = sessionStarts.filterValues { it >= activityStart }
                .map { (sessionId, date) -> DavyDebugSnapshot.SessionStart(sessionId, date) }
                .sortedBy { it.dateMillis },
        )
    }

    /**
     * Sends listeners a new snapshot once the current work is done, so a burst of changes, such as
     * many events logged at once, reads pending events from disk only once.
     */
    private fun debugStateDidChange() {
        if (debugListeners.isEmpty() || isDebugUpdateScheduled) return
        isDebugUpdateScheduled = true
        submit {
            isDebugUpdateScheduled = false
            if (debugListeners.isEmpty()) return@submit
            val snapshot = debugSnapshot()
            debugListeners.values.toList().forEach { it(snapshot) }
        }
    }

    private fun now() = System.currentTimeMillis()

    /** Runs [task] on the pipeline's thread, returning `false` if the pipeline is closed. */
    private fun submit(task: () -> Unit): Boolean = try {
        executor.execute { runLogging(task) }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun schedule(delayMillis: Long, task: () -> Unit): ScheduledFuture<*>? = try {
        executor.schedule({ runLogging(task) }, delayMillis, TimeUnit.MILLISECONDS)
    } catch (_: RejectedExecutionException) {
        null
    }

    private fun runLogging(task: () -> Unit) {
        try {
            task()
        } catch (exception: Exception) {
            Log.e(TAG, "Unexpected error", exception)
        }
    }

    private companion object {
        const val TAG = "Davy"
    }
}
