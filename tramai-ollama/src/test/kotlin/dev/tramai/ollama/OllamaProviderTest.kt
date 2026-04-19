package dev.tramai.ollama

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.InetSocketAddress
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
