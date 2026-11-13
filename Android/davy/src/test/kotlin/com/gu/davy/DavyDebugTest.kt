package com.gu.davy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
        val telemetry = makeDavy(directory(), transport).tracked()

        telemetry.log(Event.Breadcrumb("checkout_started"))
        telemetry.log(Event.Error("payment_declined", mapOf("reason" to "card_expired")))
        val snapshot = telemetry.debugSnapshotBlocking()

        assertEquals(listOf("checkout_started", "payment_declined"), snapshot.pending.map { it.name })
        assertEquals(listOf(false, true), snapshot.pending.map { it.isError })
        assertEquals(listOf(null, "card_expired"), snapshot.pending.map { it.info["reason"] })
        assertEquals(listOf(telemetry.sessionId, telemetry.sessionId), snapshot.pending.map { it.sessionId })
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
