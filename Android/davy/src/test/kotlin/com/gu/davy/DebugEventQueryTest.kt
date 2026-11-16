package com.gu.davy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** The same cases as the Swift client's `DebugEventFilterTests`. */
class DebugEventQueryTest {
    private val session = testSession()

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
        url: String,
        body: String? = null,
        eventIds: List<UUID> = listOf(UUID.randomUUID()),
        statusCode: Int? = 200,
        outcome: DavyDebugSnapshot.Request.Outcome = DavyDebugSnapshot.Request.Outcome.Delivered,
    ) = DavyDebugSnapshot.Request(UUID.randomUUID(), "POST", url, emptyMap(), body, eventIds, 1_000, 20, statusCode, outcome)

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
        val failed = request("https://example.com/events", eventIds = listOf(pending.id, delivered.id), statusCode = 500)
        val retried = request("https://example.com/events", eventIds = listOf(delivered.id))
        val other = request("https://example.com/events", eventIds = listOf(rejected.id), statusCode = 400)
        val withRequests = snapshot.copy(requests = listOf(failed, retried, other))

        assertEquals(listOf(other, retried, failed), withRequests.requestsNewestFirst())
        assertEquals(listOf(retried, failed), withRequests.requestsContaining(delivered.id))
        assertEquals(emptyList<DavyDebugSnapshot.Request>(), withRequests.requestsContaining(UUID.randomUUID()))
    }

    @Test
    fun requestSearchMatchesTheDecodedUrlOrBodyIgnoringCase() {
        val queryString = request("https://example.com/events/checkout_started?info=%7B%22step%22%3A%22Caf%C3%A9+1%22%7D")
        val batch = request("https://example.com/events", body = """{"events":[{"name":"payment_declined"}]}""")
        fun matching(searchText: String) = listOf(queryString, batch).filter { it.matches(searchText) }

        assertEquals(listOf(queryString, batch), matching("  "))
        assertEquals(listOf(queryString), matching(" \"STEP\":\"café+1\" "))
        assertEquals(listOf(batch), matching("Payment_Declined"))
        assertEquals(listOf(queryString, batch), matching("example.com/events"))
        assertEquals(emptyList<DavyDebugSnapshot.Request>(), matching("refund"))
    }

    @Test
    fun requestsAreDescribedByPathStatusEventsAndOutcome() {
        val unanswered = request("https://example.com/v1/events?key=a%26b&empty=", body = "{}", statusCode = null)
        assertEquals("POST /v1/events", unanswered.methodAndPath)
        assertEquals("No response", unanswered.statusTitle)
        assertEquals("1 event", unanswered.eventCountTitle)
        assertEquals(listOf("key" to "a&b", "empty" to ""), unanswered.queryParameters)

        val delivered = request("https://example.com", eventIds = List(3) { UUID.randomUUID() })
        assertEquals("POST /", delivered.methodAndPath)
        assertEquals("HTTP 200", delivered.statusTitle)
        assertEquals("3 events", delivered.eventCountTitle)
        assertEquals(emptyList<Pair<String, String>>(), delivered.queryParameters)

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
            request("https://example.com/events", body = body).prettyBody,
        )
        assertEquals("not json", request("https://example.com/events", body = "not json").prettyBody)
        assertEquals("{} {}", request("https://example.com/events", body = "{} {}").prettyBody)
        assertNull(request("https://example.com/events").prettyBody)
    }
}
