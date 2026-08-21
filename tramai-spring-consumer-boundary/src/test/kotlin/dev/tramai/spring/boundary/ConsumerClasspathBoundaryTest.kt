package dev.tramai.spring.boundary

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Epic 6.3 dependency-boundary oracle.
 *
 * A minimal Spring consumer declares ONLY tramai-spring. Everything provider-related
 * it can load at runtime is therefore a transitive leak from tramai-spring — the exact
 * thing Epic 6.3 ("consumers only receive dependencies for selected adapters") removes.
 *
 * These assertions characterize the CURRENT leak and are expected to pass today.
 * PR #261 flips them after the adapter split: the provider/AWS SDK classes will no
 * longer be loadable and the assertion messages will invert.
 */
class ConsumerClasspathBoundaryTest {

    @Test
    fun `runtime classpath currently carries provider SDKs (characterization — flipped by #261)`() {
        assertTrue(
            Class.forName("dev.tramai.openai.OpenAiProvider") != null,
            "tramai-openai is on the consumer runtime classpath (leak; flipped by #261)",
        )
        assertTrue(
            Class.forName("dev.tramai.anthropic.AnthropicProvider") != null,
            "tramai-anthropic is on the consumer runtime classpath (leak; flipped by #261)",
        )
        assertTrue(
            Class.forName("dev.tramai.ollama.OllamaProvider") != null,
            "tramai-ollama is on the consumer runtime classpath (leak; flipped by #261)",
        )
        assertTrue(
            Class.forName("software.amazon.awssdk.services.secretsmanager.SecretsManagerClient") != null,
            "AWS SDK SecretsManager is on the consumer runtime classpath (leak; flipped by #261)",
        )
    }

    @Test
    fun `generic spring integration classes are present`() {
        Class.forName("dev.tramai.spring.TramaiAutoConfiguration")
        Class.forName("dev.tramai.standalone.Tramai")
    }

    @Test
    fun `consumer declares no direct provider dependencies`() {
        val buildFile = File("build.gradle.kts").readText()
        val forbidden = listOf("tramai-openai", "tramai-anthropic", "tramai-ollama", "amazon.awssdk")
        forbidden.forEach { token ->
            assertTrue(
                buildFile.lines().none { it.contains(token) },
                "build.gradle.kts must not declare a direct dependency on $token (the leak is transitive only)",
            )
        }
    }
}
