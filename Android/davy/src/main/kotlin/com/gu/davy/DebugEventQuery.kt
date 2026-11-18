package com.gu.davy

/** Where an event is in its journey to the server. */
internal sealed class DebugEventStatus {
    data object Pending : DebugEventStatus()

    data class Sent(val atMillis: Long, val outcome: DavyDebugSnapshot.SentEvent.Outcome) : DebugEventStatus()

    /** Neither pending nor in the sent history, for example because it expired or the history was cleared. */
    data object Unknown : DebugEventStatus()
}

/** An event in the debug list, pending or sent. */
internal class DebugEventItem(val event: DavyDebugSnapshot.LoggedEvent, val status: DebugEventStatus) {
    /**
     * Whether the event's name, an `info` or `sessionInfo` key or value, or its ID contains
     * [searchText], ignoring case and surrounding spaces. Empty text matches every event.
     */
    fun matches(searchText: String): Boolean {
        val text = searchText.trim()
        if (text.isEmpty()) return true
        val fields = listOf(event.name, DavyJson.uuid(event.id)) +
            event.info.flatMap { listOf(it.key, it.value.toString()) } +
            event.sessionInfo.flatMap { listOf(it.key, it.value.toString()) }
        return fields.any { it.contains(text, ignoreCase = true) }
    }

    companion object {
        /** Pending and sent events together, most recently logged first. */
        fun all(snapshot: DavyDebugSnapshot): List<DebugEventItem> {
            val sent = snapshot.sent.map { DebugEventItem(it.event, DebugEventStatus.Sent(it.sentAtMillis, it.outcome)) }
            val sentIds = sent.map { it.event.id }.toSet()
            val pending = snapshot.pending.filter { it.id !in sentIds }.map { DebugEventItem(it, DebugEventStatus.Pending) }
            // Both lists are oldest first, so reversing them before a stable sort lists events
            // logged in the same millisecond most recently logged first too.
            return (sent + pending).asReversed().sortedByDescending { it.event.timestampMillis }
        }
    }
}

/** Which events the debug list shows. */
internal enum class DebugEventFilter(val title: String) {
    ALL("All"),
    PENDING("Pending"),
    SENT("Sent"),
    REJECTED("Rejected"),
    ERRORS("Errors"),
    ;

    /** Why no events are listed when this filter and [searchText] match none. */
    fun noMatchesDescription(searchText: String): String = when {
        searchText.isBlank() -> "No events match the filter."
        this == ALL -> "No events match the search."
        else -> "No events match the search and filter."
    }

    fun includes(item: DebugEventItem): Boolean {
        val outcome = (item.status as? DebugEventStatus.Sent)?.outcome
        return when (this) {
            ALL -> true
            PENDING -> item.status == DebugEventStatus.Pending
            SENT -> outcome == DavyDebugSnapshot.SentEvent.Outcome.Delivered
            REJECTED -> outcome is DavyDebugSnapshot.SentEvent.Outcome.Rejected
            ERRORS -> item.event.isError
        }
    }
}
