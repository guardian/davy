package com.gu.davy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID

/**
 * Checks that the client builds exactly the requests described by `Spec/openapi.yaml` and
 * `Spec/fixtures.json`, which the Swift client is tested against too, and handles the responses it
 * describes as it says.
 */
class SpecConformanceTest {
    private val spec = OpenApiSpec.load()

    /** The example endpoint used by the spec's server and paths. */
    private val endpoint = URI("https://api.example.com/events")

    private fun requests(session: Session, events: List<EventRecord>): List<HttpRequest> {
        val builder = RequestBuilder(DavyConfiguration(endpoint))
        val sentAt = System.currentTimeMillis()
        return listOf(builder.request(session, events, sentAt))
    }

    private fun assertConforms(requests: List<HttpRequest>) {
        val violations = requests.flatMap { request -> spec.violations(request).map { "POST ${request.url}: $it" } }
        assertTrue(violations.joinToString("\n"), violations.isEmpty())
    }

    @Test
    fun everyKindOfEventIsSentAsTheSpecDescribes() {
        val sessions = listOf(
            Session(
                id = UUID.randomUUID(),
                environment = "beta",
                appVersion = "1.2.0",
                build = 45,
                os = Session.OS,
                osVersion = Session.osVersion("15"),
                deviceModel = "Pixel 9",
                locale = Session.locale(Locale.Builder().setLanguage("es").setRegion("419").build()),
                info = mapOf("edition" to "uk", "tier" to "supporter", "beta" to true, "articles_read" to 17),
            ),
            Session(
                id = UUID.randomUUID(),
                environment = null,
                appVersion = null,
                build = null,
                os = Session.OS,
                osVersion = Session.osVersion("UpsideDownCake"),
                deviceModel = null,
                locale = Session.locale(Locale.ROOT),
            ),
        )

        for (session in sessions) {
            val directory = temporaryDirectory()
            val transport = MockTransport()
            val telemetry = makeDavy(directory, transport, endpoint = endpoint.toString(), session = session)

            telemetry.log(Event.Breadcrumb("checkout_started"))
            telemetry.log(Event.Breadcrumb("payment_selected", mapOf("payment_method" to "google_pay", "path" to "a/b+c &=?#", "note" to "café ☕️", "attempt" to 2, "ratio" to 0.25, "cached" to true)))
            // Sent as is, since `onInvalidEventName` defaults to `SEND`.
            telemetry.log(Event.Breadcrumb("Checkout Started/Now"))
            telemetry.log(Event.Error("payment_declined", mapOf("reason" to "card_expired")))
            telemetry.log(java.io.IOException(), "checkout_failed")
            telemetry.measure("feed_load") {}
            // Replaced with a "davy_event_too_large" error.
            telemetry.log(Event.Breadcrumb("large_event", mapOf("blob" to "x".repeat(5000))))
            telemetry.flushAndWaitBlocking()
            telemetry.close()
            directory.deleteRecursively()

            val requests = transport.requests
            val sentEvents = requests.sumOf { request -> JSONObject(request.body.decodeToString()).getJSONArray("events").length() }
            assertEquals(7, sentEvents)
            assertConforms(requests)
        }
    }

    @Test
    fun aWholeSecondIsStillSentWithMilliseconds() {
        val session = Session(UUID.randomUUID(), null, null, null, Session.OS, "15.0.0", null, null)
        val event = EventRecord.from(Event.Breadcrumb("checkout_started"), timestampMillis = 1_700_000_000_000)
        assertConforms(requests(session, listOf(event)))
    }

