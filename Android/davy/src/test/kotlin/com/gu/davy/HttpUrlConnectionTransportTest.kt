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
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class HttpUrlConnectionTransportTest {
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
        val body = """{"session":{},"events":[]}""".toByteArray()

        val response = HttpUrlConnectionTransport().send(
            HttpRequest("POST", "${server.url}/events", mapOf("Content-Type" to "application/json", "Authorization" to "Bearer token"), body),
        )

        assertEquals(429, response.statusCode)
        assertEquals("120", response.header("retry-after"))
        assertEquals("120", response.header("Retry-After"))
        val received = server.received.poll(5, TimeUnit.SECONDS)!!
        assertEquals("POST /events HTTP/1.1", received.requestLine)
        assertEquals("application/json", received.headers["content-type"])
        assertEquals("Bearer token", received.headers["authorization"])
        assertArrayEquals(body, received.body)
    }

    @Test
    fun sendsAQueryStringRequestWithoutABody() {
        val server = server("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
        val path = "/events/checkout_started?eventId=1B4E28BA-2FA1-11D2-883F-0016D3CCA427&info=%7B%22a%22%3A%22b%2Bc%22%7D"

        val outcome = HttpUrlConnectionTransport().deliver(HttpRequest("GET", server.url + path, emptyMap(), null))

        assertEquals(DeliveryOutcome.Delivered, outcome)
        val received = server.received.poll(5, TimeUnit.SECONDS)!!
        // Sent exactly as built, without decoding or re-encoding the query.
        assertEquals("GET $path HTTP/1.1", received.requestLine)
        assertNull(received.headers["content-length"]?.takeIf { it != "0" })
        assertEquals(0, received.body.size)
    }

    @Test
    fun noResponseIsAFailure() {
        val port = ServerSocket(0).use { it.localPort }

        val outcome = HttpUrlConnectionTransport().deliver(HttpRequest("POST", "http://127.0.0.1:$port/events", emptyMap(), ByteArray(0)))

        assertTrue(outcome.toString(), outcome is DeliveryOutcome.Failed)
    }
}
