package com.gu.davy

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpCookie
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class OkHttpTransportTest {
    /** A request as the server received it. */
    private class Received(val requestLine: String, val headers: Map<String, String>, val body: ByteArray)

    /** Answers each connection with [response], then records the request it received. */
    private class Server(private val response: String) : AutoCloseable {
        private val socket = ServerSocket(0)
        val received = LinkedBlockingQueue<Received>()
        val url = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val connection = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    connection.use {
                        val input = BufferedInputStream(it.getInputStream())
                        val requestLine = input.readLine()
                        val headers = generateSequence { input.readLine().takeIf(String::isNotEmpty) }
                            .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        var read = 0
                        while (read < body.size) read += input.read(body, read, body.size - read)
                        it.getOutputStream().apply {
                            write(response.toByteArray())
                            flush()
                        }
                        received += Received(requestLine, headers, body)
                    }
                }
            }
        }

        override fun close() = socket.close()

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

    private val servers = mutableListOf<Server>()

    private fun server(response: String) = Server(response).also { servers += it }

    @After
    fun tearDown() = servers.forEach(Server::close)

    @Test
    fun sendsTheMethodHeadersAndBodyAndReadsTheResponse() {
        val server = server("HTTP/1.1 429 Too Many Requests\r\nRetry-After: 120\r\nContent-Length: 4\r\nConnection: close\r\n\r\nslow")
        val session = Session(java.util.UUID.randomUUID(), null, null, null, Session.OS, "15.0.0", null, null)
        val event = EventRecord.from(Event.Breadcrumb("checkout_started"), timestampMillis = 1_700_000_000_000)
        val request = RequestBuilder(
            DavyConfiguration(
                endpoint = java.net.URI("${server.url}/events"),
                headers = mapOf(
                    "X-Api-Key" to "secret",
                    "user-agent" to "Custom/1.0",
                    "accept-language" to "en-GB",
                    "content-type" to "text/plain",
                    "cOoKiE" to "session=secret",
                ),
            ),
        ).request(session, listOf(event), sentAtMillis = 1_700_000_005_000)

        val response = OkHttpTransport(allowsInsecureHttp = true).send(request)

        assertEquals(429, response.statusCode)
        assertEquals("120", response.header("retry-after"))
        assertEquals("120", response.header("Retry-After"))
        val received = server.received.poll(5, TimeUnit.SECONDS)!!
        assertEquals("POST /events HTTP/1.1", received.requestLine)
        assertEquals("application/json", received.headers["content-type"])
        assertEquals("Davy", received.headers["user-agent"])
        assertEquals("*", received.headers["accept-language"])
        assertEquals("secret", received.headers["x-api-key"])
        assertNull(received.headers["cookie"])
        assertArrayEquals(request.body, received.body)
    }

    @Test
    fun noResponseIsAFailure() {
        val port = ServerSocket(0).use { it.localPort }

        val result = OkHttpTransport(allowsInsecureHttp = true).deliver(HttpRequest("http://127.0.0.1:$port/events", emptyMap(), ByteArray(0)))

        assertNull(result.statusCode)
        assertTrue(result.outcome.toString(), result.outcome is DeliveryOutcome.Failed)
    }

    @Test
    fun refusesCleartextAndDoesNotFollowRedirectsOrUseAppCookies() {
        val other = server("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
        val origin = server(
            "HTTP/1.1 307 Temporary Redirect\r\nLocation: ${other.url}/other\r\n" +
                "Set-Cookie: tracking=server; Path=/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
        )
        val endpoint = URI("${origin.url}/events")
        val request = HttpRequest(endpoint.toString(), emptyMap(), "event".toByteArray())
        val cookies = CookieManager()
        cookies.cookieStore.add(endpoint, HttpCookie("session", "host").apply { path = "/" })
        val previous = CookieHandler.getDefault()
        try {
            CookieHandler.setDefault(cookies)
            org.junit.Assert.assertThrows(IOException::class.java) { OkHttpTransport().send(request) }
            assertNull(origin.received.poll())

            val transport = OkHttpTransport(allowsInsecureHttp = true)
            repeat(2) {
                assertEquals(307, transport.send(request).statusCode)
                assertNull(origin.received.poll(5, TimeUnit.SECONDS)!!.headers["cookie"])
            }
            assertNull(other.received.poll())
            assertEquals(listOf("session"), cookies.cookieStore.cookies.map { it.name })
        } finally {
            CookieHandler.setDefault(previous)
        }
    }
}
