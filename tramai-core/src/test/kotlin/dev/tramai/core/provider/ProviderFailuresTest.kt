package dev.tramai.core.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import org.assertj.core.api.Assertions.assertThat
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

class ProviderFailuresTest {

    private val secretFixture = "sk-secret-222 /path/customer/alice SELECT * FROM users bearer-token-xyz"

    @Test
    fun `applyTramaiTimeout copies request timeout onto http request`() {
        val request = ModelRequest(
            model = "gpt-5.1-chat-latest",
            messages = listOf(Message(MessageRole.USER, "hello")),
            timeoutMillis = 1_500,
        )

        val httpRequest = HttpRequest.newBuilder(URI("https://example.com"))
            .applyTramaiTimeout(request)
            .build()

        assertThat(httpRequest.timeout()).hasValueSatisfying { timeout ->
            assertThat(timeout.toMillis()).isEqualTo(1_500)
        }
    }

    @Test
    fun `provider http failure marks transient statuses as retryable`() {
        val error = providerHttpFailure(
            providerId = "openai",
            statusCode = 429,
            body = """{"error":"rate limited"}""",
        )

        assertThat(error.statusCode).isEqualTo(429)
        assertThat(error.retryable).isTrue()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
        assertThat(error.message).isEqualTo("Provider request failed with HTTP 429")
    }

    @Test
    fun `provider http failure captures retry after hints`() {
        val error = providerHttpFailure(
            providerId = "openai",
            statusCode = 429,
            body = """{"error":"rate limited"}""",
            retryAfterHeader = "2",
        )

        assertThat(error.retryAfterMillis).isEqualTo(2_000)
    }

    @Test
    fun `provider http failure marks permanent statuses as non retryable`() {
        val error = providerHttpFailure(
            providerId = "openai",
            statusCode = 401,
            body = """{"error":"unauthorized"}""",
        )

        assertThat(error.statusCode).isEqualTo(401)
        assertThat(error.retryable).isFalse()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
    }

    @Test
    fun `provider http failure never exposes the response body in the public exception`() {
        val error = providerHttpFailure(
            providerId = "openai",
            statusCode = 500,
            body = """{"error":"$secretFixture"}""",
        )

        assertThat(error.message).isEqualTo("Provider request failed with HTTP 500")
        assertThat(error.message).doesNotContain(secretFixture)
        assertThat(error.cause).isNull()
    }

