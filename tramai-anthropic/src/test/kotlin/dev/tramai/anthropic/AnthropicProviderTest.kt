package dev.tramai.anthropic

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.InetSocketAddress
import java.io.IOException
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class AnthropicProviderTest {
    private lateinit var server: HttpServer
    private var capturedBody: String = ""
    private var capturedApiKey: String = ""
    private var capturedVersion: String = ""
    private var responseStatus: Int = 200
    private var responseBody: String = defaultSuccessBody()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/messages") { exchange ->
            capturedBody = exchange.requestBody.readAllBytes().decodeToString()
            capturedApiKey = exchange.requestHeaders.getFirst("x-api-key")
            capturedVersion = exchange.requestHeaders.getFirst("anthropic-version")
            respond(
                exchange = exchange,
                body = responseBody,
                status = responseStatus,
            )
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `posts messages request and maps anthropic response`() {
        val provider = AnthropicProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        val result = runBlocking {
            provider.complete(
                ModelRequest(
                    model = "claude-sonnet-4-20250514",
                    messages = listOf(
                        Message(MessageRole.SYSTEM, "You are concise."),
                        Message(MessageRole.USER, "say hello"),
                    ),
                    timeoutMillis = 1_500,
                ),
            )
        }

        assertThat(capturedApiKey).isEqualTo("test-key")
        assertThat(capturedVersion).isEqualTo("2023-06-01")
        assertThat(capturedBody)
            .contains("\"model\":\"claude-sonnet-4-20250514\"")
            .contains("\"system\":\"You are concise.\"")
            .contains("\"role\":\"user\"")
        assertThat(result.content).isEqualTo("hello from anthropic")
        assertThat(result.inputTokens).isEqualTo(19)
        assertThat(result.outputTokens).isEqualTo(9)
    }

    @Test
    fun `marks retryable http failures`() {
        responseStatus = 503
        responseBody = """{"error":{"message":"overloaded"}}"""
        val provider = AnthropicProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        assertThatThrownBy {
            runBlocking {
                provider.complete(
                    ModelRequest(
                        model = "claude-sonnet-4-20250514",
                        messages = listOf(Message(MessageRole.USER, "say hello")),
                    ),
                )
            }
        }
            .isInstanceOfSatisfying(ProviderException::class.java) { error ->
                assertThat(error.statusCode).isEqualTo(503)
                assertThat(error.retryable).isTrue()
            }
    }

    @Test
    fun `http failure keeps the body out of the public exception and emits bounded diagnostics`() {
        runBlocking {
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()
            val secretFixture = "sk-secret-222 /path/customer/alice SELECT * FROM users bearer-token-xyz"
            responseStatus = 500
            responseBody = """{"error":{"message":"$secretFixture"}}"""
            val provider = AnthropicProvider(
                apiKey = "test-key",
                baseUrl = "http://localhost:${server.address.port}",
                providerFailureDiagnosticObserver = ProviderFailureDiagnosticObserver { events.add(it) },
            )

            val error = runCatching {
                provider.complete(
                    ModelRequest(
                        model = "claude-sonnet-4-20250514",
                        messages = listOf(Message(MessageRole.USER, "say hello")),
                    ),
                )
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(ProviderException::class.java)
            val providerError = error as ProviderException
            assertThat(providerError.message).isEqualTo("Provider request failed with HTTP 500")
            assertThat(providerError.message!!).doesNotContain(secretFixture)
            assertThat(providerError.cause).isNull()
            assertThat(providerError.failureCode).isEqualTo(ProviderFailureCode.HTTP_REJECTED)

            assertThat(events).hasSize(1)
            val event = events.single()
            assertThat(event.code).isEqualTo(ProviderFailureCode.HTTP_REJECTED)
            assertThat(event.statusCode).isEqualTo(500)
            assertThat(event.httpBodyPreview).isNotNull()
            assertThat(event.httpBodyPreviewTruncated).isFalse()
            assertThat(event.failure).isNull()
        }
    }

    @Test
    fun `fails clearly when no text content block exists`() {
        responseBody = """
            {
              "model": "claude-sonnet-4-20250514",
              "content": [
                {
                  "type": "tool_use",
                  "name": "noop"
                }
              ]
            }
        """.trimIndent()
        val provider = AnthropicProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        assertThatThrownBy {
            runBlocking {
                provider.complete(
                    ModelRequest(
                        model = "claude-sonnet-4-20250514",
                        messages = listOf(Message(MessageRole.USER, "say hello")),
                    ),
                )
            }
        }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("text content block")
    }

    @Test
    fun `converts image parts to anthropic content block format`() {
        val imageData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val provider = AnthropicProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "claude-sonnet-4-20250514",
                    messages = listOf(
                        Message(
                            role = MessageRole.USER,
                            content = "",
                            contentParts = listOf(
                                ContentPart.TextPart("Describe this image"),
                                ContentPart.ImagePart(
                                    mimeType = "image/jpeg",
                                    data = imageData,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertThat(capturedBody).contains("\"type\":\"image\"")
        assertThat(capturedBody).contains("\"type\":\"base64\"")
        assertThat(capturedBody).contains("\"media_type\":\"image/jpeg\"")
        assertThat(capturedBody).contains(Base64.getEncoder().encodeToString(imageData))
    }

    @Test
    fun `keeps plain string content when no image parts are present`() {
        val provider = AnthropicProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "claude-sonnet-4-20250514",
                    messages = listOf(
                        Message(MessageRole.USER, "say hello"),
                    ),
                ),
            )
        }

        assertThat(capturedBody).contains("\"content\":\"say hello\"")
    }

    @Test
    fun `streaming send failure is sanitized and observed`() {
        val secretFixture = "anthropic-token /customer/alice"
        val original = IOException("send failed at $secretFixture")
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val provider = AnthropicProvider(
            apiKey = "test-key",
            httpClient = AnthropicThrowingHttpClient(original),
            ioDispatcher = EmptyCoroutineContext,
            providerFailureDiagnosticObserver = ProviderFailureDiagnosticObserver(events::add),
        )

        val chunks = runBlocking {
            provider.stream(
                ModelRequest(
                    model = "claude-sonnet-4-20250514",
                    messages = listOf(Message(MessageRole.USER, "hello")),
                ),
            ).toList()
        }

        assertThat(chunks).hasSize(1)
        val error = (chunks.single() as StreamChunk.Error).cause as ProviderException
        assertThat(error.message).isEqualTo("Provider transport failed")
        assertThat(error.message!!).doesNotContain(secretFixture)
        assertThat(error.cause).isNull()
        assertThat(events.single().failure).isInstanceOf(IOException::class.java)
        assertThat(events.single().failure!!.message).contains(secretFixture)
        assertThat(events.single().providerId).isEqualTo("anthropic")
    }

    private fun respond(
        exchange: HttpExchange,
        body: String,
        status: Int = 200,
    ) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }

    private fun defaultSuccessBody(): String = """
        {
          "model": "claude-sonnet-4-20250514",
          "content": [
            {
              "type": "text",
              "text": "hello from anthropic"
            }
          ],
          "usage": {
            "input_tokens": 19,
            "output_tokens": 9
          },
          "stop_reason": "end_turn"
        }
    """.trimIndent()
}

private class AnthropicThrowingHttpClient(
    private val failure: IOException,
) : HttpClient() {
    override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        throw failure

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(failure)

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.failedFuture(failure)

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
