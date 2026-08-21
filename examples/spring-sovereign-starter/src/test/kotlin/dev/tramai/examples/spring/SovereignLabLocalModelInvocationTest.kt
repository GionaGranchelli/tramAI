package dev.tramai.examples.spring

import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.openai.OpenAiCompatibleProvider
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.test.context.ContextConfiguration

/**
 * Opt-in proof that the sovereign-lab profile can invoke a real local
 * OpenAI-compatible model endpoint through the auto-configured
 * [OpenAiCompatibleProvider].
 *
 * Uses embedded PostgreSQL and a temporary JDBC encryption key
 * (via [LabProfileInitializer]); requires only a reachable local
 * OpenAI-compatible model endpoint.
 *
 * This test is **not** part of normal CI. It only runs when:
 * - `TRAMAI_ENABLE_LOCAL_MODEL_TEST=true`
 * - A local OpenAI-compatible endpoint is reachable at the configured URL
 *
 * Prerequisites:
 * ```
 * ollama serve
 * ollama pull qwen2.5:7b
 * ```
 *
 * Run:
 * ```
 * TRAMAI_ENABLE_LOCAL_MODEL_TEST=true ./gradlew verifySovereignLabLocalModel
 * ```
 */
@Tag("local-model")
@EnabledIfEnvironmentVariable(
    named = "TRAMAI_ENABLE_LOCAL_MODEL_TEST",
    matches = "true",
    disabledReason = """
        This test requires a real local OpenAI-compatible model endpoint.
        Set TRAMAI_ENABLE_LOCAL_MODEL_TEST=true and ensure a local endpoint
        is reachable at the TRAMAI_LOCAL_BASE_URL (default: http://localhost:11434/v1).
    """,
)
@SpringBootTest(
    classes = [SpringSovereignStarterApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.profiles.active=sovereign-lab",
        "tramai.sovereign.ops.mutations-enabled=true",
        "tramai.sovereign.ops.resume-enabled=true",
    ],
)
@ContextConfiguration(initializers = [LabProfileInitializer::class])
class SovereignLabLocalModelInvocationTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Autowired
    private lateinit var env: Environment

    @Test
    fun `invokes local OpenAI-compatible model through sovereign lab provider`() { runBlocking {
        val provider = context.getBean(OpenAiCompatibleProvider::class.java)

        val model = requireNotNull(
            env.getProperty("tramai.providers.local-lab-provider.model"),
        ) {
            "tramai.providers.local-lab-provider.model must be configured"
        }

        val request = ModelRequest(
            model = model,
            messages = listOf(
                Message(
                    role = MessageRole.USER,
                    content = "Reply with exactly the word 'ping'. Do not add any other text, punctuation, or formatting.",
                ),
            ),
            temperature = 0.0,
        )

        val response = provider.complete(request)

        assertThat(response)
            .describedAs("Local model must return a non-null response")
            .isNotNull

        assertThat(response.content)
            .describedAs("Local model response must contain non-empty content")
            .isNotBlank

        assertThat(response.modelUsed)
            .describedAs("Response must indicate which model was used")
            .isNotNull
    }
    }
}
