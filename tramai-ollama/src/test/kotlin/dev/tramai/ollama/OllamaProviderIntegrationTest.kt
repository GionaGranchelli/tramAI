package dev.tramai.ollama

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

class OllamaProviderIntegrationTest {

    @Test
    fun `can complete against a real ollama instance when explicitly enabled`() {
        assumeTrue(
            System.getenv("TRAMAI_RUN_OLLAMA_INTEGRATION") == "true",
            "Set TRAMAI_RUN_OLLAMA_INTEGRATION=true to enable this test.",
        )

        val model = System.getenv("TRAMAI_OLLAMA_MODEL")
        assumeTrue(
            !model.isNullOrBlank(),
            "Set TRAMAI_OLLAMA_MODEL to a locally available Ollama model.",
        )

        val provider = OllamaProvider(
            baseUrl = System.getenv("TRAMAI_OLLAMA_BASE_URL") ?: "http://localhost:11434",
        )

        val response = runBlocking {
            provider.complete(
                ModelRequest(
                    model = model,
                    messages = listOf(
                        Message(MessageRole.SYSTEM, "Reply with the exact token TRAMAI_OLLAMA_OK and nothing else."),
                        Message(MessageRole.USER, "Return the token now."),
                    ),
                    timeoutMillis = 120_000,
                    maxTokens = 32,
                    temperature = 0.0,
                ),
            )
        }

        assertThat(response.content).containsIgnoringCase("TRAMAI_OLLAMA_OK")
        assertThat(response.modelUsed).isNotBlank()
    }

    @Test
    fun `can stream against a real ollama instance when explicitly enabled`() {
        assumeTrue(
            System.getenv("TRAMAI_RUN_OLLAMA_INTEGRATION") == "true",
            "Set TRAMAI_RUN_OLLAMA_INTEGRATION=true to enable this test.",
        )

        val model = System.getenv("TRAMAI_OLLAMA_MODEL")
        assumeTrue(
            !model.isNullOrBlank(),
            "Set TRAMAI_OLLAMA_MODEL to a locally available Ollama model.",
        )

        val provider = OllamaProvider(
            baseUrl = System.getenv("TRAMAI_OLLAMA_BASE_URL") ?: "http://localhost:11434",
        )

        val chunks = mutableListOf<dev.tramai.core.model.StreamChunk>()
        runBlocking {
            provider.stream(
                ModelRequest(
                    model = model,
                    messages = listOf(
                        Message(MessageRole.USER, "Count from 1 to 5 and stop."),
                    ),
                    timeoutMillis = 120_000,
                    maxTokens = 64,
                )
            ).collect { chunks.add(it) }
        }

        assertThat(chunks).isNotEmpty()
        assertThat(chunks.filterIsInstance<dev.tramai.core.model.StreamChunk.Token>()).isNotEmpty()
        assertThat(chunks.last()).isInstanceOf(dev.tramai.core.model.StreamChunk.Complete::class.java)
        
        val complete = chunks.last() as dev.tramai.core.model.StreamChunk.Complete
        assertThat(complete.fullText).contains("1", "2", "3", "4", "5")
    }
}
