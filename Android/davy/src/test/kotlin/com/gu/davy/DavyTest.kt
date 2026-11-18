package com.gu.davy

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/** Ported from the Swift client's `DavyTests`, so both clients behave the same. */
class DavyTest {
    private val directories = mutableListOf<File>()
    private val instances = mutableListOf<Davy>()

    private fun directory(): File = temporaryDirectory().also { directories += it }

    private fun Davy.tracked(): Davy = also { instances += it }

    @After
    fun tearDown() {
        instances.forEach(Davy::close)
        directories.forEach { it.deleteRecursively() }
    }

    @Test
    fun breadcrumbsAndErrorsAreSentInOrderWithSessionData() {
        val transport = MockTransport()
        val session = testSession("beta", mapOf("edition" to "uk"))
        val telemetry = makeDavy(
            directory(),
            transport,
            environment = "beta",
            info = mapOf("edition" to "uk"),
            session = session,
        ).tracked()

        telemetry.log(Event.Breadcrumb("checkout_started"))
        telemetry.log(Event.Error("payment_declined", mapOf("reason" to "card_expired")))
        telemetry.log(Event.Breadcrumb("checkout_cancelled"))
        telemetry.flushAndWaitBlocking()

        val payloads = transport.payloads
        assertEquals(1, payloads.size)
        val events = payloads.single().getJSONArray("events").objects()
        assertEquals(listOf("breadcrumb", "error", "breadcrumb"), events.map { it.getString("type") })
        assertEquals(listOf("checkout_started", "payment_declined", "checkout_cancelled"), events.map { it.getString("name") })
        assertEquals(listOf(null, mapOf("reason" to "card_expired"), null), events.map { it.loggedInfo() })
        assertEquals(3, events.map { it.getString("id") }.toSet().size)
        for (event in events) {
            assertTrue(abs(DavyJson.parseTimestamp(event.getString("timestamp")) - System.currentTimeMillis()) < 60_000)
        }

        val sent = DavyJson.parseSession(payloads.single().getJSONObject("session"))
        assertEquals(session, sent)
        assertEquals(telemetry.sessionId, sent.id)
    }

    @Test
    fun addSessionInfoReplacesMatchingKeysAndLogsASessionUpdatedBreadcrumb() {
        val transport = MockTransport()
        val session = testSession("beta", mapOf("edition" to "uk", "theme" to "dark"))
        val telemetry = makeDavy(
            directory(),
            transport,
            environment = "beta",
            info = mapOf("edition" to "uk", "theme" to "dark"),
            session = session,
        ).tracked()

        telemetry.log(Event.Breadcrumb("app_launched"))
        telemetry.addSessionInfo(mapOf("edition" to "au", "tier" to "supporter"))
        telemetry.log(Event.Breadcrumb("article_opened"))
        telemetry.flushAndWaitBlocking()

        val payloads = transport.payloads
        assertEquals(2, payloads.size)
        assertEquals(listOf(telemetry.sessionId, telemetry.sessionId), payloads.map { DavyJson.parseSession(it.getJSONObject("session")).id })
        assertEquals(mapOf("edition" to "uk", "theme" to "dark"), DavyJson.parseSession(payloads[0].getJSONObject("session")).info)
        assertEquals(listOf("app_launched"), payloads[0].getJSONArray("events").objects().map { it.getString("name") })

        assertEquals(
            mapOf("edition" to "au", "theme" to "dark", "tier" to "supporter"),
            DavyJson.parseSession(payloads[1].getJSONObject("session")).info,
        )
        val updatedEvents = payloads[1].getJSONArray("events").objects()
        assertEquals(listOf("session_updated", "article_opened"), updatedEvents.map { it.getString("name") })
        assertEquals(listOf("breadcrumb", "breadcrumb"), updatedEvents.map { it.getString("type") })
        assertEquals(mapOf("edition" to "au", "tier" to "supporter"), updatedEvents[0].loggedInfo())
    }

