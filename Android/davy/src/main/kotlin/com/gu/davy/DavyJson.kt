package com.gu.davy

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * The fixed JSON encoding used for requests and storage, so every client sends the same shape.
 *
 * Written by hand rather than with a library to avoid an extra dependency, and so the output
 * is exactly what the spec describes. Reading uses Android's built-in `org.json`.
 */
internal object DavyJson {
    private const val TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

    /** ISO 8601 in UTC with milliseconds, for example `2026-11-09T11:38:55.780Z`. */
    fun timestamp(millis: Long): String = timestampFormat().format(Date(millis))

    /** Reads a [timestamp], throwing a [ParseException] if it isn't one. */
    fun parseTimestamp(string: String): Long = timestampFormat().parse(string)?.time ?: throw ParseException(string, 0)

    // SimpleDateFormat isn't thread-safe, and java.time needs API 26, so make one each time.
    private fun timestampFormat() = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    /** UUIDs in uppercase, as Apple platforms write them. */
    fun uuid(id: UUID): String = id.toString().uppercase(Locale.ROOT)

    /** The JSON batch request body: `{ "sentAt": "…", "session": { … }, "events": [ … ] }`. */
    fun payload(session: Session, events: List<EventRecord>, sentAtMillis: Long): String = buildString {
        append("{\"sentAt\":")
        appendString(timestamp(sentAtMillis))
        append(",\"session\":")
        appendSession(session)
        append(",\"events\":[")
        events.forEachIndexed { index, event ->
            if (index > 0) append(',')
            appendEvent(event)
        }
        append("]}")
    }

    /**
     * An event as stored on disk, with the session it was logged in, so events left over from a
     * previous launch are still sent with their original session and only to their original destination.
     */
    fun pendingEvent(session: Session, event: EventRecord, destination: String): String = buildString {
        append("{\"session\":")
        appendSession(session)
        append(",\"event\":")
        appendEvent(event)
        append(",\"destination\":")
        appendString(destination)
        append('}')
    }

    /** Reads a [pendingEvent], or returns `null` if [json] isn't one. */
    fun parsePendingEvent(json: String): Triple<Session, EventRecord, String?>? = try {
        val pending = JSONObject(json)
        Triple(parseSession(pending.getJSONObject("session")), parseEvent(pending.getJSONObject("event")), pending.stringOrNull("destination"))
    } catch (_: JSONException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ParseException) {
        null
    }

    /** Reads a session in the form [payload] writes it. */
    fun parseSession(json: JSONObject): Session = Session(
        id = UUID.fromString(json.getString("id")),
        environment = json.stringOrNull("environment"),
        appVersion = json.stringOrNull("appVersion"),
        build = json.stringOrNull("build"),
        os = json.getString("os"),
        osVersion = json.getString("osVersion"),
        deviceModel = json.stringOrNull("deviceModel"),
        locale = json.stringOrNull("locale"),
        info = json.optJSONObject("info")?.let(::parseInfo)?.takeIf { it.isNotEmpty() },
    )

    /** Reads an event in the form [payload] writes it. */
    fun parseEvent(json: JSONObject): EventRecord = EventRecord(
        id = UUID.fromString(json.getString("id")),
        type = json.getString("type").let { type ->
            EventRecord.Type.entries.firstOrNull { it.wireName == type } ?: throw IllegalArgumentException("Unknown event type $type")
        },
        name = json.getString("name"),
        info = json.optJSONObject("info")?.let(::parseInfo),
        timestampMillis = parseTimestamp(json.getString("timestamp")),
    )

    /** Reads `info`, including values stored as strings by earlier versions. */
    private fun parseInfo(json: JSONObject): Map<String, Any> = buildMap {
        for (key in json.keys()) {
            InfoValues.normalize(json.get(key))?.let { put(key, it) }
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private fun StringBuilder.appendSession(session: Session) {
        appendObject(
            linkedMapOf(
                "id" to uuid(session.id),
                "environment" to session.environment,
                "appVersion" to session.appVersion,
                "build" to session.build,
                "os" to session.os,
                "osVersion" to session.osVersion,
                "deviceModel" to session.deviceModel,
                "locale" to session.locale,
                "info" to session.info?.takeIf { it.isNotEmpty() }?.toSortedMap(),
            ),
        )
    }

    private fun StringBuilder.appendEvent(event: EventRecord) {
        appendObject(
            linkedMapOf(
                "id" to uuid(event.id),
                "type" to event.type.wireName,
                "name" to event.name,
                "info" to event.info?.takeIf { it.isNotEmpty() }?.toSortedMap(),
                "timestamp" to timestamp(event.timestampMillis),
            ),
        )
    }

    /**
     * An object of strings, numbers, Booleans, and nested maps of them, omitting `null` values.
     * Numbers must be finite, as [InfoValues.normalize] ensures for `info`.
     */
    private fun StringBuilder.appendObject(fields: Map<String, Any?>) {
        append('{')
        var first = true
        for ((key, value) in fields) {
            if (value == null) continue
            if (!first) append(',')
            first = false
            appendString(key)
            append(':')
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    appendObject(value as Map<String, Any?>)
                }
                is Boolean, is Long, is Double -> append(value.toString())
                else -> appendString(value.toString())
            }
        }
        append('}')
    }

    /**
     * [json], an object or array, indented with sorted keys for showing
     * request bodies in [DavyDebugActivity]. `null` if it isn't valid JSON.
     */
    fun prettyJson(json: String): String? {
        val tokener = JSONTokener(json)
        val value = try {
            tokener.nextValue().takeIf { tokener.nextClean() == '\u0000' }
        } catch (_: JSONException) {
            null
        }
        if (value !is JSONObject && value !is JSONArray) return null
        return buildString { appendPrettyJson(value, indent = "") }
    }

    private fun StringBuilder.appendPrettyJson(value: Any?, indent: String) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys().asSequence().sorted().toList()
                if (keys.isEmpty()) {
                    append("{}")
                    return
                }
                append('{')
                keys.forEachIndexed { index, key ->
                    append(if (index == 0) "\n" else ",\n").append(indent).append("  ")
                    appendString(key)
                    append(" : ")
                    appendPrettyJson(value.opt(key), "$indent  ")
                }
                append('\n').append(indent).append('}')
            }
            is JSONArray -> {
                if (value.length() == 0) {
                    append("[]")
                    return
                }
                append('[')
                for (index in 0 until value.length()) {
                    append(if (index == 0) "\n" else ",\n").append(indent).append("  ")
                    appendPrettyJson(value.opt(index), "$indent  ")
                }
                append('\n').append(indent).append(']')
            }
            is String -> appendString(value)
            null, JSONObject.NULL -> append("null")
            else -> append(value.toString())
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                in '\u0000'..'\u001F' -> append(String.format(Locale.ROOT, "\\u%04x", char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
