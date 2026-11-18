package com.gu.davy

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

/** Uses its own HTTP client rather than the host application's process-wide cookies or credentials. */
internal class OkHttpTransport(
    private val allowsInsecureHttp: Boolean = false,
) : EventTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val builder = Request.Builder().url(request.url)
        for ((field, value) in request.headers) {
            builder.header(field, value)
        }
        val post = builder.post(request.body.toRequestBody()).build()
        if (!post.url.isHttps && !allowsInsecureHttp) {
            throw IOException("The telemetry endpoint must use HTTPS")
        }
        return client.newCall(post).execute().use { response ->
            HttpResponse(response.code, response.headers.names().associateWith { response.headers[it].orEmpty() })
        }
    }

    private companion object {
        val client = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
