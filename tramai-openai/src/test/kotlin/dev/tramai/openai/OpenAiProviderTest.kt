package dev.tramai.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.ProviderFailureCode
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class OpenAiProviderTest {
    private lateinit var server: HttpServer
    private var capturedAuthorization: String = ""
    private var capturedOrganization: String? = null
    private var capturedProject: String? = null
    private var capturedBody: String = ""
    private var responseStatus: Int = 200
    private var responseBody: String = defaultSuccessBody()
    private var retryAfterHeader: String? = null

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            capturedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            capturedOrganization = exchange.requestHeaders.getFirst("OpenAI-Organization")
            capturedProject = exchange.requestHeaders.getFirst("OpenAI-Project")
            capturedBody = exchange.requestBody.readAllBytes().decodeToString()
            respond(
                exchange = exchange,
                body = responseBody,
                status = responseStatus,
                retryAfter = retryAfterHeader,
            )
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `posts chat completions request with api key authentication`() {
        val provider = OpenAiProvider(
            apiKey = "test-openai-key",
            baseUrl = "http://localhost:${server.address.port}/v1",
            organization = "org-123",
            project = "proj-456",
        )

        val result = runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gpt-5.1-chat-latest",
                    messages = listOf(
                        Message(MessageRole.SYSTEM, "You are concise."),
                        Message(MessageRole.USER, "say hello"),
                    ),
                    maxTokens = 120,
                    temperature = 0.2,
                    timeoutMillis = 1_500,
                ),
            )
        }

        assertThat(capturedAuthorization).isEqualTo("Bearer test-openai-key")
        assertThat(capturedOrganization).isEqualTo("org-123")
        assertThat(capturedProject).isEqualTo("proj-456")
        assertThat(capturedBody)
            .contains("\"model\":\"gpt-5.1-chat-latest\"")
            .contains("\"max_tokens\":120")
            .contains("\"temperature\":0.2")
            .contains("\"role\":\"system\"")
            .contains("\"role\":\"user\"")
        assertThat(result.content).isEqualTo("hello from openai")
        assertThat(result.inputTokens).isEqualTo(18)
        assertThat(result.outputTokens).isEqualTo(6)
        assertThat(result.modelUsed).isEqualTo("gpt-5.1-chat-latest")
    }

    @Test
    fun `supports generic openai compatible providers and array-based content`() {
        server.removeContext("/v1/chat/completions")
        server.createContext("/compat/chat/completions") { exchange ->
            capturedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            capturedBody = exchange.requestBody.readAllBytes().decodeToString()
            respond(
                exchange = exchange,
                body = """
                    {
                      "model": "gpt-5-codex",
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": [
                              { "type": "text", "text": "hello" },
                              { "type": "text", "text": "from compatible provider" }
                            ]
                          },
                          "finish_reason": "length"
                        }
                      ],
                      "usage": {
                        "prompt_tokens": 12,
                        "completion_tokens": 9
                      }
                    }
                """.trimIndent(),
            )
        }

        val provider = OpenAiCompatibleProvider.bearerToken(
            bearerToken = "compat-token",
            baseUrl = "http://localhost:${server.address.port}/compat",
            providerName = "custom-openai",
        )

        val result = runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gpt-5-codex",
                    messages = listOf(Message(MessageRole.USER, "say hello")),
                ),
            )
        }

        assertThat(provider.providerId()).isEqualTo("custom-openai")
        assertThat(capturedAuthorization).isEqualTo("Bearer compat-token")
        assertThat(result.content).isEqualTo("hello\nfrom compatible provider")
        assertThat(result.finishReason.name).isEqualTo("LENGTH")
    }

    @Test
    fun `marks transient http failures as retryable`() {
        responseStatus = 429
        responseBody = """{"error":{"message":"rate limited"}}"""
        retryAfterHeader = "2"
        val provider = OpenAiProvider(
            apiKey = "test-openai-key",
            baseUrl = "http://localhost:${server.address.port}/v1",
        )

        assertThatThrownBy {
            runBlocking {
                provider.complete(
                    ModelRequest(
                        model = "gpt-5.1-chat-latest",
                        messages = listOf(Message(MessageRole.USER, "say hello")),
                    ),
                )
            }
        }
            .isInstanceOfSatisfying(ProviderException::class.java) { error ->
                assertThat(error.statusCode).isEqualTo(429)
                assertThat(error.retryable).isTrue()
                assertThat(error.retryAfterMillis).isEqualTo(2_000)
            }
    }

    @Test
    fun `http failure keeps the body out of the public exception and emits bounded diagnostics`() {
        runBlocking {
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()
            val secretFixture = "sk-secret-222 /path/customer/alice SELECT * FROM users bearer-token-xyz"
            responseStatus = 500
            responseBody = """{"error":{"message":"$secretFixture"}}"""
            val provider = OpenAiProvider(
                apiKey = "test-openai-key",
                baseUrl = "http://localhost:${server.address.port}/v1",
                providerFailureDiagnosticObserver = ProviderFailureDiagnosticObserver { events.add(it) },
            )

            val error = runCatching {
                provider.complete(
                    ModelRequest(
                        model = "gpt-5.1-chat-latest",
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
    fun `transport failure keeps parser detail out of the public exception`() {
        runBlocking {
            val events = mutableListOf<ProviderFailureDiagnosticEvent>()
            val secretFixture = "sk-secret-222 /path/customer/alice SELECT * FROM users bearer-token-xyz"
            responseBody = "{not-json $secretFixture}"
            val provider = OpenAiProvider(
                apiKey = "test-openai-key",
                baseUrl = "http://localhost:${server.address.port}/v1",
                providerFailureDiagnosticObserver = ProviderFailureDiagnosticObserver { events.add(it) },
            )

            val error = runCatching {
                provider.complete(
                    ModelRequest(
                        model = "gpt-5.1-chat-latest",
                        messages = listOf(Message(MessageRole.USER, "say hello")),
                    ),
                )
            }.exceptionOrNull()

            assertThat(error).isInstanceOf(ProviderException::class.java)
            val providerError = error as ProviderException
            assertThat(providerError.message).isEqualTo("Provider transport failed")
            assertThat(providerError.message!!).doesNotContain(secretFixture)
            assertThat(providerError.cause).isNull()
            assertThat(providerError.failureCode).isEqualTo(ProviderFailureCode.TRANSPORT_FAILED)

            assertThat(events).hasSize(1)
            val event = events.single()
            assertThat(event.code).isEqualTo(ProviderFailureCode.TRANSPORT_FAILED)
            assertThat(event.failure).isInstanceOf(IOException::class.java)
            assertThat(event.httpBodyPreview).isNull()
        }
    }

    @Test
    fun `untrusted provider exception from token source is sanitized and observed`() = runBlocking {
        val secretFixture = "token-source-secret /customer/alice"
        val original = ProviderException(
            "token acquisition failed: $secretFixture",
            IllegalStateException("cause $secretFixture"),
        )
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val provider = OpenAiCompatibleProvider(
            accessTokenSource = OpenAiAccessTokenSource { throw original },
            providerName = "customer-openai",
            baseUrl = "http://localhost:${server.address.port}/v1",
            providerFailureDiagnosticObserver = ProviderFailureDiagnosticObserver(events::add),
        )

        val error = runCatching {
            provider.complete(
                ModelRequest(
                    model = "gpt-5.1-chat-latest",
                    messages = listOf(Message(MessageRole.USER, "say hello")),
                ),
            )
        }.exceptionOrNull() as ProviderException

        assertThat(error).isNotSameAs(original)
        assertThat(error.message).isEqualTo("Provider request failed")
        assertThat(error.message!!).doesNotContain(secretFixture)
        assertThat(error.cause).isNull()
        assertThat(events.single().failure).isSameAs(original)
        assertThat(events.single().providerId).isEqualTo("openai")
        assertThat(events.single().providerAlias).isEqualTo("customer-openai")
    }

    @Test
    fun `fails clearly when the response has no choices`() {
        responseBody = """
            {
              "model": "gpt-5.1-chat-latest",
              "choices": []
            }
        """.trimIndent()
        val provider = OpenAiProvider(
            apiKey = "test-openai-key",
            baseUrl = "http://localhost:${server.address.port}/v1",
        )

        assertThatThrownBy {
            runBlocking {
                provider.complete(
                    ModelRequest(
                        model = "gpt-5.1-chat-latest",
                        messages = listOf(Message(MessageRole.USER, "say hello")),
                    ),
                )
            }
        }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("completion choices")
    }

    @Test
    @OptIn(ExperimentalCodexAuth::class)
    fun `reads codex chatgpt access token from auth file`() {
        val authFile = Files.createTempFile("codex-auth", ".json")
        authFile.writeText(
            """
                {
                  "auth_mode": "chatgpt",
                  "tokens": {
                    "access_token": "codex-access-token"
                  }
                }
            """.trimIndent(),
        )

        val tokenSource = CodexAuthFileTokenSource(authFile)

        assertThat(tokenSource.accessToken()).isEqualTo("codex-access-token")
    }

    @Test
    @OptIn(ExperimentalCodexAuth::class)
    fun `fails when codex auth file is not configured for chatgpt`() {
        val authFile = Files.createTempFile("codex-auth", ".json")
        authFile.writeText(
            """
                {
                  "auth_mode": "api_key",
                  "tokens": {
                    "access_token": "unused"
                  }
                }
            """.trimIndent(),
        )

        assertThatThrownBy { CodexAuthFileTokenSource(authFile).accessToken() }
            .isInstanceOf(ConfigurationException::class.java)
            .hasMessageContaining("ChatGPT authentication")
    }

    @Test
    fun `converts image parts to openai content array format`() {
        val imageData = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        val provider = OpenAiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}/v1",
        )

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gpt-4o",
                    messages = listOf(
                        Message(
                            role = MessageRole.USER,
                            content = "",
                            contentParts = listOf(
                                ContentPart.TextPart("What's in this image?"),
                                ContentPart.ImagePart(
                                    mimeType = "image/png",
                                    data = imageData,
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertThat(capturedBody).contains("\"type\":\"image_url\"")
        assertThat(capturedBody).contains("\"type\":\"text\"")
        assertThat(capturedBody).contains("data:image/png;base64,")
        assertThat(capturedBody).contains(Base64.getEncoder().encodeToString(imageData))
    }

    @Test
    fun `keeps plain string content when no parts are present`() {
        val provider = OpenAiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}/v1",
        )

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gpt-4o",
                    messages = listOf(
                        Message(MessageRole.USER, "say hello"),
                    ),
                ),
            )
        }

        assertThat(capturedBody).contains("\"content\":\"say hello\"")
        assertThat(capturedBody).doesNotContain("\"content\":[")
    }

    @Test
    fun `stream closes response body after done marker`() {
        val body = TrackingInputStream(
            """
                data: {"choices":[{"delta":{"content":"hello"}}]}
                data: [DONE]
                data: {not-json}
            """.trimIndent().toByteArray(),
        )
        val chunks = collectStream(body)

        assertThat(chunks).hasSize(2)
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Complete::class.java)
        assertThat(body.closed).isTrue()
    }

    @Test
    fun `stream closes response body after malformed chunk`() {
        val body = TrackingInputStream("data: {not-json}\n".toByteArray())
        val chunks = collectStream(body)

        assertThat(chunks.single()).isInstanceOf(StreamChunk.Error::class.java)
        assertThat(body.closed).isTrue()
    }

    @Test
    fun `stream closes response body when collector stops after first token`() {
        val body = TrackingInputStream(
            """
                data: {"choices":[{"delta":{"content":"first"}}]}
                data: {"choices":[{"delta":{"content":"second"}}]}
                data: [DONE]
            """.trimIndent().toByteArray(),
        )
        val provider = streamingProvider(body)

        val chunks = runBlocking { provider.stream(streamRequest()).take(1).toList() }

        assertThat(chunks.single()).isInstanceOf(StreamChunk.Token::class.java)
        assertThat(body.closed).isTrue()
    }

    @Test
    fun `mid stream io failure is retryable sanitized and observed`() {
        val original = IOException("socket failed with secret")
        val body = MidReadFailingInputStream(
            "data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n".toByteArray(),
            original,
        )
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val provider = streamingProvider(body, ProviderFailureDiagnosticObserver(events::add))

        val chunks = runBlocking { provider.stream(streamRequest()).toList() }

        assertThat(chunks).hasSize(2)
        val error = (chunks.last() as StreamChunk.Error).cause as ProviderException
        assertThat(error.failureCode).isEqualTo(ProviderFailureCode.TRANSPORT_FAILED)
        assertThat(error.retryable).isTrue()
        assertThat(error.cause).isNull()
        assertThat(error.message).isEqualTo("Provider transport failed")
        assertThat(events.single().failure).isSameAs(original)
        assertThat(body.closed).isTrue()
    }

    private fun collectStream(body: InputStream): List<StreamChunk> = runBlocking {
        streamingProvider(body).stream(streamRequest()).toList()
    }

    private fun streamingProvider(
        body: InputStream,
        observer: ProviderFailureDiagnosticObserver = ProviderFailureDiagnosticObserver { },
    ): OpenAiProvider = OpenAiProvider(
        apiKey = "test-key",
        httpClient = StubHttpClient(body),
        providerFailureDiagnosticObserver = observer,
    )

    private fun streamRequest(): ModelRequest = ModelRequest(
        model = "gpt-4o",
        messages = listOf(Message(MessageRole.USER, "hello")),
    )

    private fun respond(
        exchange: HttpExchange,
        body: String,
        status: Int = 200,
        retryAfter: String? = null,
    ) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
        exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }

    private fun defaultSuccessBody(): String = """
        {
          "model": "gpt-5.1-chat-latest",
          "choices": [
            {
              "message": {
                "role": "assistant",
                "content": "hello from openai"
              },
              "finish_reason": "stop"
            }
          ],
          "usage": {
            "prompt_tokens": 18,
            "completion_tokens": 6
          }
        }
    """.trimIndent()
}

