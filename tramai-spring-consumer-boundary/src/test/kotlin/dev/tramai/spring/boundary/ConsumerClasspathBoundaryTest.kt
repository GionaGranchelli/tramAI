package dev.tramai.spring.boundary

import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * Post-#261 state (flipped): tramai-spring is a thin facade over tramai-spring-core.
 * Provider adapters and the AWS SDK are NO LONGER on the consumer classpath; consumers
 * select them explicitly via tramai-spring-provider-* / tramai-spring-secrets-*.
 */
class ConsumerClasspathBoundaryTest {

    @Test
    fun `runtime classpath no longer carries provider adapters or the AWS SDK (flipped by #261)`() {
        // TramAI provider adapter classes (not SDKs) must NOT be reachable on a
        // consumer classpath that declares only tramai-spring.
        assertThatThrownBy { Class.forName("dev.tramai.openai.OpenAiProvider") }
            .describedAs("tramai-openai adapter must not leak onto the consumer runtime classpath")
            .isInstanceOf(ClassNotFoundException::class.java)
        assertThatThrownBy { Class.forName("dev.tramai.anthropic.AnthropicProvider") }
            .describedAs("tramai-anthropic adapter must not leak onto the consumer runtime classpath")
            .isInstanceOf(ClassNotFoundException::class.java)
        assertThatThrownBy { Class.forName("dev.tramai.ollama.OllamaProvider") }
            .describedAs("tramai-ollama adapter must not leak onto the consumer runtime classpath")
            .isInstanceOf(ClassNotFoundException::class.java)
        // External SDK class pulled in transitively by tramai-spring's AWS secrets resolver.
        assertThatThrownBy { Class.forName("software.amazon.awssdk.services.secretsmanager.SecretsManagerClient") }
            .describedAs("AWS SDK SecretsManager must not leak onto the consumer runtime classpath")
            .isInstanceOf(ClassNotFoundException::class.java)
    }

    @Test
    fun `generic spring integration classes are present`() {
        Class.forName("dev.tramai.spring.TramaiAutoConfiguration")
        Class.forName("dev.tramai.spring.TramaiSecretResolutionAutoConfiguration")
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
