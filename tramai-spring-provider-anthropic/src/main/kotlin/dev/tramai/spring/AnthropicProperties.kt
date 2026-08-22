package dev.tramai.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Anthropic provider settings (`tramai.providers.anthropic.*`).
 *
 * Owned by the Anthropic adapter module — the Spring core never binds or reads
 * provider-specific configuration.
 */
@ConfigurationProperties("tramai.providers.anthropic")
data class AnthropicProperties(
    var apiKey: String? = null,
    var apiKeySecretRef: String? = null,
    var baseUrl: String? = null,
)