private open class TrackingInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    var closed: Boolean = false
        private set

    override fun read(): Int = delegate.read()
    override fun read(target: ByteArray, offset: Int, length: Int): Int = delegate.read(target, offset, length)

    override fun close() {
        closed = true
        delegate.close()
    }
}

private class MidReadFailingInputStream(
    private val firstChunk: ByteArray,
    private val failure: IOException,
) : InputStream() {
    private var index = 0
    var closed: Boolean = false
        private set

    override fun read(): Int {
        if (index >= firstChunk.size) throw failure
        return firstChunk[index++].toInt() and 0xff
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (index >= firstChunk.size) throw failure
        val count = minOf(length, firstChunk.size - index)
        firstChunk.copyInto(target, offset, index, index + count)
        index += count
        return count
    }

    override fun close() {
        closed = true
    }
}

private class StubHttpClient(private val responseBody: InputStream) : HttpClient() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> send(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): HttpResponse<T> = StubHttpResponse(request, responseBody as T)

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = CompletableFuture.completedFuture(send(request, responseBodyHandler))

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

private class StubHttpResponse<T>(
    private val originalRequest: HttpRequest,
    private val responseBody: T,
) : HttpResponse<T> {
    override fun statusCode(): Int = 200
    override fun request(): HttpRequest = originalRequest
    override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    override fun body(): T = responseBody
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = originalRequest.uri()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}
