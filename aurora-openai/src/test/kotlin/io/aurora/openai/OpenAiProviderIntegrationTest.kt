package io.aurora.openai

import io.aurora.core.model.Message
import io.aurora.core.model.MessageRole
import io.aurora.core.model.ModelRequest
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test

class OpenAiProviderIntegrationTest {

    @Test
    fun `can complete against a real openai compatible endpoint when explicitly enabled`() {
        assumeTrue(
            System.getenv("AURORA_RUN_OPENAI_INTEGRATION") == "true",
            "Set AURORA_RUN_OPENAI_INTEGRATION=true to enable this test.",
        )

        val model = System.getenv("AURORA_OPENAI_MODEL")
        assumeTrue(
            !model.isNullOrBlank(),
            "Set AURORA_OPENAI_MODEL to the model used for this integration check.",
        )

        val apiKey = System.getenv("AURORA_OPENAI_API_KEY")
        val bearerToken = System.getenv("AURORA_OPENAI_BEARER_TOKEN")
        assumeTrue(
            !apiKey.isNullOrBlank() || !bearerToken.isNullOrBlank(),
            "Set AURORA_OPENAI_API_KEY or AURORA_OPENAI_BEARER_TOKEN to enable this test.",
        )

        val provider = when {
            !apiKey.isNullOrBlank() -> OpenAiProvider(
                apiKey = apiKey,
                baseUrl = System.getenv("AURORA_OPENAI_BASE_URL") ?: OpenAiProvider.DEFAULT_BASE_URL,
            )

            else -> OpenAiProvider.bearerToken(
                bearerToken = bearerToken.orEmpty(),
                baseUrl = System.getenv("AURORA_OPENAI_BASE_URL") ?: OpenAiProvider.DEFAULT_BASE_URL,
            )
        }

        val response = runBlocking {
            provider.complete(
                ModelRequest(
                    model = model,
                    messages = listOf(
                        Message(MessageRole.SYSTEM, "Reply with the exact token AURORA_OPENAI_OK and nothing else."),
                        Message(MessageRole.USER, "Return the token now."),
                    ),
                    timeoutMillis = 60_000,
                    maxTokens = 32,
                    temperature = 0.0,
                ),
            )
        }

        assertThat(response.content).containsIgnoringCase("AURORA_OPENAI_OK")
        assertThat(response.modelUsed).isNotBlank()
    }
}
