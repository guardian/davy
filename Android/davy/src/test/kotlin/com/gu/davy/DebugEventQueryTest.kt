package com.gu.davy

import org.junit.Assert.assertEquals
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
        lastFailure = null,
        activity = emptyList(),
        sessionStarts = emptyList(),
    )

    private fun names(filter: DebugEventFilter = DebugEventFilter.ALL, searchText: String = "") =
        DebugEventItem.all(snapshot).filter { filter.includes(it) && it.matches(searchText) }.map { it.event.name }

    @Test
    fun eventsAreListedOnceMostRecentlyLoggedFirst() {
        assertEquals(listOf("bad_event", "payment_declined", "checkout_started"), names())
    }

    @Test
    fun eventsLoggedInTheSameMillisecondAreListedMostRecentlyLoggedFirst() {
        val first = loggedEvent(Event.Breadcrumb("first"), 1_000)
        val second = loggedEvent(Event.Breadcrumb("second"), 1_000)
        val sameTime = DavyDebugSnapshot(listOf(first, second), emptyList(), null, emptyList(), emptyList())

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
}
