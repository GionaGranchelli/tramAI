package io.aurora.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Externalized Spring Boot configuration for Aurora.
 */
@ConfigurationProperties("aurora")
data class AuroraProperties(
    var defaultProvider: String? = null,
    var models: Map<String, String> = emptyMap(),
    var providers: Providers = Providers(),
) {
    /**
     * Nested provider-specific settings.
     */
    data class Providers(
        var anthropic: Anthropic = Anthropic(),
        var ollama: Ollama = Ollama(),
    )

    /**
     * Anthropic provider settings.
     */
    data class Anthropic(
        var apiKey: String? = null,
        var baseUrl: String? = null,
    )

    /**
     * Ollama provider settings.
     */
    data class Ollama(
        var baseUrl: String? = null,
    )
}
