package io.aurora.spring

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("aurora")
data class AuroraProperties(
    var defaultProvider: String? = null,
    var models: Map<String, String> = emptyMap(),
    var providers: Providers = Providers(),
) {
    data class Providers(
        var anthropic: Anthropic = Anthropic(),
        var ollama: Ollama = Ollama(),
    )

    data class Anthropic(
        var apiKey: String? = null,
        var baseUrl: String? = null,
    )

    data class Ollama(
        var baseUrl: String? = null,
    )
}
