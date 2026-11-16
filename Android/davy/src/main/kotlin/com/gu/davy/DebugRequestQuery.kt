package com.gu.davy

import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.util.UUID
import kotlin.math.ceil

/** The requests in the debug list, most recently finished first. */
internal fun DavyDebugSnapshot.requestsNewestFirst(): List<DavyDebugSnapshot.Request> = requests.asReversed()

/** The requests that included the event with [eventId], most recently finished first. */
internal fun DavyDebugSnapshot.requestsContaining(eventId: UUID): List<DavyDebugSnapshot.Request> =
    requestsNewestFirst().filter { eventId in it.eventIds }

/**
 * Whether the request's percent-decoded URL or its body contains [searchText], ignoring case and
 * surrounding spaces. Empty text matches every request.
 */
internal fun DavyDebugSnapshot.Request.matches(searchText: String): Boolean {
    val text = searchText.trim()
    if (text.isEmpty()) return true
    return percentDecoded(url).contains(text, ignoreCase = true) || body?.contains(text, ignoreCase = true) == true
}

/** The method and the URL's path, for example `POST /events`. */
internal val DavyDebugSnapshot.Request.methodAndPath: String
    get() {
        val path = try {
            URI(url).path
        } catch (_: URISyntaxException) {
            null
        }
        return "$method ${path?.takeIf { it.isNotEmpty() } ?: "/"}"
    }

/** The response's status code, for example `HTTP 200`, or `No response`. */
internal val DavyDebugSnapshot.Request.statusTitle: String
    get() = statusCode?.let { "HTTP $it" } ?: "No response"

/** How many events the request carried, for example `1 event` or `3 events`. */
internal val DavyDebugSnapshot.Request.eventCountTitle: String
    get() = if (eventIds.size == 1) "1 event" else "${eventIds.size} events"

/** The URL's query parameters, percent-decoded, in order. Empty if the URL has no query. */
internal val DavyDebugSnapshot.Request.queryParameters: List<Pair<String, String>>
    get() = url.substringBefore('#').substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map { item ->
        percentDecoded(item.substringBefore('=')) to percentDecoded(item.substringAfter('=', ""))
    }

/** The body indented with sorted keys if it's JSON, otherwise as is. */
internal val DavyDebugSnapshot.Request.prettyBody: String?
    get() = body?.let { DavyJson.prettyJson(it) ?: it }

/** What the outcome meant, for example `Delivered` or `Throttled: retry after 120 s`. A failure's reason is shown separately. */
internal val DavyDebugSnapshot.Request.Outcome.description: String
    get() = when (this) {
        DavyDebugSnapshot.Request.Outcome.Delivered -> "Delivered"
        DavyDebugSnapshot.Request.Outcome.Rejected -> "Rejected"
        is DavyDebugSnapshot.Request.Outcome.Throttled -> "Throttled: retry after ${ceil(retryAfterSeconds).toLong()} s"
        is DavyDebugSnapshot.Request.Outcome.Failed -> "Failed"
    }

/** [value] with percent-encoded bytes decoded as UTF-8, or unchanged if they're malformed. A `+` stays a `+`. */
private fun percentDecoded(value: String): String = try {
    URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
} catch (_: IllegalArgumentException) {
    value
}
