package com.gu.davy

import java.util.UUID

/** A logged [Event] as it is sent in a request. */
internal data class EventRecord(
    /**
     * A random identifier generated when the event is logged and sent unchanged with every attempt,
     * so the server can discard copies of an event that was delivered more than once.
     */
    val id: UUID,
    val type: Type,
    val name: String,
    /** Additional details. `null` when empty, so the field is omitted. */
    val info: Map<String, String>?,
    /** When the event was logged, in milliseconds since 1970. */
    val timestampMillis: Long,
) {
    enum class Type(val wireName: String) {
        BREADCRUMB("breadcrumb"),
        ERROR("error"),
    }

    companion object {
        fun from(event: Event, timestampMillis: Long, id: UUID = UUID.randomUUID()): EventRecord = EventRecord(
            id = id,
            type = when (event) {
                is Event.Breadcrumb -> Type.BREADCRUMB
                is Event.Error -> Type.ERROR
            },
            name = event.name,
            info = event.info.takeIf { it.isNotEmpty() },
            timestampMillis = timestampMillis,
        )
    }
}
