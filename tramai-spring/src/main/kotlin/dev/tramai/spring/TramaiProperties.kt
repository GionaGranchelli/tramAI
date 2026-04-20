package dev.tramai.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Externalized Spring Boot configuration for Tramai.
 */
@ConfigurationProperties("tramai")
data class TramaiProperties(
    var defaultProvider: String? = null,
    var models: Map<String, String> = emptyMap(),
    var fallbacks: Map<String, List<FallbackRoute>> = emptyMap(),
    var resilience: Resilience = Resilience(),
    var cost: Cost = Cost(),
    var cache: Cache = Cache(),
    var providers: Providers = Providers(),
) {
    /**
     * Explicit fallback route for a requested model.
     */
    data class FallbackRoute(
        var provider: String? = null,
        var model: String? = null,
    )

    /**
     * Resilience controls applied by the engine.
     */
    data class Resilience(
        var circuitBreaker: CircuitBreaker = CircuitBreaker(),
        var retry: Retry = Retry(),
    )

    /**
     * Circuit breaker settings for provider routing.
     */
    data class CircuitBreaker(
        var enabled: Boolean = false,
        var failureThreshold: Int = 3,
        var openDurationMillis: Long = 30_000,
    )

    /**
     * Retry pacing settings.
     */
    data class Retry(
        var maxRetryAfterMillis: Long = 30_000,
        var jitterRatio: Double = 0.2,
    )

    /**
     * Cost-control settings applied by the engine.
     */
    data class Cost(
        var tokenBudget: TokenBudget = TokenBudget(),
    )

    /**
     * Token budget settings enforced from provider-reported usage.
     */
    data class TokenBudget(
        var hardMaxTokensPerAttempt: Long? = null,
        var hardMaxTokensPerOperation: Long? = null,
        var softMaxTokensPerOperation: Long? = null,
    )

    /**
     * Cache settings applied by the engine.
     */
    data class Cache(
        var inMemory: InMemoryCache = InMemoryCache(),
    )

    /**
     * In-memory response cache settings.
     */
    data class InMemoryCache(
        var enabled: Boolean = false,
        var maxEntries: Int = 1_000,
    )

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
        var apiKeySecretRef: String? = null,
        var baseUrl: String? = null,
    )

    /**
     * OpenAI provider settings.
     */
    data class OpenAi(
        var apiKey: String? = null,
        var apiKeySecretRef: String? = null,
        var bearerToken: String? = null,
        var bearerTokenSecretRef: String? = null,
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
        var apiKeySecretRef: String? = null,
        var bearerToken: String? = null,
        var bearerTokenSecretRef: String? = null,
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
