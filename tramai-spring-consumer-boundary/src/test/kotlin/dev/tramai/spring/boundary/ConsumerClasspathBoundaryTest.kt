package dev.tramai.spring.boundary

import org.assertj.core.api.Assertions.assertThatCode
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
 * These assertions characterize the CURRENT leak and are expected to pass today.
 * PR #261 flips them after the adapter split: the provider adapter classes and the
 * AWS SDK class will no longer be loadable, and the assertions below invert to
 * [assertThatThrownBy] expecting [ClassNotFoundException].
 */
class ConsumerClasspathBoundaryTest {

    @Test
    fun `runtime classpath currently carries provider adapters and the AWS SDK (characterization -- flipped by #261)`() {
        // TramAI provider adapter classes (not SDKs) reachable on a consumer classpath.
        assertThatCode { Class.forName("dev.tramai.openai.OpenAiProvider") }
            .describedAs("tramai-openai adapter is on the consumer runtime classpath (leak; flipped by #261)")
            .doesNotThrowAnyException()
        assertThatCode { Class.forName("dev.tramai.anthropic.AnthropicProvider") }
            .describedAs("tramai-anthropic adapter is on the consumer runtime classpath (leak; flipped by #261)")
            .doesNotThrowAnyException()
        assertThatCode { Class.forName("dev.tramai.ollama.OllamaProvider") }
            .describedAs("tramai-ollama adapter is on the consumer runtime classpath (leak; flipped by #261)")
            .doesNotThrowAnyException()
        // External SDK class pulled in transitively by tramai-spring's AWS secrets resolver.
        assertThatCode { Class.forName("software.amazon.awssdk.services.secretsmanager.SecretsManagerClient") }
            .describedAs("AWS SDK SecretsManager is on the consumer runtime classpath (leak; flipped by #261)")
            .doesNotThrowAnyException()
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
