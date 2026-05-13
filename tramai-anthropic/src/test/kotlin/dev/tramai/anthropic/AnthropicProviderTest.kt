package dev.tramai.anthropic

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.InetSocketAddress
import java.util.Base64
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
