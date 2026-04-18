package io.aurora.spring

import io.aurora.anthropic.AnthropicProvider
import io.aurora.core.provider.ModelProvider
import io.aurora.ollama.OllamaProvider
import io.aurora.standalone.Aurora
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(AuroraProperties::class)
class AuroraAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun aurora(
        properties: AuroraProperties,
        modelProviders: ObjectProvider<ModelProvider>,
    ): Aurora {
        val builder = Aurora.builder()

        val providedProviders = modelProviders.orderedStream().toList()
        if (providedProviders.isNotEmpty()) {
            providedProviders.forEach { provider ->
                builder.provider(provider, name = provider.providerId())
            }
        } else {
            properties.providers.anthropic.apiKey?.takeIf { it.isNotBlank() }?.let { apiKey ->
                builder.provider(
                    provider = AnthropicProvider(
                        apiKey = apiKey,
                        baseUrl = properties.providers.anthropic.baseUrl ?: "https://api.anthropic.com",
                    ),
                    name = "anthropic",
                )
            }

            properties.providers.ollama.baseUrl?.takeIf { it.isNotBlank() }?.let { baseUrl ->
                builder.provider(
                    provider = OllamaProvider(baseUrl = baseUrl),
                    name = "ollama",
                )
            }
        }

        properties.models.forEach { (model, providerName) ->
            builder.model(model, providerName)
        }

        properties.defaultProvider?.takeIf { it.isNotBlank() }?.let(builder::defaultProvider)

        return builder.build()
    }

    @Bean
    fun aiServiceBeanDefinitionRegistrar(
        beanFactory: ConfigurableListableBeanFactory,
    ): AiServiceBeanDefinitionRegistrar = AiServiceBeanDefinitionRegistrar(beanFactory)
}
