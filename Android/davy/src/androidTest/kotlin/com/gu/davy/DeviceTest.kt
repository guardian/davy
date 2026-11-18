package com.gu.davy

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Checks what only a real device can: the data Davy reads from Android, and sending over its HTTP stack. */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    // The patterns from Spec/openapi.yaml.
    private val osVersionPattern = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")
    private val localePattern = Regex("^[a-z]{2,3}(_([A-Z]{2}|[0-9]{3}))?$")

    @Test
    fun sessionsDescribeTheDeviceInTheSpecsFormat() {
        val session = Session.start(context, "test")

        assertEquals("Android", session.os)
        assertEquals("test", session.environment)
        assertTrue(session.osVersion, osVersionPattern.matches(session.osVersion))
        assertEquals(Session.osVersion(Build.VERSION.RELEASE), session.osVersion)
        assertEquals(Build.MODEL, session.deviceModel)
        val locale = session.locale
        assertTrue("$locale", locale != null && localePattern.matches(locale))
        assertTrue(Session.start(context, "test").id != session.id)
    }

    @Test
    fun errorsCarryTheAppAndDeviceState() {
        val diagnostics = Diagnostics(context)
        Thread.sleep(100)
        val info = diagnostics.capture()

        assertTrue("$info", Diagnostics.KEYS.containsAll(info.keys))
        assertTrue("$info", (info.getValue(Diagnostics.CPU_PERCENT) as Long) >= 0)
        assertTrue("$info", (info.getValue(Diagnostics.MEMORY_USED_MB) as Long) > 0)
        assertTrue("$info", (info.getValue(Diagnostics.MEMORY_AVAILABLE_MB) as Long) > 0)
        assertTrue("$info", info[Diagnostics.LOW_POWER_MODE] is Boolean)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val states = setOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown")
            assertTrue("$info", info[Diagnostics.THERMAL_STATE] in states)
        }
    }

    @Test
    fun eventsAreSentOverHttp() {
        val server = ServerSocket(0)
        val requests = LinkedBlockingQueue<Pair<String, ByteArray>>()
        thread(isDaemon = true) {
            server.accept().use { connection ->
                val input = BufferedInputStream(connection.getInputStream())
                val requestLine = input.readLine()
                val headers = generateSequence { input.readLine().takeIf(String::isNotEmpty) }
                    .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                var read = 0
                while (read < body.size) read += input.read(body, read, body.size - read)
                connection.getOutputStream().write("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n".toByteArray())
                requests += requestLine to body
            }
        }
        val directory = File(context.cacheDir, "DeviceTest-${UUID.randomUUID()}")
        val telemetry = Davy(
            context,
            DavyConfiguration(
                endpoint = URI("http://127.0.0.1:${server.localPort}/events"),
                environment = "test",
                storageDirectory = directory,
                allowsInsecureHttp = true,
            ),
        )

        try {
            telemetry.log(Event.Breadcrumb("checkout_started"))
            telemetry.log(Event.Error("payment_declined", mapOf("reason" to "card_expired")))
            val flushed = CountDownLatch(1)
            telemetry.flush { flushed.countDown() }
            assertTrue(flushed.await(10, TimeUnit.SECONDS))

            val (requestLine, body) = requests.poll(10, TimeUnit.SECONDS)!!
            assertEquals("POST /events HTTP/1.1", requestLine)
            val payload = JSONObject(body.decodeToString())
            assertEquals(DavyJson.uuid(telemetry.sessionId), payload.getJSONObject("session").getString("id"))
            assertEquals("Android", payload.getJSONObject("session").getString("os"))
            val events = payload.getJSONArray("events")
            assertEquals(listOf("checkout_started", "payment_declined"), (0 until events.length()).map { events.getJSONObject(it).getString("name") })
            val errorInfo = events.getJSONObject(1).getJSONObject("info")
            assertEquals("card_expired", errorInfo.getString("reason"))
            assertTrue(errorInfo.has(Diagnostics.MEMORY_USED_MB))
            // Delivered events are removed, so nothing is left to send.
            assertEquals(0, File(directory, "events").list()?.size)
        } finally {
            telemetry.close()
            server.close()
            directory.deleteRecursively()
        }
    }

    private fun InputStream.readLine(): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = read()
            if (byte == -1 || byte == '\n'.code) break
            if (byte != '\r'.code) line.write(byte)
        }
        return line.toString(Charsets.UTF_8.name())
    }
}
