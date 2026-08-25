package dev.tramai.spring.sovereign

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ModelProvider
import dev.tramai.sovereign.SovereignTramai
import dev.tramai.sovereign.SovereignTramaiRuntime
import dev.tramai.spring.AiServiceBeanDefinitionRegistrar
import dev.tramai.spring.sovereign.aiservicefixture.SovereignScannedAiService
import dev.tramai.standalone.Tramai
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class SovereignAiServiceProxyAutoConfigurationTest {

    private val sovereignProperties = arrayOf(
        "tramai.profile=sovereign",
        "tramai.sovereign.allowed-models[0]=local-model",
        "tramai.sovereign.allowed-providers[0]=local-provider",
        "tramai.sovereign.provider-zones.local-provider=LOCAL",
        "tramai.sovereign.models.local-model=local-provider",
    )

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                SovereignTramaiProfileAutoConfiguration::class.java,
                SovereignAiServiceProxyAutoConfiguration::class.java,
            ),
        )
        .withInitializer { context ->
            AutoConfigurationPackages.register(
                context.beanFactory as BeanDefinitionRegistry,
                "dev.tramai.spring.sovereign.aiservicefixture",
            )
        }

    @Test
    fun `scanned AiService is created and executed by the sovereign runtime`() {
        runner
            .withBean(ModelProvider::class.java, { SovereignAiServiceProvider() })
            .withPropertyValues(*sovereignProperties)
            .run { context ->
                assertThat(context).hasSingleBean(SovereignTramai::class.java)
                assertThat(context).hasSingleBean(SovereignTramaiRuntime::class.java)
                assertThat(context).doesNotHaveBean(Tramai::class.java)
                assertThat(context).hasBean("tramaiAiServiceCreator")
                assertThat(context).hasSingleBean(AiServiceBeanDefinitionRegistrar::class.java)

                val service = context.getBean(SovereignScannedAiService::class.java)
                val result = runBlocking { service.answer("hello") }

                assertThat(result).isEqualTo("SOVEREIGN_OK")
            }
    }

    @Test
    fun `standard profile does not activate the sovereign AiService adapter`() {
        runner
            .withPropertyValues("tramai.profile=standard")
            .run { context ->
                assertThat(context).doesNotHaveBean("tramaiAiServiceCreator")
                assertThat(context).doesNotHaveBean(AiServiceBeanDefinitionRegistrar::class.java)
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
                assertThat(context).doesNotHaveBean(Tramai::class.java)
            }
    }

    @Test
    fun `missing sovereign creator fails loudly and cannot fall back to standard runtime`() {
        runner
            .withPropertyValues(*sovereignProperties)
            .run { context ->
                assertThat(context).doesNotHaveBean(SovereignTramai::class.java)
                assertThat(context).doesNotHaveBean(Tramai::class.java)
                assertThat(context).doesNotHaveBean("tramaiAiServiceCreator")
                assertThat(context).hasSingleBean(AiServiceBeanDefinitionRegistrar::class.java)

                assertThatThrownBy {
                    context.getBean(SovereignScannedAiService::class.java)
                }.hasRootCauseInstanceOf(NoSuchBeanDefinitionException::class.java)
            }
    }

    private class SovereignAiServiceProvider : ModelProvider {
        override fun providerId(): String = "local-provider"

        override suspend fun complete(request: ModelRequest): ModelResponse =
            ModelResponse(content = "SOVEREIGN_OK")
    }
}
