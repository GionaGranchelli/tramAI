package dev.tramai.spring.provider.openai

import dev.tramai.core.provider.ModelProvider
import dev.tramai.openai.OpenAiCompatibleProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class OpenAiCompatibleProviderAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OpenAiCompatibleProviderAutoConfiguration::class.java))

    // ── Happy path ──

    @Test
    fun `creates OpenAI provider from tramai providers yaml`() {
        contextRunner
            .withPropertyValues(
                "tramai.providers.local-lab-provider.type=openai",
                "tramai.providers.local-lab-provider.base-url=http://localhost:11434/v1",
                "tramai.providers.local-lab-provider.api-key=local-dev",
                "tramai.providers.local-lab-provider.model=qwen2.5:7b",
            )
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(OpenAiCompatibleProvider::class.java)
                assertThat(ctx).getBean(ModelProvider::class.java).isNotNull

                val provider = ctx.getBean(OpenAiCompatibleProvider::class.java)
                assertThat(provider.providerId())
                    .describedAs("Provider name must match the YAML key")
                    .isEqualTo("local-lab-provider")
            }
    }

    // ── Missing / non-openai type ──

    @Test
    fun `does not create provider when local-lab-provider entry is missing`() {
        contextRunner
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBeanProvider(OpenAiCompatibleProvider::class.java).ifAvailable)
                    .describedAs("No OpenAI provider bean when tramai.providers entry is missing")
                    .isNull()
            }
    }

    @Test
    fun `does not create provider when type is not openai`() {
        contextRunner
            .withPropertyValues(
                "tramai.providers.local-lab-provider.type=anthropic",
                "tramai.providers.local-lab-provider.base-url=http://localhost:9999/v1",
            )
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBeanProvider(OpenAiCompatibleProvider::class.java).ifAvailable)
                    .describedAs("No OpenAI provider bean when type is not openai")
                    .isNull()
            }
    }

    // ── Validation ──

    @Test
    fun `fails when base-url is missing`() {
        contextRunner
            .withPropertyValues(
                "tramai.providers.local-lab-provider.type=openai",
                "tramai.providers.local-lab-provider.api-key=local-dev",
                // no base-url
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx.startupFailure).isNotNull
                assertThat(ctx.startupFailure!!.message)
                    .contains("base-url is required")
            }
    }

    // ── Conditional on missing bean ──

    @Test
    fun `user-provided local-lab-provider bean takes precedence`() {
        val userProvider = OpenAiCompatibleProvider.bearerToken(
            bearerToken = "user-token",
            baseUrl = "http://user-localhost:8080/v1",
            providerName = "local-lab-provider",
        )

        contextRunner
            .withPropertyValues(
                "tramai.providers.local-lab-provider.type=openai",
                "tramai.providers.local-lab-provider.base-url=http://localhost:11434/v1",
                "tramai.providers.local-lab-provider.api-key=default-key",
            )
            .withBean("localLabModelProvider", OpenAiCompatibleProvider::class.java, java.util.function.Supplier { userProvider })
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(OpenAiCompatibleProvider::class.java)
                val provider = ctx.getBean(OpenAiCompatibleProvider::class.java)
                assertThat(provider)
                    .describedAs("User-provided bean must be the one in context")
                    .isSameAs(userProvider)
            }
    }

    // ── No HTTP call ──

    @Test
    fun `provider bean is created without making any HTTP call`() {
        contextRunner
            .withPropertyValues(
                "tramai.providers.local-lab-provider.type=openai",
                "tramai.providers.local-lab-provider.base-url=http://localhost:11434/v1",
                "tramai.providers.local-lab-provider.api-key=local-dev",
                "tramai.providers.local-lab-provider.model=qwen2.5:7b",
            )
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(OpenAiCompatibleProvider::class.java)
                // Creating the bean constructs the HttpClient but does not perform a request.
                // If an HTTP call had been attempted to a non-existent localhost port,
                // the context would fail or the bean creation would hang.
            }
    }
}
