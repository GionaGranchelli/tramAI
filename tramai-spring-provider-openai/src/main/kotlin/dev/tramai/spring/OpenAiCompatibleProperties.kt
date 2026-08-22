package dev.tramai.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * OpenAI-compatible provider settings (`tramai.providers.openai-compatible.*`).
 *
 * Owned by the OpenAI adapter module — the Spring core never binds or reads
 * provider-specific configuration.
 */
@ConfigurationProperties("tramai.providers.openai-compatible")
data class OpenAiCompatibleProperties(
    var providerName: String = "openai-compatible",
    var apiKey: String? = null,
    var apiKeySecretRef: String? = null,
    var bearerToken: String? = null,
    var bearerTokenSecretRef: String? = null,
    var baseUrl: String? = null,
    var codexAuth: CodexAuth = CodexAuth(),
)
