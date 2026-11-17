package com.gu.davy

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Ported from the Swift client's debug snapshot tests. */
class DavyDebugTest {
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
    fun debugSnapshotListsUnsentEventsAsPending() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport, info = mapOf("edition" to "uk")).tracked()

        telemetry.log(Event.Breadcrumb("checkout_started"))
        telemetry.log(Event.Error("payment_declined", mapOf("reason" to "card_expired")))
        val snapshot = telemetry.debugSnapshotBlocking()

        assertEquals(listOf("checkout_started", "payment_declined"), snapshot.pending.map { it.name })
        assertEquals(listOf(false, true), snapshot.pending.map { it.isError })
        assertEquals(listOf(null, "card_expired"), snapshot.pending.map { it.info["reason"] })
        assertEquals(listOf(telemetry.sessionId, telemetry.sessionId), snapshot.pending.map { it.sessionId })
        assertEquals(listOf(mapOf("edition" to "uk"), mapOf("edition" to "uk")), snapshot.pending.map { it.sessionInfo })
        assertEquals(Event.Breadcrumb("checkout_started"), snapshot.pending[0].event)
        assertEquals(2, snapshot.pending.map { it.id }.toSet().size)
        assertTrue(snapshot.sent.isEmpty())
        assertNull(snapshot.lastFailure)
        assertEquals(0, transport.attempts)
    }

    @Test
    fun debugSnapshotMovesEventsFromPendingToSentOnceTheServerResponds() {
        val cases = mapOf(
            200 to DavyDebugSnapshot.SentEvent.Outcome.Delivered,
            400 to DavyDebugSnapshot.SentEvent.Outcome.Rejected(400),
        )
        for ((statusCode, outcome) in cases) {
            val transport = MockTransport().apply { respond(statusCode) }
            val telemetry = makeDavy(directory(), transport).tracked()

            telemetry.log(Event.Breadcrumb("checkout_started"))
            telemetry.log(Event.Breadcrumb("checkout_finished"))
            val pending = telemetry.debugSnapshotBlocking().pending
            val start = System.currentTimeMillis()
            telemetry.flushAndWaitBlocking()
            val snapshot = telemetry.debugSnapshotBlocking()

            assertTrue(snapshot.pending.isEmpty())
            assertEquals(pending, snapshot.sent.map { it.event })
            assertEquals(listOf(outcome, outcome), snapshot.sent.map { it.outcome })
            assertTrue(snapshot.sent.all { it.sentAtMillis in start..System.currentTimeMillis() })
        }
    }

    @Test
    fun debugHistoryKeepsOnlyTheMostRecentSentEvents() {
        for ((limit, expectedNames) in listOf(3 to listOf("event_2", "event_3", "event_4"), 0 to emptyList())) {
            val transport = MockTransport()
            val telemetry = makeDavy(directory(), transport, batchSize = 2, debugHistoryLimit = limit).tracked()

            repeat(5) { telemetry.log(Event.Breadcrumb("event_$it")) }
            telemetry.flushAndWaitBlocking()
            assertEquals(expectedNames, telemetry.debugSnapshotBlocking().sent.map { it.event.name })

            telemetry.clearDebugHistory()
            assertTrue(telemetry.debugSnapshotBlocking().sent.isEmpty())
        }
    }

    @Test
    fun debugSnapshotCountsEventsLoggedAndSentEachMinuteEvenWithoutHistory() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport, debugHistoryLimit = 0).tracked()
        val start = DavyDebugSnapshot.Activity.minuteContaining(System.currentTimeMillis())

        telemetry.log(Event.Breadcrumb("checkout_started"))
        telemetry.log(Event.Breadcrumb("checkout_finished"))
        val logged = telemetry.debugSnapshotBlocking().activity
        assertEquals(2, logged.sumOf { it.logged })
        assertEquals(0, logged.sumOf { it.sent })

        telemetry.flushAndWaitBlocking()
        telemetry.clearDebugHistory()
        val sent = telemetry.debugSnapshotBlocking().activity
        assertEquals(2, sent.sumOf { it.logged })
        assertEquals(2, sent.sumOf { it.sent })
        assertTrue(sent.all { it.minuteMillis in start..System.currentTimeMillis() })
    }

    @Test
    fun debugActivityCountsEventsLeftOverFromAnEarlierLaunchAsLogged() {
        val directory = directory()
        val transport = MockTransport().apply { setFailing(true) }
        val earlier = makeDavy(directory, transport)
        earlier.log(Event.Breadcrumb("app_launched"))
        earlier.log(Event.Breadcrumb("content_viewed"))
        assertEquals(2, earlier.debugSnapshotBlocking().pending.size)
        earlier.close()

        val relaunched = makeDavy(directory, transport).tracked()
        relaunched.log(Event.Breadcrumb("app_launched"))
        val snapshot = relaunched.debugSnapshotBlocking()

        assertEquals(3, snapshot.pending.size)
        assertEquals(3, snapshot.activity.sumOf { it.logged })
    }

    @Test
    fun debugCountsEveryLoggedEventAsPendingOrSent() {
        val directory = directory()
        val store = FileEventStore(File(directory, "events"))
        val session = testSession()
        val now = System.currentTimeMillis()
        // Left over from earlier launches: one logged before the last hour, one expired, and one
        // that can't be decoded. Only the first can be sent, so only it counts as logged.
        for ((name, loggedAt) in listOf("earlier" to now - 2.hours.inWholeMilliseconds, "expired" to now - 31.days.inWholeMilliseconds)) {
            store.append(DavyJson.pendingEvent(session, EventRecord.from(Event.Breadcrumb(name), loggedAt)).toByteArray(), loggedAt)
        }
        store.append("""{"not":"an event"}""".toByteArray(), now - 60_000)

        val transport = MockTransport().apply { setFailing(true) }
        val telemetry = makeDavy(directory, transport).tracked()
        telemetry.log(Event.Breadcrumb("checkout_started"))
        telemetry.log(Event.Breadcrumb("checkout_finished"))
        telemetry.flushAndWaitBlocking()
        val failed = telemetry.debugSnapshotBlocking()
        assertEquals(3, failed.loggedCount)
        assertEquals(3, failed.pending.size)
        assertEquals(0, failed.sentCount)

        transport.respond(400)
        telemetry.log(Event.Breadcrumb("checkout_abandoned"))
        telemetry.flushAndWaitBlocking()
        telemetry.clearDebugHistory()
        val sent = telemetry.debugSnapshotBlocking()
        assertEquals(4, sent.loggedCount)
        assertTrue(sent.pending.isEmpty())
        assertEquals(4, sent.sentCount)
    }

    @Test
    fun debugSnapshotRecordsEachRequestInTheOrderTheyFinished() {
        val transport = MockTransport().apply { setFailing(true) }
        val telemetry = makeDavy(directory(), transport, headers = mapOf("Authorization" to "Bearer token")).tracked()

        telemetry.log(Event.Breadcrumb("checkout_started"))
        telemetry.log(Event.Breadcrumb("checkout_finished"))
        val pending = telemetry.debugSnapshotBlocking().pending
        val start = System.currentTimeMillis()
        telemetry.flushAndWaitBlocking()
        transport.setFailing(false)
        telemetry.flushAndWaitBlocking()
        val end = System.currentTimeMillis()
        val requests = telemetry.debugSnapshotBlocking().requests

        assertEquals(
            listOf(DavyDebugSnapshot.Request.Outcome.Failed("HTTP 500"), DavyDebugSnapshot.Request.Outcome.Delivered),
            requests.map { it.outcome },
        )
        assertEquals(listOf(500, 200), requests.map { it.statusCode })
        assertEquals(2, requests.map { it.id }.toSet().size)
        assertEquals(transport.requests.map { it.body.decodeToString() }, requests.map { it.body })
        for (request in requests) {
            assertEquals("POST", request.method)
            assertEquals("https://example.com/events", request.url)
            assertEquals(mapOf("Content-Type" to "application/json", "Authorization" to "Bearer token"), request.headers)
            assertEquals(listOf("checkout_started", "checkout_finished"), JSONObject(request.body).getJSONArray("events").objects().map { it.getString("name") })
            assertEquals(pending.map { it.id }, request.eventIds)
            assertTrue(request.durationMillis >= 0)
            assertTrue(request.sentAtMillis in start..end)
        }
        assertTrue(requests[0].sentAtMillis <= requests[1].sentAtMillis)
    }

    @Test
    fun debugSnapshotRecordsThrottledRejectedAndUnansweredRequests() {
        val offline = IOException("The Internet connection appears to be offline.")
        val cases = listOf(
            Triple(MockTransport().apply { respond(429, mapOf("Retry-After" to "120")) }, 429, DavyDebugSnapshot.Request.Outcome.Throttled(120.0)),
            Triple(MockTransport().apply { respond(400) }, 400, DavyDebugSnapshot.Request.Outcome.Rejected),
            Triple(EventTransport { throw offline }, null, DavyDebugSnapshot.Request.Outcome.Failed(offline.toString())),
        )
        for ((transport, statusCode, outcome) in cases) {
            val telemetry = makeDavy(directory(), transport).tracked()

            telemetry.log(Event.Breadcrumb("checkout_started"))
            telemetry.flushAndWaitBlocking()
            val request = telemetry.debugSnapshotBlocking().requests.single()

            assertEquals("$outcome", statusCode, request.statusCode)
            assertEquals(outcome, request.outcome)
        }
    }

    @Test
    fun debugHistoryKeepsOnlyTheMostRecentRequests() {
        for (limit in listOf(2, 0)) {
            val transport = MockTransport()
            val telemetry = makeDavy(
                directory(),
                transport,
                batchSize = 1,
                debugHistoryLimit = limit,
            ).tracked()

            repeat(3) { telemetry.log(Event.Breadcrumb("event_$it")) }
            telemetry.flushAndWaitBlocking()
            assertEquals(
                transport.requests.takeLast(limit).map { it.body.decodeToString() },
                telemetry.debugSnapshotBlocking().requests.map { it.body },
            )

            telemetry.clearDebugHistory()
            assertTrue(telemetry.debugSnapshotBlocking().requests.isEmpty())
        }
    }

    @Test
    fun debugSnapshotListsWhenEachSessionLoggedItsFirstEvent() {
        val directory = directory()
        val transport = MockTransport().apply { setFailing(true) }
        val earlier = makeDavy(directory, transport)
        earlier.log(Event.Breadcrumb("app_launched"))
        earlier.log(Event.Breadcrumb("content_viewed"))
        val earlierFirstLogged = earlier.debugSnapshotBlocking().pending.first().timestampMillis
        earlier.close()

        val relaunched = makeDavy(directory, transport).tracked()
        assertEquals(listOf(earlier.sessionId), relaunched.debugSnapshotBlocking().sessionStarts.map { it.sessionId })

        relaunched.log(Event.Breadcrumb("app_launched"))
        relaunched.log(Event.Breadcrumb("content_viewed"))
        val snapshot = relaunched.debugSnapshotBlocking()
        val relaunchedFirstLogged = snapshot.pending.first { it.sessionId == relaunched.sessionId }.timestampMillis

        assertEquals(
            mapOf(earlier.sessionId to earlierFirstLogged, relaunched.sessionId to relaunchedFirstLogged),
            snapshot.sessionStarts.associate { it.sessionId to it.dateMillis },
        )
    }

    @Test
    fun failedSendsLeaveEventsPendingAndAreRecordedUntilASendSucceeds() {
        val transport = MockTransport().apply { setFailing(true) }
        val telemetry = makeDavy(directory(), transport).tracked()

        telemetry.log(Event.Breadcrumb("checkout_started"))
        val start = System.currentTimeMillis()
        telemetry.flushAndWaitBlocking()
        val failed = telemetry.debugSnapshotBlocking()

        assertEquals(listOf("checkout_started"), failed.pending.map { it.name })
        assertTrue(failed.sent.isEmpty())
        val failure = failed.lastFailure!!
        assertEquals("HTTP 500", failure.reason)
        assertTrue(failure.dateMillis in start..System.currentTimeMillis())

        transport.setFailing(false)
        telemetry.flushAndWaitBlocking()
        val sent = telemetry.debugSnapshotBlocking()
        assertTrue(sent.pending.isEmpty())
        assertEquals(failed.pending, sent.sent.map { it.event })
        assertNull(sent.lastFailure)
    }

    @Test
    fun debugListenersGetASnapshotNowAndAfterEachChangeUntilClosed() {
        val transport = MockTransport()
        val telemetry = makeDavy(directory(), transport).tracked()
        val snapshots = LinkedBlockingQueue<DavyDebugSnapshot>()
        val listener = telemetry.addDebugListener { snapshots += it }

        assertEquals(DavyDebugSnapshot.EMPTY, snapshots.poll(5, TimeUnit.SECONDS))

        telemetry.log(Event.Breadcrumb("checkout_started"))
        val logged = snapshots.poll(5, TimeUnit.SECONDS)!!
        assertEquals(listOf("checkout_started"), logged.pending.map { it.name })

        telemetry.flushAndWaitBlocking()
        var sent = snapshots.poll(5, TimeUnit.SECONDS)!!
        while (sent.pending.isNotEmpty()) sent = snapshots.poll(5, TimeUnit.SECONDS)!!
        assertEquals(logged.pending, sent.sent.map { it.event })

        listener.close()
        telemetry.log(Event.Breadcrumb("checkout_finished"))
        telemetry.debugSnapshotBlocking()
        assertNull(snapshots.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun debugJsonIsIndentedWithSortedKeys() {
        val session = Session(
            id = java.util.UUID.fromString("6F9619FF-8B86-D011-B42D-00C04FC964FF"),
            environment = null,
            appVersion = "1.2.0",
            build = null,
            os = Session.OS,
            osVersion = "15.0.0",
            deviceModel = null,
            locale = "en_GB",
        )
        val event = EventRecord(
            id = java.util.UUID.fromString("9C5B94B1-35AD-49BB-B118-8E8FC24ABF80"),
            type = EventRecord.Type.ERROR,
            name = "payment_declined",
            info = mapOf("reason" to "card/expired", "attempt" to "2"),
            timestampMillis = DavyJson.parseTimestamp("2026-11-09T11:38:57.112Z"),
        )

        assertEquals(
            """
            {
              "event" : {
                "id" : "9C5B94B1-35AD-49BB-B118-8E8FC24ABF80",
                "info" : {
                  "attempt" : "2",
                  "reason" : "card/expired"
                },
                "name" : "payment_declined",
                "timestamp" : "2026-11-09T11:38:57.112Z",
                "type" : "error"
              },
              "session" : {
                "appVersion" : "1.2.0",
                "id" : "6F9619FF-8B86-D011-B42D-00C04FC964FF",
                "locale" : "en_GB",
                "os" : "Android",
                "osVersion" : "15.0.0"
              }
            }
            """.trimIndent(),
            DavyDebugSnapshot.LoggedEvent(event, session).prettyJson,
        )
    }
}
