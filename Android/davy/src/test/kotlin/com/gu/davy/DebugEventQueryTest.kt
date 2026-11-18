package com.gu.davy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/** The same cases as the Swift client's `DebugEventFilterTests`. */
class DebugEventQueryTest {
    private val session = testSession(info = mapOf("edition" to "Australia"))

    private fun loggedEvent(event: Event, timestampMillis: Long, id: UUID = UUID.randomUUID()) =
        DavyDebugSnapshot.LoggedEvent(EventRecord.from(event, timestampMillis, id), session)

    private val pending = loggedEvent(Event.Breadcrumb("checkout_started", mapOf("step" to "Cart")), 1_000)
    private val delivered = loggedEvent(
        Event.Error("payment_declined", mapOf("reason" to "card_expired")),
        2_000,
        UUID.fromString("9C5B94B1-35AD-49BB-B118-8E8FC24ABF80"),
    )
    private val rejected = loggedEvent(Event.Breadcrumb("bad_event"), 3_000)

    private val snapshot = DavyDebugSnapshot(
        // An event in a request that's finished is listed once, as sent.
        pending = listOf(pending, delivered),
        sent = listOf(
            DavyDebugSnapshot.SentEvent(delivered, 5_000, DavyDebugSnapshot.SentEvent.Outcome.Delivered),
            DavyDebugSnapshot.SentEvent(rejected, 6_000, DavyDebugSnapshot.SentEvent.Outcome.Rejected(400)),
        ),
        requests = emptyList(),
        loggedCount = 3,
        sentCount = 2,
        lastFailure = null,
        activity = emptyList(),
        sessionStarts = emptyList(),
    )

    private fun names(filter: DebugEventFilter = DebugEventFilter.ALL, searchText: String = "") =
        DebugEventItem.all(snapshot).filter { filter.includes(it) && it.matches(searchText) }.map { it.event.name }

    private fun request(
        body: String = "{}",
        eventIds: List<UUID> = listOf(UUID.randomUUID()),
        statusCode: Int? = 200,
        outcome: DavyDebugSnapshot.Request.Outcome = DavyDebugSnapshot.Request.Outcome.Delivered,
    ) = DavyDebugSnapshot.Request(UUID.randomUUID(), emptyMap(), body, eventIds, 1_000, 20, statusCode, outcome)

    @Test
    fun eventsAreListedOnceMostRecentlyLoggedFirst() {
        assertEquals(listOf("bad_event", "payment_declined", "checkout_started"), names())
    }

    @Test
    fun eventsLoggedInTheSameMillisecondAreListedMostRecentlyLoggedFirst() {
        val first = loggedEvent(Event.Breadcrumb("first"), 1_000)
        val second = loggedEvent(Event.Breadcrumb("second"), 1_000)
        val sameTime = DavyDebugSnapshot(listOf(first, second), emptyList(), emptyList(), 2, 0, null, emptyList(), emptyList())

        assertEquals(listOf("second", "first"), DebugEventItem.all(sameTime).map { it.event.name })
    }

    @Test
    fun filtersShowEventsByStatusOrType() {
        assertEquals(listOf("checkout_started"), names(DebugEventFilter.PENDING))
        assertEquals(listOf("payment_declined"), names(DebugEventFilter.SENT))
        assertEquals(listOf("bad_event"), names(DebugEventFilter.REJECTED))
        assertEquals(listOf("payment_declined"), names(DebugEventFilter.ERRORS))
    }

    @Test
    fun searchMatchesNamesInfoAndIdsIgnoringCase() {
        assertEquals(names(), names(searchText = "  "))
        assertEquals(listOf("checkout_started"), names(searchText = "CHECKOUT"))
        assertEquals(listOf("checkout_started"), names(searchText = "cart"))
        assertEquals(listOf("payment_declined"), names(searchText = "Reason"))
        assertEquals(listOf("payment_declined"), names(searchText = " 9c5b94b1 "))
        assertEquals(names(), names(searchText = "EDITION"))
        assertEquals(names(), names(searchText = "australia"))
        assertEquals(emptyList<String>(), names(searchText = "refund"))
        assertEquals(emptyList<String>(), names(DebugEventFilter.PENDING, searchText = "payment"))
    }

    @Test
    fun emptyResultsExplainWhatNarrowedThem() {
        assertEquals("No events match the filter.", DebugEventFilter.SENT.noMatchesDescription(" "))
        assertEquals("No events match the search.", DebugEventFilter.ALL.noMatchesDescription("refund"))
        assertEquals("No events match the search and filter.", DebugEventFilter.SENT.noMatchesDescription("refund"))
    }

    @Test
    fun requestsAreListedMostRecentlyFinishedFirstAndFoundByTheirEvents() {
        val failed = request(eventIds = listOf(pending.id, delivered.id), statusCode = 500)
        val retried = request(eventIds = listOf(delivered.id))
        val other = request(eventIds = listOf(rejected.id), statusCode = 400)
        val withRequests = snapshot.copy(requests = listOf(failed, retried, other))

        assertEquals(listOf(other, retried, failed), withRequests.requestsNewestFirst())
        assertEquals(listOf(retried, failed), withRequests.requestsContaining(delivered.id))
        assertEquals(emptyList<DavyDebugSnapshot.Request>(), withRequests.requestsContaining(UUID.randomUUID()))
    }

    @Test
    fun requestSearchMatchesTheBodyIgnoringCase() {
        val first = request(body = """{"events":[{"name":"checkout_started","info":{"step":"Cart"}}]}""")
        val second = request(body = """{"events":[{"name":"payment_declined"}]}""")
        fun matching(searchText: String) = listOf(first, second).filter { it.matches(searchText) }

        assertEquals(listOf(first, second), matching("  "))
        assertEquals(listOf(first), matching(" \"cart\" "))
        assertEquals(listOf(second), matching("Payment_Declined"))
        assertEquals(emptyList<DavyDebugSnapshot.Request>(), matching("refund"))
    }

    @Test
    fun requestsAreDescribedByStatusEventsAndOutcome() {
        val unanswered = request(body = "{}", statusCode = null)
        assertEquals("No response", unanswered.statusTitle)
        assertEquals("1 event", unanswered.eventCountTitle)

        val delivered = request(eventIds = List(3) { UUID.randomUUID() })
        assertEquals("HTTP 200", delivered.statusTitle)
        assertEquals("3 events", delivered.eventCountTitle)

        assertEquals(
            listOf("Delivered", "Rejected", "Throttled: retry after 120 s", "Failed"),
            listOf(
                DavyDebugSnapshot.Request.Outcome.Delivered,
                DavyDebugSnapshot.Request.Outcome.Rejected,
                DavyDebugSnapshot.Request.Outcome.Throttled(120.0),
                DavyDebugSnapshot.Request.Outcome.Failed("HTTP 500"),
            ).map { it.description },
        )
    }

    @Test
    fun requestBodiesAreIndentedWithSortedKeysWhenTheyAreJson() {
        val body = """{"session":{"os":"Android","id":"6F9619FF"},"events":[{"name":"a\"b","info":{}},[]],"count":2,"valid":true,"note":null}"""

        assertEquals(
            """
            {
              "count" : 2,
              "events" : [
                {
                  "info" : {},
                  "name" : "a\"b"
                },
                []
              ],
              "note" : null,
              "session" : {
                "id" : "6F9619FF",
                "os" : "Android"
              },
              "valid" : true
            }
            """.trimIndent(),
            request(body = body).prettyBody,
        )
        assertEquals("not json", request(body = "not json").prettyBody)
        assertEquals("{} {}", request(body = "{} {}").prettyBody)
    }
}
