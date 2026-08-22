package dev.tramai.spring

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Externalized Spring Boot configuration for Tramai's generic runtime.
 *
 * Provider-specific (`tramai.providers.<name>.*`) and secret-backend-specific
 * (`tramai.secrets.<backend>.*`) property models are owned by their adapter
 * modules; this class only binds the framework-level settings that the Spring
 * core itself consumes.
 */
@ConfigurationProperties("tramai")
data class TramaiProperties(
    var defaultProvider: String? = null,
    var models: Map<String, String> = emptyMap(),
    var fallbacks: Map<String, List<FallbackRoute>> = emptyMap(),
    var resilience: Resilience = Resilience(),
    var cost: Cost = Cost(),
    var cache: Cache = Cache(),
    var security: Security = Security(),
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

    data class Security(
        var classification: Classification = Classification(),
        var modelRegistry: ModelRegistryProperties = ModelRegistryProperties(),
    )

    data class ModelRegistryProperties(
        var enabled: Boolean = false,
    )

    data class ClassificationRuleProperties(
        var id: String = "",
        var classification: String = "",
        var priority: Int = 0,
        var pattern: String? = null,
        var metadataEquals: Map<String, String> = emptyMap(),
    )

    data class Classification(
        var enabled: Boolean = false,
        var defaultClassification: String = "INTERNAL",
        var maxTextLength: Int = 100_000,
        var rules: List<ClassificationRuleProperties> = emptyList(),
    )
}
