package dev.tramai.gemini

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.InetSocketAddress
import com.sun.net.httpserver.Headers
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class GeminiProviderTest {
    private lateinit var server: HttpServer
    private var capturedQuery: String = ""
    private var capturedHeaders: Headers = Headers()
    private var capturedBody: String = ""
    private var responseStatus: Int = 200
    private var responseBody: String = defaultSuccessBody()
    private var retryAfterHeader: String? = null

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/v1beta/models/gemini-2.0-flash:generateContent") { exchange ->
            capturedQuery = exchange.requestURI.query ?: ""
            capturedHeaders = exchange.requestHeaders
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
    fun `posts generate content request with api key authentication`() {
        val provider = GeminiProvider(
            apiKey = "test-gemini-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        val result = runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gemini-2.0-flash",
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

        assertThat(capturedHeaders.getFirst("X-Goog-Api-Key")).isEqualTo("test-gemini-key")
        assertThat(capturedQuery).doesNotContain("key=")
        assertThat(capturedBody)
            .contains("You are concise.") // system_instruction
            .contains("say hello")
        assertThat(result.content).isEqualTo("hello from gemini")
        assertThat(result.inputTokens).isEqualTo(12)
        assertThat(result.outputTokens).isEqualTo(5)
    }

    @Test
    fun `maps system message to system instruction field`() {
        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gemini-2.0-flash",
                    messages = listOf(
                        Message(MessageRole.SYSTEM, "You are a helpful assistant."),
                        Message(MessageRole.USER, "say hello"),
                    ),
                ),
            )
        }

        assertThat(capturedBody).contains("\"system_instruction\"")
        assertThat(capturedBody).contains("You are a helpful assistant.")
    }

    @Test
    fun `maps assistant role to model and tool role to function`() {
        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gemini-2.0-flash",
                    messages = listOf(
                        Message(MessageRole.ASSISTANT, "Hello!"),
                        Message(MessageRole.USER, "How are you?"),
                        Message(MessageRole.TOOL, "Tool result", toolCallId = "call_123"),
                    ),
                ),
            )
        }

        assertThat(capturedBody).contains("\"role\":\"model\"")
        assertThat(capturedBody).contains("\"role\":\"user\"")
        assertThat(capturedBody).contains("\"role\":\"function\"")
    }

    @Test
    fun `includes tool definitions when provided`() {
        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gemini-2.0-flash",
                    messages = listOf(Message(MessageRole.USER, "What's the weather?")),
                    tools = listOf(
                        dev.tramai.core.model.ToolDefinition(
                            name = "get_weather",
                            description = "Get current weather",
                            inputSchemaJson = """{"type":"object","properties":{"location":{"type":"string"}}}""",
                        ),
                    ),
                ),
            )
        }

        assertThat(capturedBody).contains("\"function_declarations\"")
        assertThat(capturedBody).contains("get_weather")
    }

    @Test
    fun `converts image parts to inline data format`() {
        val imageData = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte())
        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gemini-2.0-flash",
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

        assertThat(capturedBody).contains("\"inlineData\"")
        assertThat(capturedBody).contains("\"mimeType\":\"image/png\"")
        assertThat(capturedBody).contains(Base64.getEncoder().encodeToString(imageData))
    }

    @Test
    fun `keeps plain text content when no parts are present`() {
        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gemini-2.0-flash",
                    messages = listOf(
                        Message(MessageRole.USER, "plain text message"),
                    ),
                ),
            )
        }

        assertThat(capturedBody).contains("\"text\":\"plain text message\"")
    }

    @Test
    fun `stream returns tokens and complete chunk`() {
        // For streaming, register context on the streamGenerateContent endpoint
        server.removeContext("/v1beta/models/gemini-2.0-flash:generateContent")
        server.createContext("/v1beta/models/gemini-2.0-flash:streamGenerateContent") { exchange ->
            capturedQuery = exchange.requestURI.query ?: ""
            capturedBody = exchange.requestBody.readAllBytes().decodeToString()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val sseBody = """
                data: {"candidates":[{"content":{"parts":[{"text":"Hello"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2}}
                
                data: [DONE]
                
            """.trimIndent()
            exchange.sendResponseHeaders(200, sseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(sseBody.toByteArray()) }
        }

        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        val chunks = runBlocking {
            provider.stream(
                ModelRequest(
                    model = "gemini-2.0-flash",
                    messages = listOf(Message(MessageRole.USER, "say hello")),
                ),
            ).toList()
        }

        assertThat(chunks).hasSize(2)
        assertThat(chunks[0]).isInstanceOf(dev.tramai.core.model.StreamChunk.Token::class.java)
        assertThat((chunks[0] as dev.tramai.core.model.StreamChunk.Token).text).isEqualTo("Hello")
        assertThat(chunks[1]).isInstanceOf(dev.tramai.core.model.StreamChunk.Complete::class.java)
        val complete = chunks[1] as dev.tramai.core.model.StreamChunk.Complete
        assertThat(complete.fullText).isEqualTo("Hello")
        assertThat(complete.usage.inputTokens).isEqualTo(5)
        assertThat(complete.usage.outputTokens).isEqualTo(2)
    }

    @Test
    fun `maps finish reason stop correctly`() {
        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        val result = runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gemini-2.0-flash",
                    messages = listOf(Message(MessageRole.USER, "say hello")),
                ),
            )
        }

        assertThat(result.finishReason.name).isEqualTo("STOP")
    }

    @Test
    fun `maps finish reason max tokens to length`() {
        responseBody = """
            {
              "candidates": [
                {
                  "content": { "parts": [{"text": "partial"}] },
                  "finishReason": "MAX_TOKENS"
                }
              ],
              "usageMetadata": { "promptTokenCount": 5, "candidatesTokenCount": 2 }
            }
        """.trimIndent()

        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        val result = runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gemini-2.0-flash",
                    messages = listOf(Message(MessageRole.USER, "say hello")),
                ),
            )
        }

        assertThat(result.finishReason.name).isEqualTo("LENGTH")
    }

    @Test
    fun `maps safety finish reason to content filter`() {
        responseBody = """
            {
              "candidates": [
                {
                  "content": { "parts": [{"text": ""}] },
                  "finishReason": "SAFETY"
                }
              ],
              "usageMetadata": { "promptTokenCount": 3, "candidatesTokenCount": 0 }
            }
        """.trimIndent()

        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        val result = runBlocking {
            provider.complete(
                ModelRequest(
                    model = "gemini-2.0-flash",
                    messages = listOf(Message(MessageRole.USER, "bad stuff")),
                ),
            )
        }

        assertThat(result.finishReason.name).isEqualTo("CONTENT_FILTER")
    }

    @Test
    fun `marks retryable http failures`() {
        responseStatus = 429
        responseBody = """{"error":{"message":"rate limited"}}"""
        retryAfterHeader = "2"

        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        assertThatThrownBy {
            runBlocking {
                provider.complete(
                    ModelRequest(
                        model = "gemini-2.0-flash",
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
    fun `fails clearly when no candidates exist`() {
        responseBody = """{"candidates": []}"""

        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        assertThatThrownBy {
            runBlocking {
                provider.complete(
                    ModelRequest(
                        model = "gemini-2.0-flash",
                        messages = listOf(Message(MessageRole.USER, "say hello")),
                    ),
                )
            }
        }
            .isInstanceOf(ProviderException::class.java)
            .hasMessageContaining("candidates")
    }

    @Test
    fun `provider id is gemini`() {
        val provider = GeminiProvider(
            apiKey = "test-key",
            baseUrl = "http://localhost:${server.address.port}",
        )

        assertThat(provider.providerId()).isEqualTo("gemini")
    }

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
          "candidates": [
            {
              "content": {
                "parts": [{"text": "hello from gemini"}]
              },
              "finishReason": "STOP"
            }
          ],
          "usageMetadata": {
            "promptTokenCount": 12,
            "candidatesTokenCount": 5
          }
        }
    """.trimIndent()
}
