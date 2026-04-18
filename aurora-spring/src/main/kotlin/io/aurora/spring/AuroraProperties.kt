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
        var openai: OpenAi = OpenAi(),
        var openaiCompatible: OpenAiCompatible = OpenAiCompatible(),
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
     * OpenAI provider settings.
     */
    data class OpenAi(
        var apiKey: String? = null,
        var bearerToken: String? = null,
        var baseUrl: String? = null,
        var organization: String? = null,
        var project: String? = null,
        var codexAuth: CodexAuth = CodexAuth(),
    )

    /**
     * Generic OpenAI-compatible provider settings.
     */
    data class OpenAiCompatible(
        var providerName: String = "openai-compatible",
        var apiKey: String? = null,
        var bearerToken: String? = null,
        var baseUrl: String? = null,
        var codexAuth: CodexAuth = CodexAuth(),
    )

    /**
     * Codex ChatGPT auth-file settings.
     *
     * Experimental: intended for local testing and exploratory integrations.
     */
    data class CodexAuth(
        var enabled: Boolean = false,
        var authFile: String? = null,
    )

    /**
     * Ollama provider settings.
     */
    data class Ollama(
        var baseUrl: String? = null,
    )
}
