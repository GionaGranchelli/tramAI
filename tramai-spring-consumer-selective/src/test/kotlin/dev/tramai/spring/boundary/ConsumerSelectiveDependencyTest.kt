package dev.tramai.spring.boundary

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Epic 6.3 selective-dependency oracle (companion to the boundary module).
 *
 * A consumer that explicitly selects tramai-spring-provider-openai gets the
 * OpenAI adapter — and ONLY it. Anthropic/Ollama/AWS must still be absent,
 * proving "consumers only receive dependencies for selected adapters".
 */
class ConsumerSelectiveDependencyTest {

    @Test
    fun `explicitly selected openai adapter is loadable`() {
        assertThatCode { Class.forName("dev.tramai.openai.OpenAiProvider") }
            .describedAs("declared tramai-spring-provider-openai must be on the consumer runtime classpath")
            .doesNotThrowAnyException()
        // Selecting an adapter must not replace the generic runtime integration.
        assertThatCode { Class.forName("dev.tramai.spring.TramaiAutoConfiguration") }
            .describedAs("generic spring integration must remain present when an adapter is selected")
            .doesNotThrowAnyException()
    }

    @Test
    fun `unselected adapters and the AWS SDK stay off the classpath`() {
        assertThatThrownBy { Class.forName("dev.tramai.anthropic.AnthropicProvider") }
            .describedAs("anthropic adapter must not leak without its module")
            .isInstanceOf(ClassNotFoundException::class.java)
        assertThatThrownBy { Class.forName("dev.tramai.ollama.OllamaProvider") }
            .describedAs("ollama adapter must not leak without its module")
            .isInstanceOf(ClassNotFoundException::class.java)
        assertThatThrownBy { Class.forName("software.amazon.awssdk.services.secretsmanager.SecretsManagerClient") }
            .describedAs("AWS SDK must not leak without tramai-spring-secrets-aws")
            .isInstanceOf(ClassNotFoundException::class.java)
    }
}
