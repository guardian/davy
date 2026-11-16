package com.gu.davy

import java.net.URI

/** An HTTP request ready to send. */
internal class HttpRequest(
    val method: String,
    val url: String,
    /** Header fields, with names as given. */
    val headers: Map<String, String>,
    /** The body, or `null` for none. */
    val body: ByteArray?,
)

/** Builds the HTTP request that delivers events in the configured [RequestFormat]. */
internal class RequestBuilder(
    private val endpoint: URI,
    private val headers: Map<String, String>,
    private val format: RequestFormat,
) {
    constructor(configuration: DavyConfiguration) : this(
        configuration.endpoint,
        configuration.headers,
        configuration.format,
    )

    /**
     * Builds a request delivering [events], all logged in [session], stamped with [sentAtMillis], the
     * device's time when it's sent. A [RequestFormat.JsonEvent] or [RequestFormat.QueryString]
     * request carries exactly one event.
     */
    fun request(session: Session, events: List<EventRecord>, sentAtMillis: Long): HttpRequest {
        val fields = linkedMapOf<String, String>()
        val (method, url, body) = when (format) {
            RequestFormat.JsonBatch, is RequestFormat.JsonEvent -> {
                if (format is RequestFormat.JsonEvent) {
                    require(events.size == 1) { "A JSON event request carries exactly one event, not ${events.size}" }
                }
                fields["Content-Type"] = "application/json"
                Triple("POST", endpoint.toString(), DavyJson.payload(session, events, sentAtMillis).toByteArray())
            }
            is RequestFormat.QueryString -> {
                require(events.size == 1) { "A query string request carries exactly one event, not ${events.size}" }
                Triple(format.method.name, queryStringUrl(session, events.single(), sentAtMillis), null)
            }
        }
        for ((field, value) in headers) {
            // Header names are case-insensitive, so a configured header replaces a default one.
            fields.keys.removeAll { it.equals(field, ignoreCase = true) }
            fields[field] = value
        }
        return HttpRequest(method, url, fields, body)
    }

    /**
     * The size of [event] in bytes: the length of the URL it's sent to with
     * [RequestFormat.QueryString], whatever the configured format. `sentAt` is always the same
     * length, so any time will do.
     */
    fun size(event: EventRecord, session: Session): Int =
        queryStringUrl(session, event, System.currentTimeMillis()).toByteArray().size

    /**
     * `<endpoint>/<name>?eventId=…&type=…&timestamp=…&sentAt=…&sessionId=…`, keeping any query items
     * already in the endpoint. Parameters whose value is `null` are omitted.
     */
    private fun queryStringUrl(session: Session, event: EventRecord, sentAtMillis: Long): String {
        val path = endpoint.rawPath.orEmpty().let { if (it.endsWith("/")) it else "$it/" } + percentEncoded(event.name)
        val parameters = listOf(
            "eventId" to DavyJson.uuid(event.id),
            "type" to event.type.wireName,
            "timestamp" to DavyJson.timestamp(event.timestampMillis),
            "sentAt" to DavyJson.timestamp(sentAtMillis),
            "sessionId" to DavyJson.uuid(session.id),
            "environment" to session.environment,
            "appVersion" to session.appVersion,
            "build" to session.build,
            "os" to session.os,
            "osVersion" to session.osVersion,
            "deviceModel" to session.deviceModel,
            "locale" to session.locale,
            "info" to event.info?.let(DavyJson::info),
        )
        val query = listOfNotNull(endpoint.rawQuery?.takeIf { it.isNotEmpty() }) +
            parameters.mapNotNull { (name, value) -> value?.let { "$name=${percentEncoded(it)}" } }

        return buildString {
            endpoint.scheme?.let { append(it).append(':') }
            endpoint.rawAuthority?.let { append("//").append(it) }
            append(path)
            append('?').append(query.joinToString("&"))
            endpoint.rawFragment?.let { append('#').append(it) }
        }
    }

    companion object {
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /**
         * [value] with everything except `A–Z a–z 0–9 - . _ ~` percent-encoded as UTF-8, including
         * `+`, `&`, `=`, and `/`, so every value decodes the same way on every server.
         */
        fun percentEncoded(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (char in UNRESERVED) {
                    append(char)
                } else {
                    append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
                }
            }
        }

        private const val HEX = "0123456789ABCDEF"
    }
}
