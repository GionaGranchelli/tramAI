package dev.tramai.spring

import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.standalone.Tramai
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Cross-profile regression for the property-driven provider seam (H1).
 *
 * Uses a REAL provider adapter ([dev.tramai.spring.OpenAiProviderAutoConfiguration])
 * that contributes a [SpringConfiguredModelProvider] descriptor — not a
 * handcrafted raw [ModelProvider] fixture. Under both profiles the runtime
 * must be assembled from the adapter's descriptor with identical semantics.
 */
class SpringProviderAdapterCrossProfileTest {

    private val adapterAutoConfigurations = AutoConfigurations.of(
        Class.forName("dev.tramai.spring.StandardTramaiProfileAutoConfiguration"),
        Class.forName("dev.tramai.spring.AiServiceProxyAutoConfiguration"),
        Class.forName("dev.tramai.spring.sovereign.SovereignTramaiProfileAutoConfiguration"),
        Class.forName("dev.tramai.spring.sovereign.SovereignAiServiceProxyAutoConfiguration"),
        Class.forName("dev.tramai.spring.TramaiSecretResolutionAutoConfiguration"),
        Class.forName("dev.tramai.spring.OpenAiProviderAutoConfiguration"),
    )

    @Test
    fun `openai property adapter registers the standard runtime`() {
        ApplicationContextRunner()
            .withConfiguration(adapterAutoConfigurations)
            .withPropertyValues(
                "tramai.profile=standard",
                "tramai.providers.openai.api-key=fake-key-for-construction-only",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(Tramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramaiRuntime::class.java)
            }
    }

    @Test
    fun `openai property adapter registers the sovereign runtime`() {
        ApplicationContextRunner()
            .withConfiguration(adapterAutoConfigurations)
            .withPropertyValues(
                "tramai.profile=sovereign",
                "tramai.sovereign.allowed-models[0]=gpt-4o",
                "tramai.sovereign.allowed-providers[0]=openai",
                "tramai.sovereign.provider-zones.openai=GLOBAL_CLOUD",
                "tramai.sovereign.models.gpt-4o=openai",
                "tramai.providers.openai.api-key=fake-key-for-construction-only",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(Tramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
            }
    }
}
