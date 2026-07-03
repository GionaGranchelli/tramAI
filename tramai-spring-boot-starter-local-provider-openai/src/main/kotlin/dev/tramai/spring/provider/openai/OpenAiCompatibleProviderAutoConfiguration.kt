package dev.tramai.spring.provider.openai

import dev.tramai.openai.OpenAiCompatibleProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Auto-configures the sovereign lab [OpenAiCompatibleProvider] bean from
 * the `tramai.providers.local-lab-provider` YAML entry.
 *
 * Only the `local-lab-provider` key with `type=openai` triggers bean
 * creation. Unknown keys or non-openai types are silently skipped
 * (future PRs may generalize to support all `tramai.providers.*` entries).
 *
 * The resulting [OpenAiCompatibleProvider] is a [ConditionalOnMissingBean]
 * — a user-provided bean with name `localLabModelProvider` takes precedence.
 */
@AutoConfiguration
@EnableConfigurationProperties(TramaiProviderProperties::class)
class OpenAiCompatibleProviderAutoConfiguration {

    @Bean(name = ["localLabModelProvider"])
    @ConditionalOnProperty(
        prefix = "tramai.providers.local-lab-provider",
        name = ["type"],
        havingValue = "openai",
    )
    @ConditionalOnMissingBean(name = ["localLabModelProvider"])
    fun localLabModelProvider(
        properties: TramaiProviderProperties,
    ): OpenAiCompatibleProvider {
        val entry = requireNotNull(properties.providers["local-lab-provider"]) {
            "tramai.providers.local-lab-provider is required when type=openai (this should not happen — property condition should have prevented this)"
        }

        return OpenAiCompatibleProvider.bearerToken(
            bearerToken = entry.apiKey ?: "local-dev",
            baseUrl = requireNotNull(entry.baseUrl) {
                "tramai.providers.local-lab-provider.base-url is required for OpenAI-compatible provider"
            },
            providerName = "local-lab-provider",
        )
    }
}
