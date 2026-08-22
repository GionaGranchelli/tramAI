package dev.tramai.spring

import dev.tramai.ollama.OllamaProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/**
 * Contributes the Ollama property provider as a [SpringConfiguredModelProvider].
 *
 * Assembles OllamaProvider from `tramai.providers.ollama.*` and hands it to the
 * Spring core exactly like any other adapter module. Core stays provider-agnostic.
 */
@AutoConfiguration(before = [TramaiAutoConfiguration::class])
@ConditionalOnMissingBean(dev.tramai.standalone.Tramai::class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class OllamaProviderAutoConfiguration {

    @Bean
    fun ollamaProvider(properties: TramaiProperties): SpringConfiguredModelProvider? {
        val baseUrl = properties.providers.ollama.baseUrl
        if (baseUrl.isNullOrBlank()) return null
        return SpringConfiguredModelProvider(
            providerId = "ollama",
            provider = OllamaProvider(baseUrl = baseUrl),
        )
    }
}
