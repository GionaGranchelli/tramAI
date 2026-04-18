package io.aurora.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.aurora.core.exception.ConfigurationException
import io.aurora.core.model.Message
import io.aurora.core.model.MessageRole
import io.aurora.core.model.ModelRequest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.net.InetSocketAddress
import java.nio.file.Files
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
                body = """
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
                """.trimIndent(),
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

    private fun respond(
        exchange: HttpExchange,
        body: String,
        status: Int = 200,
    ) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
        exchange.responseBody.use { it.write(body.toByteArray()) }
    }
}
