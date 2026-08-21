package dev.tramai.core.provider.transport

import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import dev.tramai.core.provider.PROVIDER_ERROR_BODY_LIMIT_BYTES
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProviderHttpResponseTest {

    private class CloseTrackingStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class ThrowingStream : InputStream() {
        override fun read(): Int = throw IOException("body read failed")
    }

    private class FakeResponse<S : InputStream>(
        private val status: Int,
        val bodyStream: S,
        private val retryAfter: String? = null,
    ) : HttpResponse<InputStream> {
        override fun statusCode(): Int = status
        override fun body(): InputStream = bodyStream
        override fun headers(): HttpHeaders = HttpHeaders.of(
            mapOf("Retry-After" to listOfNotNull(retryAfter)),
        ) { _: String, _: String -> true }

        override fun uri(): URI = URI.create("https://example.invalid/chat/completions")
        override fun request(): HttpRequest = throw UnsupportedOperationException()
        override fun version(): HttpClient.Version? = HttpClient.Version.HTTP_1_1
        override fun previousResponse(): Optional<HttpResponse<InputStream>>? = Optional.empty()
        override fun sslSession(): Optional<SSLSession> = Optional.empty()
    }

    private fun fake(status: Int, body: String, retryAfter: String? = null): FakeResponse<CloseTrackingStream> =
        FakeResponse(status, CloseTrackingStream(body.toByteArray()), retryAfter)

    private val events = mutableListOf<ProviderFailureDiagnosticEvent>()
    private val observer = ProviderFailureDiagnosticObserver(events::add)

    @Test
    fun `rejected response maps to http rejected failure with retry after`() = runBlocking<Unit> {
        val response = fake(429, "{\"error\":\"rate limited\"}", retryAfter = "5")

        val error = rejectedProviderHttpResponse("openai", "customer-openai", response, observer)

        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
        assertThat(error.statusCode).isEqualTo(429)
        assertThat(error.retryable).isTrue()
        assertThat(error.retryAfterMillis).isEqualTo(5_000)
        assertThat(error.message).isEqualTo("Provider request failed with HTTP 429")
        assertThat(error.message).doesNotContain("rate limited")
        assertThat(events.single().providerAlias).isEqualTo("customer-openai")
        assertThat(events.single().httpBodyPreview).isEqualTo("{\"error\":\"rate limited\"}")
    }

    @Test
    fun `rejected response body closes on failure`() = runBlocking<Unit> {
        val response = fake(500, "boom")

        rejectedProviderHttpResponse("openai", null, response, observer)

        assertThat(response.bodyStream.closed).isTrue()
    }

    @Test
    fun `permanent status stays non retryable`() = runBlocking<Unit> {
        val error = rejectedProviderHttpResponse("openai", null, fake(401, "unauthorized"), observer)

        assertThat(error.retryable).isFalse()
        assertThat(error.retryAfterMillis).isNull()
    }

    @Test
    fun `body preview is bounded at the diagnostic limit`() = runBlocking<Unit> {
        val oversized = "x".repeat(PROVIDER_ERROR_BODY_LIMIT_BYTES + 1_000)

        val error = rejectedProviderHttpResponse("openai", null, fake(500, oversized), observer)

        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
        val event = events.single()
        assertThat(event.httpBodyPreview!!.toByteArray()).hasSizeLessThanOrEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES)
        assertThat(event.httpBodyPreviewTruncated).isTrue()
    }

    @Test
    fun `body read failure becomes a transport failure`() = runBlocking<Unit> {
        val response = FakeResponse(500, ThrowingStream())

        val error = rejectedProviderHttpResponse("openai", null, response, observer)

        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.TRANSPORT_FAILED)
        assertThat(error.retryable).isTrue()
        assertThat(events.single().failure).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `observer failure stays fail open`() = runBlocking<Unit> {
        val throwingObserver = ProviderFailureDiagnosticObserver {
            throw IllegalStateException("observer exploded")
        }

        val error = rejectedProviderHttpResponse("openai", null, fake(429, "x"), throwingObserver)

        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
    }

    @Test
    fun `observer thrown cancellation is swallowed while the coroutine is active`() = runBlocking<Unit> {
        // Fail-open diagnostic semantics: an observer-thrown CancellationException
        // must not replace the provider failure while the enclosing coroutine is
        // still active. (Cancellation rethrow of a genuinely cancelled coroutine
        // is exercised end-to-end by the provider TCK stream-cancellation test.)
        val observer = ProviderFailureDiagnosticObserver { throw CancellationException("observer") }

        val error = rejectedProviderHttpResponse("openai", null, fake(429, "x"), observer)

        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
    }

    @Test
    fun `rejected handling does not classify status itself`() = runBlocking<Unit> {
        // The adapter checks 2xx before invoking the rejected handler; the
        // handler must pass the status through verbatim without deciding
        // whether it is an error.
        val error = rejectedProviderHttpResponse("openai", null, fake(200, "{}"), observer)

        assertThat(error.statusCode).isEqualTo(200)
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
    }
}
