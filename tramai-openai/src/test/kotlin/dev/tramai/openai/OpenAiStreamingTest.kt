package dev.tramai.openai

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.UsageMetrics
import dev.tramai.core.observation.NoOpProviderFailureDiagnosticObserver
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import dev.tramai.core.provider.PROVIDER_ERROR_BODY_LIMIT_BYTES
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.stream.Stream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.coroutines.EmptyCoroutineContext

class OpenAiStreamingTest {

    @Test
    fun `flow emits chunks for a successful streamed response`() {
        val provider = providerWithResponse(
            statusCode = 200,
            body = Stream.of(
                """data: {"choices":[{"delta":{"content":"hel"}}]}""",
                """data: {"choices":[{"delta":{"content":"lo"}}]}""",
                """data: {"usage":{"prompt_tokens":3,"completion_tokens":2}}""",
                "data: [DONE]",
            ),
        )

        val chunks = runBlocking { provider.stream(request()).toList() }

        assertEquals(
            listOf(
                StreamChunk.Token("hel"),
                StreamChunk.Token("lo"),
                StreamChunk.Complete("hello", UsageMetrics(inputTokens = 3, outputTokens = 2)),
            ),
            chunks,
        )
    }

    @Test
    fun `streaming response includes thinking tokens from completion_tokens_details`() {
        val provider = providerWithResponse(
            statusCode = 200,
            body = Stream.of(
                """data: {"choices":[{"delta":{"content":"think"}}]}""",
                """data: {"usage":{"prompt_tokens":5,"completion_tokens":10,"completion_tokens_details":{"reasoning_tokens":42}}}""",
                "data: [DONE]",
            ),
        )

        val chunks = runBlocking { provider.stream(request()).toList() }

        assertEquals(
            listOf(
                StreamChunk.Token("think"),
                StreamChunk.Complete(
                    "think",
                    UsageMetrics(inputTokens = 5, outputTokens = 10, thinkingTokens = 42),
                ),
            ),
            chunks,
        )
    }

    @Test
    fun `non 2xx responses produce an error chunk`() {
        val provider = providerWithResponse(
            statusCode = 429,
            body = Stream.of("""{"error":{"message":"rate limited"}}"""),
        )

        val chunk = runBlocking { provider.stream(request()).toList().single() }

        assertEquals(
            429,
            assertInstanceOf(
                ProviderException::class.java,
                assertInstanceOf(StreamChunk.Error::class.java, chunk).cause,
            ).statusCode,
        )
    }

    @Test
    fun `malformed chunks are handled as terminal errors`() {
        val provider = providerWithResponse(
            statusCode = 200,
            body = Stream.of(
                ": keep-alive",
                "",
                "data: {not-json}",
            ),
        )

        val chunk = runBlocking { provider.stream(request()).toList().single() }

        val error = assertInstanceOf(StreamChunk.Error::class.java, chunk).cause as ProviderException
        // The parse error text is untrusted provider data: only the fixed
        // safe message is visible, and the original throwable is not retained.
        assertEquals("Provider transport failed", error.message)
        assertTrue(error.cause == null)
    }

    @Test
    fun `streaming send failure is sanitized and delivered only to diagnostics`() {
        val secretFixture = "token-stream-openai /customer/alice"
        val original = IOException("failed at $secretFixture")
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val provider = OpenAiCompatibleProvider(
            accessTokenSource = StaticOpenAiAccessTokenSource("test-key"),
            providerName = "mock-openai",
            baseUrl = "https://example.invalid/v1",
            httpClient = FakeHttpClient(failure = original),
            ioDispatcher = EmptyCoroutineContext,
            providerFailureDiagnosticObserver = ProviderFailureDiagnosticObserver(events::add),
        )

        val chunks = runBlocking { provider.stream(request()).toList() }

        assertEquals(1, chunks.size)
        val error = assertInstanceOf(StreamChunk.Error::class.java, chunks.single()).cause as ProviderException
        assertEquals("Provider transport failed", error.message)
        assertTrue(error.cause == null)
        assertTrue(!error.message!!.contains(secretFixture))
        assertTrue(events.single().failure is IOException)
        assertTrue(events.single().failure!!.message!!.contains(secretFixture))
        assertEquals("openai-compatible", events.single().providerId)
        assertEquals("mock-openai", events.single().providerAlias)
    }

