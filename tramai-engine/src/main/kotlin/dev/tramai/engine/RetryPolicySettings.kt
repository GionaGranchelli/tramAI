package dev.tramai.engine

/**
 * Engine-owned retry pacing settings for provider calls.
 */
data class RetryPolicySettings(
    val maxRetryAfterMillis: Long = 30_000,
    val jitterRatio: Double = 0.2,
) {
    init {
        require(maxRetryAfterMillis > 0) { "Retry policy maxRetryAfterMillis must be greater than zero" }
        require(jitterRatio >= 0.0) { "Retry policy jitterRatio must be zero or greater" }
    }
}
