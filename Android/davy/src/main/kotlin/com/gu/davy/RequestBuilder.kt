package com.gu.davy

import java.net.URI

/** A `POST` request ready to be sent by an [EventTransport]. */
internal class HttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/** Builds the HTTP request that delivers events to the configured endpoint. */
internal class RequestBuilder(configuration: DavyConfiguration) {
    val endpoint: URI = configuration.endpoint
    private val headers: Map<String, String> = configuration.headers

    /**
     * Builds a `POST` request delivering [events], all logged in [session], stamped with
     * [sentAtMillis], the device's time when it's sent.
     */
    fun request(session: Session, events: List<EventRecord>, sentAtMillis: Long): HttpRequest = HttpRequest(
        url = endpoint.toString(),
        headers = buildMap {
            for ((field, value) in headers) {
                if (!field.equals("Content-Type", ignoreCase = true) &&
                    !field.equals("User-Agent", ignoreCase = true) &&
                    !field.equals("Accept-Language", ignoreCase = true)
                ) {
                    put(field, value)
                }
            }
            put("Content-Type", "application/json")
            put("User-Agent", "Davy")
            put("Accept-Language", "*")
        },
        body = DavyJson.payload(session, events, sentAtMillis).toByteArray(Charsets.UTF_8),
    )

    /**
     * The size of [event] in bytes: the UTF-8 length of a single-event JSON request body with
     * [session]. `sentAt` is always 24 characters, so any millisecond value will do.
     */
    fun size(event: EventRecord, session: Session): Int =
        DavyJson.payload(session, listOf(event), sentAtMillis = 0L).toByteArray(Charsets.UTF_8).size
}
