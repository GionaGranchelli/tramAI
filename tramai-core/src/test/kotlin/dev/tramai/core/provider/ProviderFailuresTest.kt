package dev.tramai.core.provider

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import org.assertj.core.api.Assertions.assertThat
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import kotlin.test.Test

class ProviderFailuresTest {

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
            providerName = "openai",
            statusCode = 429,
            body = """{"error":"rate limited"}""",
        )

        assertThat(error.statusCode).isEqualTo(429)
        assertThat(error.retryable).isTrue()
        assertThat(error.message).contains("HTTP 429")
    }

    @Test
    fun `provider http failure captures retry after hints`() {
        val error = providerHttpFailure(
            providerName = "openai",
            statusCode = 429,
            body = """{"error":"rate limited"}""",
            retryAfterHeader = "2",
        )

        assertThat(error.retryAfterMillis).isEqualTo(2_000)
    }

    @Test
    fun `provider http failure marks permanent statuses as non retryable`() {
        val error = providerHttpFailure(
            providerName = "openai",
            statusCode = 401,
            body = """{"error":"unauthorized"}""",
        )

        assertThat(error.statusCode).isEqualTo(401)
        assertThat(error.retryable).isFalse()
    }

    @Test
    fun `provider transport failure marks timeout as retryable`() {
        val error = providerTransportFailure(
            providerName = "ollama",
            error = HttpTimeoutException("request timed out"),
        )

        assertThat(error.retryable).isTrue()
        assertThat(error.cause).isInstanceOf(HttpTimeoutException::class.java)
        assertThat(error.message).contains("request timed out")
    }

    @Test
    fun `provider transport failure marks connection failures as retryable`() {
        val error = providerTransportFailure(
            providerName = "anthropic",
            error = ConnectException("connection refused"),
        )

        assertThat(error.retryable).isTrue()
        assertThat(error.cause).isInstanceOf(ConnectException::class.java)
        assertThat(error.message).contains("connection failed")
    }

    @Test
    fun `provider transport failure marks io failures as retryable`() {
        val error = providerTransportFailure(
            providerName = "openai",
            error = IOException("socket closed"),
        )

        assertThat(error.retryable).isTrue()
        assertThat(error.cause).isInstanceOf(IOException::class.java)
        assertThat(error.message).contains("transport failed")
    }

    @Test
    fun `provider transport failure leaves unexpected failures non retryable`() {
        val error = providerTransportFailure(
            providerName = "openai",
            error = IllegalStateException("boom"),
        )

        assertThat(error.retryable).isFalse()
        assertThat(error.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(error.message).contains("failed")
    }
}
