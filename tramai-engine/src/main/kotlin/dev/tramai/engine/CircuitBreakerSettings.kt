package dev.tramai.engine

/**
 * Engine-owned circuit breaker settings for provider routing.
 */
data class CircuitBreakerSettings(
    val enabled: Boolean = false,
    val failureThreshold: Int = 3,
    val openDurationMillis: Long = 30_000,
) {
    init {
        require(failureThreshold > 0) { "Circuit breaker failureThreshold must be greater than zero" }
        require(openDurationMillis > 0) { "Circuit breaker openDurationMillis must be greater than zero" }
    }
}
