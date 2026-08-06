package dev.tramai.azureopenai

import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.observation.ProviderFailureDiagnosticEvent
import dev.tramai.core.observation.ProviderFailureDiagnosticObserver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.util.Base64
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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test

class AzureOpenAiProviderTest {

    @Test
    fun `converts image parts to azure openai content array format`() {
        val imageData = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.USER,
            content = "",
            contentParts = listOf(
                ContentPart.TextPart("What's in this image?"),
                ContentPart.ImagePart(
                    mimeType = "image/png",
                    data = imageData,
                ),
            ),
        )

        val result = provider.messageToMap(message)

        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as List<Map<String, Any?>>
        assertThat(content).hasSize(2)

        val textBlock = content[0]
        assertThat(textBlock["type"]).isEqualTo("text")
        assertThat(textBlock["text"]).isEqualTo("What's in this image?")

        val imageBlock = content[1]
        assertThat(imageBlock["type"]).isEqualTo("image_url")
        @Suppress("UNCHECKED_CAST")
        val imageUrl = imageBlock["image_url"] as Map<String, Any?>
        assertThat(imageUrl["url"]).isEqualTo("data:image/png;base64,${Base64.getEncoder().encodeToString(imageData)}")
    }

    @Test
    fun `converts image url content to image_url format`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.USER,
            content = "",
            contentParts = listOf(
                ContentPart.TextPart("Analyze this"),
                ContentPart.ImageUrlContent("https://example.com/photo.jpg"),
            ),
        )

        val result = provider.messageToMap(message)

        @Suppress("UNCHECKED_CAST")
        val content = result["content"] as List<Map<String, Any?>>
        assertThat(content).hasSize(2)

        val imageBlock = content[1]
        assertThat(imageBlock["type"]).isEqualTo("image_url")
        @Suppress("UNCHECKED_CAST")
        val imageUrl = imageBlock["image_url"] as Map<String, Any?>
        assertThat(imageUrl["url"]).isEqualTo("https://example.com/photo.jpg")
    }

    @Test
    fun `keeps plain string content when no parts are present`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.USER,
            content = "say hello",
        )

        val result = provider.messageToMap(message)

        assertThat(result["content"]).isEqualTo("say hello")
    }

    @Test
    fun `maps user role correctly`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.USER,
            content = "hello",
        )

        val result = provider.messageToMap(message)
        assertThat(result["role"]).isEqualTo("user")
    }

    @Test
    fun `maps assistant role correctly`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.ASSISTANT,
            content = "hello",
        )

        val result = provider.messageToMap(message)
        assertThat(result["role"]).isEqualTo("assistant")
    }

    @Test
    fun `rejects unsupported image mime type`() {
        val imageData = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        val message = Message(
            role = MessageRole.USER,
            content = "",
            contentParts = listOf(
                ContentPart.ImagePart(
                    mimeType = "image/bmp",
                    data = imageData,
                ),
            ),
        )

        assertThatThrownBy { provider.messageToMap(message) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsupported image mimeType")
    }

    @Test
    fun `provider id is azure-openai`() {
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
        )

        assertThat(provider.providerId()).isEqualTo("azure-openai")
    }

    @Test
    fun `fails when neither api key nor entra token source is provided`() {
        assertThatThrownBy {
            AzureOpenAiProvider(
                resourceName = "test-resource",
                deploymentId = "gpt-4o",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("apiKey")
    }

    @Test
    fun `fails when resource name is blank`() {
        assertThatThrownBy {
            AzureOpenAiProvider(
                resourceName = "",
                deploymentId = "gpt-4o",
                apiKey = "test-key",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("resourceName")
    }

    @Test
    fun `fails when deployment id is blank`() {
        assertThatThrownBy {
            AzureOpenAiProvider(
                resourceName = "test-resource",
                deploymentId = "",
                apiKey = "test-key",
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("deploymentId")
    }

    @Test
    fun `streaming send failure is sanitized and observed`() {
        val secretFixture = "azure-token /customer/alice"
        val original = IOException("send failed at $secretFixture")
        val events = mutableListOf<ProviderFailureDiagnosticEvent>()
        val provider = AzureOpenAiProvider(
            resourceName = "test-resource",
            deploymentId = "gpt-4o",
            apiKey = "test-key",
            httpClient = AzureThrowingHttpClient(original),
            ioDispatcher = EmptyCoroutineContext,
            providerFailureDiagnosticObserver = ProviderFailureDiagnosticObserver(events::add),
        )

        val chunks = runBlocking {
            provider.stream(
                ModelRequest("gpt-4o", listOf(Message(MessageRole.USER, "hello"))),
            ).toList()
        }

        assertThat(chunks).hasSize(1)
        val error = (chunks.single() as StreamChunk.Error).cause as ProviderException
        assertThat(error.message).isEqualTo("Provider transport failed")
        assertThat(error.message!!).doesNotContain(secretFixture)
        assertThat(error.cause).isNull()
        assertThat(events.single().failure).isInstanceOf(IOException::class.java)
        assertThat(events.single().failure!!.message).contains(secretFixture)
        assertThat(events.single().providerId).isEqualTo("azure-openai")
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

        val chunks = runBlocking { streamingProvider(body).stream(streamRequest()).take(1).toList() }

        assertThat(chunks.single()).isInstanceOf(StreamChunk.Token::class.java)
        assertThat(body.closed).isTrue()
    }

    private fun collectStream(body: InputStream): List<StreamChunk> = runBlocking {
        streamingProvider(body).stream(streamRequest()).toList()
    }

    private fun streamingProvider(body: InputStream): AzureOpenAiProvider = AzureOpenAiProvider(
        resourceName = "test-resource",
        deploymentId = "gpt-4o",
        apiKey = "test-key",
        httpClient = StubHttpClient(body),
        ioDispatcher = EmptyCoroutineContext,
    )

    private fun streamRequest(): ModelRequest =
        ModelRequest("gpt-4o", listOf(Message(MessageRole.USER, "hello")))
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

private class AzureThrowingHttpClient(
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