    @Test
    fun `streaming http failure keeps the body out of the public exception and bounds diagnostics`() {
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val secretFixture = "sk-secret-streaming /path/customer/alice"
        val provider = providerWithResponse(
            statusCode = 500,
            body = Stream.of(
                """{"error":{"message":"$secretFixture"}}""",
                "x".repeat(50_000),
            ),
            observer = ProviderFailureDiagnosticObserver { events.add(it) },
        )

        val chunk = runBlocking { provider.stream(request()).toList().single() }

        val error = assertInstanceOf(StreamChunk.Error::class.java, chunk).cause as ProviderException
        assertEquals("Provider request failed with HTTP 500", error.message)
        assertTrue(!error.message!!.contains(secretFixture))
        assertTrue(error.cause == null)

        val event = events.single()
        assertEquals(ProviderFailureCode.HTTP_REJECTED, event.code)
        assertEquals(500, event.statusCode)
        assertTrue(event.httpBodyPreview != null)
        assertTrue(event.httpBodyPreview!!.length <= PROVIDER_ERROR_BODY_LIMIT_BYTES)
        assertTrue(event.httpBodyPreviewTruncated)
    }

    @Test
    fun `completion signal is emitted after content chunks`() {
        val provider = providerWithResponse(
            statusCode = 200,
            body = Stream.of(
                """data: {"choices":[{"delta":{"content":"a"}}]}""",
                """data: {"choices":[{"delta":{"content":"b"}}]}""",
                "data: [DONE]",
            ),
        )

        val chunkKinds = runBlocking { provider.stream(request()).toList().map { it::class.simpleName } }

        assertEquals(listOf("Token", "Token", "Complete"), chunkKinds)
    }

    @Test
    fun `empty stream body produces no chunks`() {
        val provider = providerWithResponse(
            statusCode = 200,
            body = Stream.empty(),
        )

        val chunks = runBlocking { provider.stream(request()).toList() }

        assertEquals(listOf(StreamChunk.Complete("", UsageMetrics())), chunks)
    }

    @Test
    fun `immediate done without content produces empty complete chunk`() {
        val provider = providerWithResponse(
            statusCode = 200,
            body = Stream.of("data: [DONE]"),
        )

        val chunks = runBlocking { provider.stream(request()).toList() }

        assertEquals(listOf(StreamChunk.Complete("", UsageMetrics())), chunks)
    }

    @Test
    fun `missing choices field in json is handled gracefully`() {
        val provider = providerWithResponse(
            statusCode = 200,
            body = Stream.of(
                """data: {"not_choices":[{"delta":{"content":"ignored"}}]}""",
                "data: [DONE]",
            ),
        )

        val chunks = runBlocking { provider.stream(request()).toList() }

        assertEquals(listOf(StreamChunk.Complete("", UsageMetrics())), chunks)
    }

    private fun providerWithResponse(
        statusCode: Int,
        body: Stream<String>,
        observer: ProviderFailureDiagnosticObserver = NoOpProviderFailureDiagnosticObserver,
    ): OpenAiCompatibleProvider = OpenAiCompatibleProvider(
        accessTokenSource = StaticOpenAiAccessTokenSource("test-key"),
        providerName = "mock-openai",
        baseUrl = "https://example.invalid/v1",
        httpClient = FakeHttpClient(
            FakeHttpResponse(
                statusCode = statusCode,
                body = body.use { ByteArrayInputStream(it.toList().joinToString("\n").toByteArray()) },
            ),
        ),
        providerFailureDiagnosticObserver = observer,
    )

    private fun request(): ModelRequest = ModelRequest(
        model = "gpt-5.1-chat-latest",
        messages = listOf(Message(MessageRole.USER, "hello")),
    )
}

private class FakeHttpClient(
    private val response: HttpResponse<InputStream>? = null,
    private val failure: IOException? = null,
) : HttpClient() {

    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> {
        failure?.let { throw it }
        return requireNotNull(response) as HttpResponse<T>
    }

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException("unused"))

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(UnsupportedOperationException("unused"))

    override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

    override fun connectTimeout(): Optional<Duration> = Optional.empty()

    override fun followRedirects(): HttpClient.Redirect = HttpClient.Redirect.NEVER

    override fun proxy(): Optional<ProxySelector> = Optional.empty()

    override fun sslContext(): SSLContext = SSLContext.getDefault()

    override fun sslParameters(): SSLParameters = SSLParameters()

    override fun authenticator(): Optional<java.net.Authenticator> = Optional.empty()

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1

    override fun executor(): Optional<Executor> = Optional.empty()
}

private data class FakeHttpResponse<T>(
    private val statusCode: Int,
    private val body: T,
    private val uri: URI = URI.create("https://example.invalid/v1/chat/completions"),
    private val headers: HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true },
) : HttpResponse<T> {

    override fun statusCode(): Int = statusCode

    override fun request(): HttpRequest = HttpRequest.newBuilder(uri).build()

    override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()

    override fun headers(): HttpHeaders = headers

    override fun body(): T = body

    override fun sslSession(): Optional<SSLSession> = Optional.empty()

    override fun uri(): URI = uri

    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}
