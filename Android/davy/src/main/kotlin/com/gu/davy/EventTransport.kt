package com.gu.davy

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A response's status code and header fields. */
internal class HttpResponse(
    val statusCode: Int,
    /** Header fields with lowercase names. */
    headers: Map<String, String>,
) {
    private val headers = headers.mapKeys { it.key.lowercase() }

    /** The value of the header field [name], ignoring its case. */
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * Sends a request and waits for the response. Throwing means no HTTP response was received, for
 * example because the device is offline; the events are kept and retried.
 */
internal fun interface EventTransport {
    @Throws(IOException::class)
    fun send(request: HttpRequest): HttpResponse
}

/** What sending a request produced: the response's status code, if one was received, and what it means for the events. */
internal class DeliveryResult(val statusCode: Int?, val outcome: DeliveryOutcome)

/** Sends [request] and classifies the result. */
internal fun EventTransport.deliver(request: HttpRequest): DeliveryResult = try {
    val response = send(request)
    DeliveryResult(response.statusCode, DeliveryOutcome.of(response.statusCode, response::header))
} catch (exception: Exception) {
    DeliveryResult(null, DeliveryOutcome.Failed(exception.toString()))
}

/**
 * Sends requests with [HttpURLConnection], never answering them from a cache.
 *
 * Cookies are sent and stored only if the app installs a default `java.net.CookieHandler`, which
 * apps don't by default.
 */
internal class HttpUrlConnectionTransport(private val timeoutMillis: Int = 60_000) : EventTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.useCaches = false
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            for ((field, value) in request.headers) {
                connection.setRequestProperty(field, value)
            }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val statusCode = connection.responseCode
            // The body is ignored, but reading it lets the connection be reused.
            val stream = if (statusCode >= 400) connection.errorStream else connection.inputStream
            stream?.use { it.readBytes() }
            val headers = connection.headerFields.orEmpty()
                .filterKeys { it != null }
                .mapNotNull { (field, values) -> values.lastOrNull()?.let { field to it } }
                .toMap()
            return HttpResponse(statusCode, headers)
        } finally {
            connection.disconnect()
        }
    }
}