    @Test
    fun errorsHaveDiagnosticsAddedToInfoButBreadcrumbsDoNot() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport, onInvalidEventName = InvalidEventNameAction.SEND_ERROR).tracked()

        telemetry.log(Event.Breadcrumb("checkout_started"))
        // Diagnostics replace any values already in `info` under the same keys.
        telemetry.log(Event.Error("payment_declined", mapOf("reason" to "card_expired", "low_power_mode" to "unknown")))
        // Errors that replace an invalid name are captured when logged too.
        telemetry.log(Event.Breadcrumb("checkoutStarted"))
        telemetry.flushAndWaitBlocking()

        val events = transport.sentEvents
        assertEquals(listOf("checkout_started", "payment_declined", "davy_invalid_event_name"), events.map { it.getString("name") })
        assertNull(events[0].optJSONObject("info"))
        assertEquals(listOf(mapOf("reason" to "card_expired"), mapOf("rejected_name" to "checkoutStarted")), events.drop(1).map { it.loggedInfo() })
        for (event in events.drop(1)) {
            assertEquals(testDiagnostics, event.getJSONObject("info").strings().filterKeys { it in Diagnostics.KEYS })
        }
    }

    @Test
    fun unsentEventsSurviveRelaunchAndAreSentWithTheirOriginalSession() {
        val directory = directory()
        val failingTransport = MockTransport().apply { setFailing(true) }
        val first = makeDavy(directory, failingTransport)
        first.log(Event.Breadcrumb("event-0"))
        first.log(Event.Error("event-1"))
        first.flushAndWaitBlocking()
        first.close()
        assertEquals(1, failingTransport.attempts)

        // The relaunched instance can't send the leftover events at first, so they're still pending
        // alongside the new session's event when it sends again.
        val transport = MockTransport().apply { setFailing(true) }
        val relaunched = makeDavy(directory, transport).tracked()
        assertTrue(waitUntil { transport.attempts == 1 })
        relaunched.log(Event.Breadcrumb("event-2"))
        transport.setFailing(false)
        relaunched.flushAndWaitBlocking()

        val payloads = transport.payloads
        assertNotEquals(first.sessionId, relaunched.sessionId)
        assertEquals(
            listOf(first.sessionId, relaunched.sessionId),
            payloads.map { DavyJson.parseSession(it.getJSONObject("session")).id },
        )
        assertEquals(
            listOf(listOf("event-0", "event-1"), listOf("event-2")),
            payloads.map { payload -> payload.getJSONArray("events").objects().map { it.getString("name") } },
        )
        assertEquals(
            listOf(listOf("breadcrumb", "error"), listOf("breadcrumb")),
            payloads.map { payload -> payload.getJSONArray("events").objects().map { it.getString("type") } },
        )
    }

    @Test
    fun reachingBatchSizeSendsWithoutWaitingForDebounce() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport, batchSize = 3).tracked()

        repeat(3) { telemetry.log(Event.Breadcrumb("event-$it")) }

        assertTrue(waitUntil { transport.sentEvents.size == 3 })
        assertEquals(1, transport.payloads.size)
    }

    @Test
    fun debounceSendsPartialBatchAfterQuietPeriod() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport, debounceInterval = 100.milliseconds).tracked()

        telemetry.log(Event.Breadcrumb("event-0"))
        telemetry.log(Event.Breadcrumb("event-1"))

        assertTrue(waitUntil { transport.sentEvents.size == 2 })
        assertEquals(1, transport.payloads.size)
    }

    @Test
    fun backgroundingFlushesAndLogsTransitionsWhenConfiguredTo() {
        for (logsAppLifecycleEvents in listOf(true, false)) {
            val transport = MockTransport()
            val lifecycle = FakeLifecycle()
            val telemetry = makeDavy(directory(), transport, logsAppLifecycleEvents = logsAppLifecycleEvents, lifecycle = lifecycle).tracked()

            // Like launching, an activity starting before the app ever leaves the foreground isn't logged.
            lifecycle.activityStarted()
            telemetry.log(Event.Breadcrumb("checkout_started"))
            // Recreating an activity, such as on rotation, isn't a transition.
            lifecycle.activityStopped(isChangingConfigurations = true)
            lifecycle.activityStarted()
            lifecycle.activityStopped()
            // The debounce interval is long, so only backgrounding sends these.
            val firstFlush = listOf("checkout_started") + if (logsAppLifecycleEvents) listOf("app_backgrounded") else emptyList()
            assertTrue("$logsAppLifecycleEvents", waitUntil { transport.sentNames == firstFlush })

            telemetry.log(Event.Breadcrumb("checkout_finished"))
            lifecycle.activityStarted()
            lifecycle.activityStopped()
            val secondFlush = listOf("checkout_finished") + if (logsAppLifecycleEvents) listOf("app_foregrounded", "app_backgrounded") else emptyList()
            assertTrue("$logsAppLifecycleEvents", waitUntil { transport.sentNames == firstFlush + secondFlush })
        }
    }

    @Test
    fun failedEventsAreKeptAndSentInBatchSizedChunks() {
        val transport = MockTransport().apply { setFailing(true) }
        val telemetry = makeDavy(directory(), transport, batchSize = 2).tracked()

        repeat(5) { telemetry.log(Event.Breadcrumb("event-$it")) }
        telemetry.flushAndWaitBlocking()
        assertTrue(transport.payloads.isEmpty())

        transport.setFailing(false)
        telemetry.flushAndWaitBlocking()

        assertEquals(listOf(2, 2, 1), transport.payloads.map { it.getJSONArray("events").length() })
        assertEquals((0 until 5).map { "event-$it" }, transport.sentNames)
    }

    @Test
    fun eventsOlderThanMaxEventAgeAreDiscarded() {
        val directory = directory()
        val eventsDirectory = File(directory, "events")
        val store = FileEventStore(eventsDirectory)
        val session = testSession()
        val now = System.currentTimeMillis()
        for ((name, loggedAt) in listOf("expired" to now - 31.days.inWholeMilliseconds, "recent" to now - 29.days.inWholeMilliseconds)) {
            val pending = DavyJson.pendingEvent(session, EventRecord.from(Event.Breadcrumb(name), loggedAt), testDestination)
            store.append(pending.toByteArray(), loggedAt)
        }

        val transport = MockTransport()
        val telemetry = makeDavy(directory, transport).tracked()
        telemetry.flushAndWaitBlocking()

        assertEquals(listOf("recent"), transport.sentNames)
        assertEquals(0, FileEventStore(eventsDirectory).count)
    }

    @Test
    fun onlyEventsBoundToTheCurrentEndpointAreSent() {
        val directory = directory()
        val eventsDirectory = File(directory, "events")
        val store = FileEventStore(eventsDirectory)
        val session = testSession()
        val loggedAt = System.currentTimeMillis() - 60_000
        val oldDestination = RequestBuilder(DavyConfiguration(URI("https://example.com/old"))).destination
        val old = DavyJson.pendingEvent(session, EventRecord.from(Event.Breadcrumb("old"), loggedAt), oldDestination)
        val legacy = DavyJson.prettyPendingEvent(session, EventRecord.from(Event.Breadcrumb("legacy"), loggedAt))
        store.append(old.toByteArray(), loggedAt)
        store.append(legacy.toByteArray(), loggedAt)

        val transport = MockTransport()
        val telemetry = makeDavy(directory, transport, batchSize = 1).tracked()
        telemetry.log(Event.Breadcrumb("current"))
        telemetry.flushAndWaitBlocking()

        assertEquals(listOf("current"), transport.sentNames)
        assertEquals(0, FileEventStore(eventsDirectory).count)
    }

    @Test
    fun undecodableStoredEventsAreDiscardedWithoutBlockingTheQueue() {
        val directory = directory()
        val eventsDirectory = File(directory, "events")
        FileEventStore(eventsDirectory).append("""{"not":"an event"}""".toByteArray(), System.currentTimeMillis() - 60_000)

        val transport = MockTransport()
        val telemetry = makeDavy(directory, transport).tracked()
        telemetry.log(Event.Breadcrumb("event-0"))
        telemetry.flushAndWaitBlocking()

        assertEquals(listOf("event-0"), transport.sentNames)
        assertEquals(0, FileEventStore(eventsDirectory).count)
    }

    @Test
    fun logIsSafeToCallFromManyThreads() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport).tracked()

        val threads = Executors.newFixedThreadPool(8)
        repeat(250) { index -> threads.execute { telemetry.log(Event.Breadcrumb("event-$index")) } }
        threads.shutdown()
        assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS))
        telemetry.flushAndWaitBlocking()

        val names = transport.sentNames
        assertEquals(250, names.size)
        assertEquals((0 until 250).map { "event-$it" }.toSet(), names.toSet())
    }

    @Test
    fun invalidEventNamesAreHandledAsConfigured() {
        val cases = mapOf(
            InvalidEventNameAction.SEND to listOf("checkout_started", "checkoutStarted", "payment-declined"),
            InvalidEventNameAction.LOG_ERROR to listOf("checkout_started"),
            InvalidEventNameAction.IGNORE to listOf("checkout_started"),
        )
        for ((action, expectedNames) in cases) {
            val transport = MockTransport()
            val telemetry = makeDavy(directory(), transport, onInvalidEventName = action).tracked()

            telemetry.log(Event.Breadcrumb("checkout_started"))
            telemetry.log(Event.Breadcrumb("checkoutStarted"))
            telemetry.log(Event.Error("payment-declined"))
            telemetry.flushAndWaitBlocking()

            assertEquals("$action", expectedNames, transport.sentNames)
        }
    }

    @Test
    fun invalidEventNamesAreReplacedWithAnErrorWhenConfiguredTo() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport, onInvalidEventName = InvalidEventNameAction.SEND_ERROR).tracked()

        telemetry.log(Event.Breadcrumb("checkout_started", mapOf("step" to "cart")))
        telemetry.log(Event.Breadcrumb("checkoutStarted", mapOf("step" to "cart")))
        telemetry.flushAndWaitBlocking()

        val events = transport.sentEvents
        assertEquals(listOf("breadcrumb", "error"), events.map { it.getString("type") })
        assertEquals(listOf("checkout_started", "davy_invalid_event_name"), events.map { it.getString("name") })
        assertEquals(listOf(mapOf("step" to "cart"), mapOf("rejected_name" to "checkoutStarted")), events.map { it.loggedInfo() })
    }

    @Test
    fun invalidEventNameThrowsWhenConfiguredTo() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport, onInvalidEventName = InvalidEventNameAction.CRASH).tracked()

        telemetry.log(Event.Breadcrumb("checkout_started"))
        assertThrows(IllegalArgumentException::class.java) { telemetry.log(Event.Breadcrumb("checkoutStarted")) }
        telemetry.flushAndWaitBlocking()

        assertEquals(listOf("checkout_started"), transport.sentNames)
    }

    @Test
    fun eventsOverMaxEventSizeAreReplacedWithAnError() {
        val transport = MockTransport()
        val session = testSession()
        val fitting = Event.Breadcrumb("checkout_started", mapOf("note" to "x".repeat(1000)))
        val tooLarge = Event.Breadcrumb("checkout_started", mapOf("note" to "x".repeat(1001)))
        // IDs and timestamps have a fixed length, so any record of `fitting` has this size.
        val builder = RequestBuilder(DavyConfiguration(URI("https://example.com/events")))
        val size = builder.size(EventRecord.from(fitting, System.currentTimeMillis()), session)
        val telemetry = makeDavy(directory(), transport, maxEventSize = size, session = session).tracked()

        telemetry.log(fitting)
        telemetry.log(tooLarge)
        telemetry.log(Event.Error("payment_declined", tooLarge.info))
        // Even the replacement error is too large, so nothing is sent.
        telemetry.log(Event.Breadcrumb("a".repeat(size)))
        telemetry.flushAndWaitBlocking()

        val events = transport.sentEvents
        assertEquals(listOf("breadcrumb", "error", "error"), events.map { it.getString("type") })
        assertEquals(listOf("checkout_started", "davy_event_too_large", "davy_event_too_large"), events.map { it.getString("name") })
        assertEquals(fitting.info, events[0].getJSONObject("info").strings())
        assertEquals(mapOf("rejected_name" to "checkout_started", "size" to "${size + 1}"), events[1].getJSONObject("info").strings())
        // A replaced error keeps its diagnostics, but not the rest of its info.
        val replacedError = events[2].getJSONObject("info").strings()
        assertEquals(setOf("rejected_name", "size"), events[2].loggedInfo()?.keys)
        assertEquals("payment_declined", replacedError["rejected_name"])
        assertEquals(testDiagnostics[Diagnostics.LOW_POWER_MODE], replacedError[Diagnostics.LOW_POWER_MODE])
    }

    @Test
    fun retriedEventsKeepTheirId() {
        val transport = MockTransport().apply { setFailing(true) }
        val telemetry = makeDavy(directory(), transport).tracked()

        telemetry.log(Event.Breadcrumb("event_0"))
        telemetry.flushAndWaitBlocking()
        transport.setFailing(false)
        telemetry.flushAndWaitBlocking()

        val eventIds = transport.requests.map { JSONObject(it.body.decodeToString()).getJSONArray("events").getJSONObject(0).getString("id") }
        assertEquals(2, eventIds.size)
        assertEquals(eventIds[0], eventIds[1])
    }

    @Test
    fun eventsTheServerRejectsAreDiscardedWithoutBlockingTheQueue() {
        for (statusCode in listOf(400, 413, 414, 422, 431)) {
            val transport = MockTransport().apply { respond(statusCode) }
            val telemetry = makeDavy(directory(), transport).tracked()

            telemetry.log(Event.Breadcrumb("rejected"))
            telemetry.flushAndWaitBlocking()
            transport.respond(200)
            telemetry.log(Event.Breadcrumb("accepted"))
            telemetry.flushAndWaitBlocking()

            assertEquals("$statusCode", 2, transport.attempts)
            assertEquals("$statusCode", listOf("accepted"), transport.sentNames)
        }
    }

    @Test
    fun eventsAreKeptAfterOtherErrorResponses() {
        for (statusCode in listOf(401, 403, 404, 408, 429, 500, 503)) {
            val transport = MockTransport().apply { respond(statusCode) }
            val telemetry = makeDavy(directory(), transport).tracked()

            telemetry.log(Event.Breadcrumb("event_0"))
            telemetry.flushAndWaitBlocking()
            transport.respond(200)
            telemetry.flushAndWaitBlocking()

            assertEquals("$statusCode", 2, transport.attempts)
            assertEquals("$statusCode", listOf("event_0"), transport.sentNames)
        }
    }

    @Test
    fun retryAfterPausesSendingUntilItEndsEvenWhenFlushing() {
        val transport = MockTransport().apply { respond(429, mapOf("Retry-After" to "1")) }
        val telemetry = makeDavy(directory(), transport).tracked()

        telemetry.log(Event.Breadcrumb("event_0"))
        telemetry.flushAndWaitBlocking()
        transport.respond(200)
        telemetry.log(Event.Breadcrumb("event_1"))
        telemetry.flushAndWaitBlocking()
        assertEquals(1, transport.attempts)

        assertTrue(waitUntil { transport.sentNames == listOf("event_0", "event_1") })
    }

    @Test
    fun retryAfterPauseSurvivesRelaunch() {
        val directory = directory()
        val throttlingTransport = MockTransport().apply { respond(503, mapOf("Retry-After" to "3600")) }
        val first = makeDavy(directory, throttlingTransport)
        first.log(Event.Breadcrumb("event_0"))
        first.flushAndWaitBlocking()
        first.close()

        val transport = MockTransport()
        val relaunched = makeDavy(directory, transport).tracked()
        relaunched.log(Event.Breadcrumb("event_1"))
        relaunched.flushAndWaitBlocking()

        assertEquals(1, throttlingTransport.attempts)
        assertEquals(0, transport.attempts)
    }

    @Test
    fun batchSizeOfOneSendsEachEventAsItsOwnRequest() {
        val transport = MockTransport()
        val session = testSession("beta")
        val telemetry = makeDavy(
            directory(),
            transport,
            endpoint = "https://example.com/events?key=abc",
            batchSize = 1,
            environment = "beta",
            headers = mapOf("Authorization" to "Bearer token"),
            session = session,
        ).tracked()

        repeat(6) { telemetry.log(Event.Breadcrumb("event_$it")) }
        telemetry.flushAndWaitBlocking()

        val requests = transport.requests
        assertEquals(6, requests.size)
        for (request in requests) {
            assertEquals("https://example.com/events?key=abc", request.url)
            assertEquals("application/json", request.headers["Content-Type"])
            assertEquals("Davy", request.headers["User-Agent"])
            assertEquals("*", request.headers["Accept-Language"])
            assertEquals("Bearer token", request.headers["Authorization"])
            val payload = JSONObject(request.body.decodeToString())
            assertEquals(1, payload.getJSONArray("events").length())
            assertEquals(session, DavyJson.parseSession(payload.getJSONObject("session")))
        }
        assertEquals((0 until 6).map { "event_$it" }, transport.sentNames)
    }

    @Test
    fun measureLogsABreadcrumbWithItsDurationEvenWhenTheBodyThrows() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport).tracked()

        assertEquals(42, telemetry.measure("sync_work") { 42 })
        assertThrows(IOException::class.java) { telemetry.measure("failing_work") { throw IOException() } }
        runSuspending {
            val value = telemetry.measure("async_work") {
                telemetry.flushAndWait()
                "done"
            }
            assertEquals("done", value)
        }
        telemetry.flushAndWaitBlocking()

        val events = transport.sentEvents
        assertEquals(listOf("sync_work", "failing_work", "async_work"), events.map { it.getString("name") })
        assertEquals(listOf("breadcrumb", "breadcrumb", "breadcrumb"), events.map { it.getString("type") })
        for (event in events) {
            assertTrue(event.getJSONObject("info").getString("duration_ms").toLong() >= 0)
        }
    }

    @Test
    fun errorsLoggedFromAThrowableIncludeItsTypeInInfo() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport).tracked()

        telemetry.log(IOException("contains personal data"), "request_failed", mapOf("error_type" to "custom", "stage" to "upload"))
        telemetry.flushAndWaitBlocking()

        val event = transport.sentEvents.single()
        assertEquals("error", event.getString("type"))
        assertEquals("request_failed", event.getString("name"))
        assertEquals(mapOf("error_type" to "IOException", "stage" to "upload"), event.loggedInfo())
    }
}
