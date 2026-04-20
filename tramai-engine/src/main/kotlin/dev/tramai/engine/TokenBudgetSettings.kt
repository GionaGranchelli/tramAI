package dev.tramai.engine

/**
 * Engine-owned token budget controls based on provider-reported token usage.
 */
data class TokenBudgetSettings(
    /**
     * Hard maximum for the total tokens reported by a single provider attempt.
     */
    val hardMaxTokensPerAttempt: Long? = null,
    /**
     * Hard maximum for the cumulative tokens reported across one logical operation execution.
     *
     * This includes retries, structured-output retries, and tool-call loops.
     */
    val hardMaxTokensPerOperation: Long? = null,
    /**
     * Soft maximum for cumulative tokens across one logical operation execution.
     *
     * Crossing this threshold emits an engine event but does not fail the call.
     */
    val softMaxTokensPerOperation: Long? = null,
) {
    init {
        require(hardMaxTokensPerAttempt == null || hardMaxTokensPerAttempt > 0) {
            "TokenBudgetSettings.hardMaxTokensPerAttempt must be greater than zero when configured"
        }
        require(hardMaxTokensPerOperation == null || hardMaxTokensPerOperation > 0) {
            "TokenBudgetSettings.hardMaxTokensPerOperation must be greater than zero when configured"
        }
        require(softMaxTokensPerOperation == null || softMaxTokensPerOperation > 0) {
            "TokenBudgetSettings.softMaxTokensPerOperation must be greater than zero when configured"
        }
    }
}
