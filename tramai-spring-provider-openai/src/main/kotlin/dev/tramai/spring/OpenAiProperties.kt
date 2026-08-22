package dev.tramai.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * OpenAI provider settings (`tramai.providers.openai.*`).
 *
 * Owned by the OpenAI adapter module — the Spring core never binds or reads
 * provider-specific configuration.
 */
@ConfigurationProperties("tramai.providers.openai")
data class OpenAiProperties(
    var apiKey: String? = null,
    var apiKeySecretRef: String? = null,
    var bearerToken: String? = null,
    var bearerTokenSecretRef: String? = null,
    var baseUrl: String? = null,
    var organization: String? = null,
    var project: String? = null,
    var codexAuth: CodexAuth = CodexAuth(),
)
