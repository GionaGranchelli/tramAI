package dev.tramai.core.provider.transport

import java.io.ByteArrayInputStream
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
    private class CloseTrackingStream(
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
}
