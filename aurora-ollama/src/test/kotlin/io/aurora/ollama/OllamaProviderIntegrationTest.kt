package io.aurora.ollama

import io.aurora.core.model.Message
import io.aurora.core.model.MessageRole
import io.aurora.core.model.ModelRequest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

class OllamaProviderIntegrationTest {

    @Test
    fun `can complete against a real ollama instance when explicitly enabled`() {
        assumeTrue(
            System.getenv("AURORA_RUN_OLLAMA_INTEGRATION") == "true",
            "Set AURORA_RUN_OLLAMA_INTEGRATION=true to enable this test.",
        )

        val model = System.getenv("AURORA_OLLAMA_MODEL")
        assumeTrue(
            !model.isNullOrBlank(),
            "Set AURORA_OLLAMA_MODEL to a locally available Ollama model.",
        )

        val provider = OllamaProvider(
            baseUrl = System.getenv("AURORA_OLLAMA_BASE_URL") ?: "http://localhost:11434",
        )

        val response = runBlocking {
            provider.complete(
                ModelRequest(
                    model = model,
                    messages = listOf(
                        Message(MessageRole.SYSTEM, "Reply with the exact token AURORA_OLLAMA_OK and nothing else."),
                        Message(MessageRole.USER, "Return the token now."),
                    ),
                    timeoutMillis = 120_000,
                    maxTokens = 32,
                    temperature = 0.0,
                ),
            )
        }

        assertThat(response.content).containsIgnoringCase("AURORA_OLLAMA_OK")
        assertThat(response.modelUsed).isNotBlank()
    }
}
