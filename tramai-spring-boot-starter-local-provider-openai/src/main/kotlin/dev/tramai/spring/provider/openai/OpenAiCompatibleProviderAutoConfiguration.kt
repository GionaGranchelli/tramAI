package dev.tramai.spring.provider.openai

import dev.tramai.openai.OpenAiCompatibleProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configures [OpenAiCompatibleProvider] beans from
 * `tramai.providers.*` YAML entries where `type=openai`.
 *
 * For each entry, a bean is registered with the provider name as
 * both the bean name and the [OpenAiCompatibleProvider.providerId],
 * so the sovereign runtime picks it up via [ObjectProvider<ModelProvider>].
 *
 * Entry keys that do not have `type=openai` are silently skipped
 * (future PRs may add support for other provider types).
 *
 * Each resulting [OpenAiCompatibleProvider] is a [ConditionalOnMissingBean]
 * — a user-provided bean with the same name takes precedence.
 */
@AutoConfiguration
@EnableConfigurationProperties(TramAiProviderProperties::class)
class OpenAiCompatibleProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["localLabModelProvider"])
    fun localLabModelProvider(
        properties: TramAiProviderProperties,
    ): OpenAiCompatibleProvider? {
        val entry = properties.providers["local-lab-provider"] ?: return null
        if (entry.type?.lowercase() != "openai") return null

        return OpenAiCompatibleProvider.bearerToken(
            bearerToken = entry.apiKey ?: "local-dev",
            baseUrl = requireNotNull(entry.baseUrl) {
                "tramai.providers.local-lab-provider.base-url is required for OpenAI-compatible provider"
            },
            providerName = "local-lab-provider",
        )
    }
}
