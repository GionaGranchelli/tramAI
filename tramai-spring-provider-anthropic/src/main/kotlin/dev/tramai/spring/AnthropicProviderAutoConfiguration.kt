package dev.tramai.spring

import dev.tramai.anthropic.AnthropicProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Anthropic provider adapter auto-configuration.
 *
 * Constructs the Anthropic provider from `tramai.providers.anthropic.*`
 * properties and contributes it as a [SpringConfiguredModelProvider]
 * descriptor. The generic runtime assembly in [TramaiAutoConfiguration]
 * consumes the descriptor without knowing Anthropic exists.
 *
 * Backs off when the user supplies their own [dev.tramai.standalone.Tramai]
 * bean, matching the original behavior where the auto-configuration tramai()
 * bean (and therefore provider construction) was skipped entirely.
 */
@AutoConfiguration(before = [TramaiAutoConfiguration::class])
@EnableConfigurationProperties(AnthropicProperties::class)
@ConditionalOnMissingBean(dev.tramai.standalone.Tramai::class)
class AnthropicProviderAutoConfiguration {

    @Bean
    fun anthropicProvider(
        properties: AnthropicProperties,
        secretChain: SpringSecretChain,
    ): SpringConfiguredModelProvider? =
        SpringSecretResolution.resolve(
            directValue = properties.apiKey,
            secretRef = properties.apiKeySecretRef,
            fieldName = "tramai.providers.anthropic.apiKey",
            secretResolver = secretChain.resolver,
        )?.let { apiKey ->
            SpringConfiguredModelProvider(
                providerId = "anthropic",
                provider = AnthropicProvider(
                    apiKey = apiKey,
                    baseUrl = properties.baseUrl ?: "https://api.anthropic.com",
                ),
            )
        }
}
