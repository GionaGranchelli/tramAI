package dev.tramai.core.provider.transport

import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalProviderTransportApi::class)
class BoundedBodyReadTest {
    private open class CloseTrackingStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeResponse(
        private val bodyStream: InputStream,
    ) : HttpResponse<InputStream> {
        override fun statusCode(): Int = 200

        override fun body(): InputStream = bodyStream

        override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }

        override fun uri(): URI = URI.create("https://example.invalid")

        override fun request(): HttpRequest = throw UnsupportedOperationException()

        override fun version(): HttpClient.Version? = HttpClient.Version.HTTP_1_1

        override fun previousResponse(): Optional<HttpResponse<InputStream>> = Optional.empty()

        override fun sslSession(): Optional<SSLSession> = Optional.empty()
    }

    @Test
    fun `readBoundedBody reads small body fully`() {
        val result = readBoundedBody(ByteArrayInputStream("hello".toByteArray()), 10)

        assertEquals("hello", result.text)
        assertTrue(!result.truncated)
    }

    @Test
    fun `readBoundedBody truncates oversized body`() {
        val result = readBoundedBody(ByteArrayInputStream("0123456789".toByteArray()), 4)

        assertEquals("0123", result.text)
        assertTrue(result.text.toByteArray().size <= 4)
        assertTrue(result.truncated)
    }

    @Test
    fun `readBoundedBody closes stream`() {
        val stream = CloseTrackingStream("body".toByteArray())

        readBoundedBody(stream, 10)

        assertTrue(stream.closed)
    }

    @Test
    fun `readBoundedBodyBytes returns exact bytes within limit`() {
        val bytes = byteArrayOf(0, 1, 127, -1)

        assertTrue(readBoundedBodyBytes(ByteArrayInputStream(bytes), bytes.size).contentEquals(bytes))
    }

    @Test
    fun `readBoundedBodyBytes throws with limit when source exceeds it`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                readBoundedBodyBytes(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 2)
            }

        assertTrue(error.message.orEmpty().contains("2"))
    }

    @Test
    fun `readBoundedResponseBody reads response body`() {
        val result = readBoundedResponseBody(FakeResponse(ByteArrayInputStream("{}".toByteArray())), 10)

        assertEquals("{}", result.text)
        assertTrue(!result.truncated)
    }

    /**
     * Epic 12.1c probe 4 — HTTP response/stream always closed. The transport
     * contract closes the body stream on EVERY exit (input.use): prove the
     * failure, cancellation, overflow, and response-level paths with an
     * instrumented close-observable stream. SSE/streaming-path closure is
     * covered by ProviderSseTest (caller use block closes on completion and on
     * exception). No FD counting, no sleeps — observable close() only.
     */
    @Test
    fun `readBoundedBody closes the stream when reading fails`() {
        val stream =
            object : CloseTrackingStream("partial".toByteArray()) {
                private var reads = 0

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (reads++ == 0) {
                        return super.read(b, off, 1)
                    }
                    throw IOException("mid-read failure")
                }
            }

        val error =
            assertFailsWith<IOException> {
                readBoundedBody(stream, 10)
            }

        assertTrue(stream.closed)
        assertEquals("mid-read failure", error.message)
    }

    @Test
    fun `readBoundedBody closes the stream on cancellation`() {
        val stream =
            object : CloseTrackingStream("never-read".toByteArray()) {
                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = throw CancellationException("cancelled mid-read")
            }

        val error =
            assertFailsWith<CancellationException> {
                readBoundedBody(stream, 10)
            }

        assertTrue(stream.closed)
        assertEquals("cancelled mid-read", error.message)
    }

    @Test
    fun `readBoundedBody closes the stream when the body exceeds the limit`() {
        val stream = CloseTrackingStream("0123456789".toByteArray())

        val result = readBoundedBody(stream, 4)

        assertTrue(result.truncated)
        assertTrue(stream.closed)
    }

    @Test
    fun `readBoundedResponseBody closes the response body stream`() {
        val stream = CloseTrackingStream("{}".toByteArray())

        readBoundedResponseBody(FakeResponse(stream), 10)

        assertTrue(stream.closed)
    }

    @Test
    fun `readBoundedResponseBody closes the response body stream when reading fails`() {
        val stream =
            object : CloseTrackingStream("{}".toByteArray()) {
                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = throw IOException("response read failure")
            }

        assertFailsWith<IOException> {
            readBoundedResponseBody(FakeResponse(stream), 10)
        }

        assertTrue(stream.closed)
    }
}