    @Test
    fun requestsMatchTheSharedFixturesExactly() {
        val fixtures = JSONObject(File(OpenApiSpec.directory, "fixtures.json").readText()).getJSONArray("cases")
        assertTrue(fixtures.length() > 0)

        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val name = fixture.getString("name")
            val session = DavyJson.parseSession(fixture.getJSONObject("session"))
            val events = fixture.getJSONArray("events").objects().map(DavyJson::parseEvent)
            val builder = RequestBuilder(
                DavyConfiguration(
                    endpoint = URI(fixture.getString("endpoint")),
                    headers = fixture.getJSONObject("headers").strings(),
                ),
            )
            val request = builder.request(session, events, DavyJson.parseTimestamp(fixture.getString("sent_at")))
            val expected = fixture.getJSONObject("expected")

            assertEquals(name, "POST", expected.getString("method"))
            assertEquals(name, expected.getString("url"), request.url)
            assertEquals(
                name,
                expected.getJSONObject("headers").strings().mapKeys { it.key.lowercase() },
                request.headers.mapKeys { it.key.lowercase() },
            )
            assertEquals(name, expected.opt("body")?.let(Json::of), Json.parse(request.body.decodeToString()))
            if (events.size == 1) {
                assertEquals(name, request.body.size, builder.size(events.single(), session))
            }
        }
    }

    @Test
    fun sendTimesUseTheEventTimestampFormatWithoutReplacingEventTimes() {
        val timestamp = "2023-11-14T22:13:20.000Z"
        val event = EventRecord.from(Event.Breadcrumb("checkout_started"), timestampMillis = DavyJson.parseTimestamp(timestamp))
        val session = Session(UUID.randomUUID(), null, null, null, Session.OS, "15.0.0", null, null)

        val builder = RequestBuilder(DavyConfiguration(endpoint))
        for (sentAt in listOf("2023-11-14T22:13:25.000Z", "2023-11-14T22:13:25.007Z")) {
            val request = builder.request(session, listOf(event), DavyJson.parseTimestamp(sentAt))
            val payload = JSONObject(request.body.decodeToString())
            assertEquals(sentAt, payload.getString("sent_at"))
            assertTrue(!payload.has("sentAt"))
            assertEquals(timestamp, payload.getJSONArray("events").getJSONObject(0).getString("timestamp"))
            assertConforms(listOf(request))
        }
    }

    @Test
    fun everyStatusCodeIsHandledAsTheSpecDescribes() {
        assertEquals(1, spec.operations.size)
        val mismatches = mutableListOf<String>()

        for ((name, operation) in spec.operations) {
            for (statusCode in 100 until 600) {
                val response = spec.responseName(statusCode, operation) ?: "nothing"
                val outcome = DeliveryOutcome.of(statusCode, { if (it.equals("Retry-After", ignoreCase = true)) "120" else null })
                val outcomeWithoutRetryAfter = DeliveryOutcome.of(statusCode, { null })
                val isAsDescribed = when (response) {
                    "Stored" -> outcome is DeliveryOutcome.Delivered && outcomeWithoutRetryAfter is DeliveryOutcome.Delivered
                    "Rejected" -> outcome is DeliveryOutcome.Rejected && outcomeWithoutRetryAfter is DeliveryOutcome.Rejected
                    // Without a valid `Retry-After`, events are retried with backoff.
                    "Throttled" -> outcome is DeliveryOutcome.Throttled && outcomeWithoutRetryAfter is DeliveryOutcome.Failed
                    "Retried" -> outcome is DeliveryOutcome.Failed && outcomeWithoutRetryAfter is DeliveryOutcome.Failed
                    else -> false
                }
                if (!isAsDescribed) mismatches += "$name $statusCode: the spec says $response, but the outcome is $outcome"
            }
        }
        assertTrue(mismatches.joinToString("\n"), mismatches.isEmpty())
    }

    @Test
    fun requestsThatBreakTheSpecAreCaught() {
        val session = Session(UUID.randomUUID(), "beta", "1.2.0", 45, Session.OS, "15.0.0", "Pixel 9", "en_GB")
        val event = EventRecord.from(Event.Error("payment_declined", mapOf("reason" to "card_expired")), System.currentTimeMillis())
        val batch = requests(session, listOf(event)).single()
        assertConforms(listOf(batch))

        val breakages = mapOf(
            "lowercase event ID" to changingBody(batch) {
                val first = it.getJSONArray("events").getJSONObject(0)
                first.put("id", first.getString("id").lowercase())
            },
            "timestamp without milliseconds" to changingBody(batch) {
                it.getJSONArray("events").getJSONObject(0).put("timestamp", "2026-11-09T11:38:55Z")
            },
            "unknown OS" to changingBody(batch) { it.getJSONObject("session").put("os", "Windows") },
            "empty info" to changingBody(batch) { it.getJSONArray("events").getJSONObject(0).put("info", JSONObject()) },
            "empty name" to changingBody(batch) { it.getJSONArray("events").getJSONObject(0).put("name", "") },
            "missing session ID" to changingBody(batch) { it.getJSONObject("session").remove("id") },
            "extra session field" to changingBody(batch) { it.getJSONObject("session").put("deviceId", "123") },
            "object in info" to changingBody(batch) {
                it.getJSONArray("events").getJSONObject(0).getJSONObject("info").put("payment", JSONObject(mapOf("method" to "google_pay")))
            },
            "no events" to changingBody(batch) { it.put("events", org.json.JSONArray()) },
            "missing sent_at in body" to changingBody(batch) { it.remove("sent_at") },
            "sent_at without milliseconds" to changingBody(batch) { it.put("sent_at", "2026-11-09T11:39:00Z") },
            "query string on batch endpoint" to HttpRequest("${batch.url}?key=123", batch.headers, batch.body),
            "wrong User-Agent" to HttpRequest(batch.url, batch.headers + ("User-Agent" to "Dalvik/2.1.0"), batch.body),
            "wrong Accept-Language" to HttpRequest(batch.url, batch.headers + ("Accept-Language" to "en-GB"), batch.body),
        )
        val missed = breakages.filter { (_, request) -> spec.violations(request).isEmpty() }.keys
        assertTrue("Not caught: $missed", missed.isEmpty())
    }

    /** [request] with its JSON body changed by [change]. */
    @Test
    fun sessionsStoredWithAStringBuildStillDecode() {
        fun decode(build: String) = DavyJson.parseSession(
            JSONObject("""{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","build":$build,"os":"Android","osVersion":"15.0.0"}"""),
        )
        assertEquals(45L, decode("45").build)
        assertEquals(45L, decode("\"45\"").build)
    }

    @Test
    fun sessionsStoredWithCamelCaseKeysStillDecode() {
        val session = DavyJson.parseSession(
            JSONObject("""{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","appVersion":"1.2.0","os":"Android","osVersion":"15.0.0","deviceModel":"Pixel 9"}"""),
        )
        assertEquals("1.2.0", session.appVersion)
        assertEquals("15.0.0", session.osVersion)
        assertEquals("Pixel 9", session.deviceModel)
    }

    private fun changingBody(request: HttpRequest, change: (JSONObject) -> Unit): HttpRequest {
        val payload = JSONObject(request.body.decodeToString()).also(change)
        return HttpRequest(request.url, request.headers, payload.toString().toByteArray())
    }
}