    @Test
    fun `provider http failure delivers a bounded body preview to the diagnostic observer`() {
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val observer = ProviderFailureDiagnosticObserver { events.add(it) }
        val oversized = "x".repeat(PROVIDER_ERROR_BODY_LIMIT_BYTES + 100)

        providerHttpFailure(
            providerId = "openai",
            statusCode = 429,
            body = oversized,
            observer = observer,
        )

        val event = events.single()
        assertThat(event.code).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
        assertThat(event.providerId).isEqualTo("openai")
        assertThat(event.statusCode).isEqualTo(429)
        assertThat(event.retryable).isTrue()
        assertThat(event.httpBodyPreview).isNotNull()
        assertThat(event.httpBodyPreview!!.length).isLessThanOrEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES)
        assertThat(event.httpBodyPreviewTruncated).isTrue()
        assertThat(event.failure).isNull()
    }

    @Test
    fun `provider http failure preserves a caller-supplied truncation flag`() {
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val observer = ProviderFailureDiagnosticObserver { events.add(it) }

        providerHttpFailure(
            providerId = "openai",
            statusCode = 429,
            body = "small preview",
            bodyTruncated = true,
            observer = observer,
        )

        val event = events.single()
        assertThat(event.httpBodyPreviewTruncated).isTrue()
    }

    @Test
    fun `bounded body preview keeps bodies at the limit untruncated`() {
        val exact = "y".repeat(PROVIDER_ERROR_BODY_LIMIT_BYTES)

        val preview = boundedBodyPreview(exact)

        assertThat(preview.text).isEqualTo(exact)
        assertThat(preview.truncated).isFalse()
    }

    @Test
    fun `bounded lines preview stops at the limit and closes the stream`() {
        val lines = Stream.of("a".repeat(PROVIDER_ERROR_BODY_LIMIT_BYTES), "b", "c")

        val preview = boundedLinesPreview(lines)

        assertThat(preview.truncated).isTrue()
        assertThat(preview.text.length).isLessThanOrEqualTo(PROVIDER_ERROR_BODY_LIMIT_BYTES)
        assertThat(preview.text).doesNotContain("b")
    }

    @Test
    fun `bounded lines preview keeps a full body under the limit`() {
        val preview = boundedLinesPreview(Stream.of("ok", "body"))

        assertThat(preview.truncated).isFalse()
        assertThat(preview.text).isEqualTo("ok\nbody")
    }

    @Test
    fun `provider transport failure marks timeout as retryable with fixed message and no cause`() {
        val error = providerTransportFailure(
            providerId = "ollama",
            error = HttpTimeoutException("connection timed out at $secretFixture"),
        )

        assertThat(error.retryable).isTrue()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.TIMEOUT)
        assertThat(error.message).isEqualTo("Provider request timed out")
        assertThat(error.message).doesNotContain(secretFixture)
        assertThat(error.cause).isNull()
    }

    @Test
    fun `provider transport failure marks connection failures as retryable`() {
        val error = providerTransportFailure(
            providerId = "anthropic",
            error = ConnectException("connection refused"),
        )

        assertThat(error.retryable).isTrue()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.CONNECTION_FAILED)
        assertThat(error.message).isEqualTo("Provider connection failed")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `provider transport failure marks io failures as retryable`() {
        val error = providerTransportFailure(
            providerId = "openai",
            error = IOException("socket closed"),
        )

        assertThat(error.retryable).isTrue()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.TRANSPORT_FAILED)
        assertThat(error.message).isEqualTo("Provider transport failed")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `provider transport failure leaves unexpected failures non retryable`() {
        val error = providerTransportFailure(
            providerId = "openai",
            error = IllegalStateException("boom"),
        )

        assertThat(error.retryable).isFalse()
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.UNEXPECTED_FAILURE)
        assertThat(error.message).isEqualTo("Provider request failed")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `provider transport failure never exposes throwable message and retains no cause`() {
        val error = providerTransportFailure(
            providerId = "openai",
            error = IOException("connection to $secretFixture refused"),
        )

        assertThat(error.message).doesNotContain(secretFixture)
        assertThat(error.cause).isNull()
    }

    @Test
    fun `provider transport failure delivers the original throwable to the diagnostic observer`() {
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val observer = ProviderFailureDiagnosticObserver { events.add(it) }
        val original = IOException("raw $secretFixture")

        providerTransportFailure(
            providerId = "openai",
            error = original,
            observer = observer,
        )

        val event = events.single()
        assertThat(event.failure).isSameAs(original)
        assertThat(event.code).isEqualTo(ProviderFailureCode.TRANSPORT_FAILED)
        assertThat(event.providerId).isEqualTo("openai")
        assertThat(event.statusCode).isNull()
        assertThat(event.httpBodyPreview).isNull()
        assertThat(event.httpBodyPreviewTruncated).isFalse()
    }

    @Test
    fun `provider transport failure passes existing provider exceptions through unchanged`() {
        val original = ProviderException(
            message = "custom trusted message",
            cause = IllegalStateException("cause text"),
            statusCode = 418,
            retryable = false,
        )

        val result = providerTransportFailure("openai", original)

        assertThat(result).isSameAs(original)
    }

    @Test
    fun `cancellation is rethrown without diagnostics or a provider failure`() {
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val observer = ProviderFailureDiagnosticObserver { events.add(it) }

        assertFailsWith<CancellationException> {
            providerTransportFailure("openai", CancellationException("parent cancelled"), observer = observer)
        }

        assertThat(events).isEmpty()
    }

    @Test
    fun `observer failure is fail-open and never replaces the provider failure`() {
        val error = providerTransportFailure(
            providerId = "openai",
            error = IOException("boom"),
            observer = ProviderFailureDiagnosticObserver { throw IllegalStateException("observer bug") },
        )

        assertThat(error.message).isEqualTo("Provider transport failed")
        assertThat(error.cause).isNull()
    }

    @Test
    fun `observer thrown cancellation is swallowed while the coroutine is active`() {
        val error = providerTransportFailure(
            providerId = "openai",
            error = IOException("boom"),
            observer = ProviderFailureDiagnosticObserver { throw CancellationException("observer bug") },
        )

        assertThat(error.message).isEqualTo("Provider transport failed")
        assertThat(error.cause).isNull()
    }
}
