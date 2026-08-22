package dev.tramai.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Ollama provider settings (`tramai.providers.ollama.*`).
 *
 * Owned by the Ollama adapter module — the Spring core never binds or reads
 * provider-specific configuration.
 */
@ConfigurationProperties("tramai.providers.ollama")
data class OllamaProperties(
    var baseUrl: String? = null,
)
