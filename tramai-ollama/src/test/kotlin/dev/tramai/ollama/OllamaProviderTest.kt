package dev.tramai.ollama

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
import kotlinx.coroutines.flow.take
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.InetSocketAddress
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.CookieHandler
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
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class OllamaProviderTest {
    private lateinit var server: HttpServer
    private var capturedBody: String = ""
    private var responseStatus: Int = 200
    private var responseBody: String = defaultSuccessBody()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/chat") { exchange ->
            capturedBody = exchange.requestBody.readAllBytes().decodeToString()
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
    fun `posts chat request and maps response`() {
        val provider = OllamaProvider(baseUrl = "http://localhost:${server.address.port}")

        val result = runBlocking {
            provider.complete(
                ModelRequest(
                    model = "llama3.2",
                    messages = listOf(Message(MessageRole.USER, "say hello")),
                    timeoutMillis = 1_500,
                ),
            )
        }

        assertThat(capturedBody)
            .contains("\"model\":\"llama3.2\"")
            .contains("\"stream\":false")
            .contains("\"role\":\"user\"")
        assertThat(result.content).isEqualTo("hello from ollama")
        assertThat(result.inputTokens).isEqualTo(11)
        assertThat(result.outputTokens).isEqualTo(7)
        assertThat(result.modelUsed).isEqualTo("llama3.2")
    }

    @Test
    fun `marks retryable http failures`() {
        responseStatus = 503
        responseBody = """{"error":"busy"}"""
        val provider = OllamaProvider(baseUrl = "http://localhost:${server.address.port}")

        assertThatThrownBy {
            runBlocking {
                provider.complete(
                    ModelRequest(
                        model = "llama3.2",
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
            val provider = OllamaProvider(
                baseUrl = "http://localhost:${server.address.port}",
                providerFailureDiagnosticObserver = ProviderFailureDiagnosticObserver { events.add(it) },
            )

            val error = runCatching {
                provider.complete(
                    ModelRequest(
                        model = "llama3.2",
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
    fun `fails clearly when the response role is not assistant`() {
        responseBody = """
            {
              "model": "llama3.2",
              "message": {
                "role": "user",
                "content": "bad role"
              }
            }
        """.trimIndent()
        val provider = OllamaProvider(baseUrl = "http://localhost:${server.address.port}")

        assertThatThrownBy {
            runBlocking {
                provider.complete(
                    ModelRequest(
                        model = "llama3.2",
                        messages = listOf(Message(MessageRole.USER, "say hello")),
                    ),
                )
            }
        }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("Unexpected Ollama response role")
    }

    @Test
    fun `converts image parts to ollama images array format`() {
        val imageData = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
        val provider = OllamaProvider(baseUrl = "http://localhost:${server.address.port}")

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "llava",
                    messages = listOf(
                        Message(
                            role = MessageRole.USER,
                            content = "",
                            contentParts = listOf(
                                ContentPart.TextPart("describe this"),
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

        assertThat(capturedBody).contains("\"images\":")
        assertThat(capturedBody).contains(Base64.getEncoder().encodeToString(imageData))
        assertThat(capturedBody).contains("describe this")
    }

    @Test
    fun `keeps plain string content when no image parts are present`() {
        val provider = OllamaProvider(baseUrl = "http://localhost:${server.address.port}")

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "llama3.2",
                    messages = listOf(
                        Message(MessageRole.USER, "say hello"),
                    ),
                ),
            )
        }

        assertThat(capturedBody).contains("\"content\":\"say hello\"")
        assertThat(capturedBody).doesNotContain("\"images\":")
    }

    @Test
    fun `streaming send failure is sanitized and observed`() {
        val secretFixture = "ollama-token /customer/alice"
        val original = IOException("send failed at $secretFixture")
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val provider = OllamaProvider(
            httpClient = OllamaThrowingHttpClient(original),
            ioDispatcher = EmptyCoroutineContext,
            providerFailureDiagnosticObserver = ProviderFailureDiagnosticObserver(events::add),
        )

        val chunks = runBlocking {
            provider.stream(
                ModelRequest(
                    model = "llama3.2",
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
        assertThat(events.single().providerId).isEqualTo("ollama")
    }

    @Test
    fun `stream closes response body after done marker`() {
        val body = TrackingInputStream(
            """
                {"message":{"content":"hello"},"done":false}
                {"message":{"content":""},"done":true,"prompt_eval_count":2,"eval_count":1}
                {not-json}
            """.trimIndent().toByteArray(),
        )

        val chunks = collectStream(body)

        assertThat(chunks).hasSize(2)
        assertThat(chunks.last()).isInstanceOf(StreamChunk.Complete::class.java)
        assertThat(body.closed).isTrue()
    }

    @Test
    fun `stream closes response body after malformed chunk`() {
        val body = TrackingInputStream("{not-json}\n".toByteArray())

        val chunks = collectStream(body)

        assertThat(chunks.single()).isInstanceOf(StreamChunk.Error::class.java)
        assertThat(body.closed).isTrue()
    }

    @Test
    fun `stream closes response body when collector stops after first token`() {
        val body = TrackingInputStream(
            """
                {"message":{"content":"first"},"done":false}
                {"message":{"content":"second"},"done":false}
                {"message":{"content":""},"done":true}
            """.trimIndent().toByteArray(),
        )

        val chunks = runBlocking { streamingProvider(body).stream(streamRequest()).take(1).toList() }

        assertThat(chunks.single()).isInstanceOf(StreamChunk.Token::class.java)
        assertThat(body.closed).isTrue()
    }

    private fun collectStream(body: InputStream): List<StreamChunk> = runBlocking {
        streamingProvider(body).stream(streamRequest()).toList()
    }

    private fun streamingProvider(body: InputStream): OllamaProvider = OllamaProvider(
        httpClient = StubHttpClient(body),
        ioDispatcher = EmptyCoroutineContext,
    )

    private fun streamRequest(): ModelRequest = ModelRequest(
        model = "llama3.2",
        messages = listOf(Message(MessageRole.USER, "hello")),
    )

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
          "model": "llama3.2",
          "message": {
            "role": "assistant",
            "content": "hello from ollama"
          },
          "prompt_eval_count": 11,
          "eval_count": 7,
          "done_reason": "stop"
        }
    """.trimIndent()
}

private class TrackingInputStream(bytes: ByteArray) : InputStream() {
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

private class StubHttpClient(private val responseBody: InputStream) : HttpClient() {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> send(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        StubHttpResponse(request, responseBody as T)

    override fun <T : Any?> sendAsync(request: HttpRequest, responseBodyHandler: HttpResponse.BodyHandler<T>) =
        CompletableFuture.completedFuture(send(request, responseBodyHandler))

    override fun <T : Any?> sendAsync(
        request: HttpRequest,
        responseBodyHandler: HttpResponse.BodyHandler<T>,
        pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
    ) = CompletableFuture.completedFuture(send(request, responseBodyHandler))

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

private class StubHttpResponse<T>(private val originalRequest: HttpRequest, private val responseBody: T) : HttpResponse<T> {
    override fun statusCode(): Int = 200
    override fun request(): HttpRequest = originalRequest
    override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    override fun body(): T = responseBody
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = originalRequest.uri()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}

private class OllamaThrowingHttpClient(
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
