package dev.tramai.openai

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

class OpenAiProviderIntegrationTest {

    @Test
    fun `can complete against a real openai compatible endpoint when explicitly enabled`() {
        assumeTrue(
            System.getenv("TRAMAI_RUN_OPENAI_INTEGRATION") == "true",
            "Set TRAMAI_RUN_OPENAI_INTEGRATION=true to enable this test.",
        )

        val model = System.getenv("TRAMAI_OPENAI_MODEL")
        assumeTrue(
            !model.isNullOrBlank(),
            "Set TRAMAI_OPENAI_MODEL to the model used for this integration check.",
        )

        val apiKey = System.getenv("TRAMAI_OPENAI_API_KEY")
        val bearerToken = System.getenv("TRAMAI_OPENAI_BEARER_TOKEN")
        assumeTrue(
            !apiKey.isNullOrBlank() || !bearerToken.isNullOrBlank(),
            "Set TRAMAI_OPENAI_API_KEY or TRAMAI_OPENAI_BEARER_TOKEN to enable this test.",
        )

        val provider = when {
            !apiKey.isNullOrBlank() -> OpenAiProvider(
                apiKey = apiKey,
                baseUrl = System.getenv("TRAMAI_OPENAI_BASE_URL") ?: OpenAiProvider.DEFAULT_BASE_URL,
            )

            else -> OpenAiProvider.bearerToken(
                bearerToken = bearerToken.orEmpty(),
                baseUrl = System.getenv("TRAMAI_OPENAI_BASE_URL") ?: OpenAiProvider.DEFAULT_BASE_URL,
            )
        }

        val response = runBlocking {
            provider.complete(
                ModelRequest(
                    model = model,
                    messages = listOf(
                        Message(MessageRole.SYSTEM, "Reply with the exact token TRAMAI_OPENAI_OK and nothing else."),
                        Message(MessageRole.USER, "Return the token now."),
                    ),
                    timeoutMillis = 60_000,
                    maxTokens = 32,
                    temperature = 0.0,
                ),
            )
        }

        assertThat(response.content).containsIgnoringCase("TRAMAI_OPENAI_OK")
        assertThat(response.modelUsed).isNotBlank()
    }
}
