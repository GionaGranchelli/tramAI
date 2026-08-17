package dev.tramai.engine.budget

import dev.tramai.core.model.ModelResponse
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.TokenBudgetSnapshot

internal class TokenBudgetTracker(
    private val settings: TokenBudgetSettings,
) {
    private var totalInputTokensObserved: Long = 0
    private var totalOutputTokensObserved: Long = 0
    private var totalInputCostObserved: Double = 0.0
    private var totalOutputCostObserved: Double = 0.0
    private var softLimitReported: Boolean = false

    fun snapshot(): TokenBudgetSnapshot = TokenBudgetSnapshot(
        totalInputTokens = totalInputTokensObserved,
        totalOutputTokens = totalOutputTokensObserved,
        totalInputCost = totalInputCostObserved,
        totalOutputCost = totalOutputCostObserved,
        warnIfExceeded = !softLimitReported,
    )

    fun restore(snapshot: TokenBudgetSnapshot) {
        totalInputTokensObserved = snapshot.totalInputTokens
        totalOutputTokensObserved = snapshot.totalOutputTokens
        totalInputCostObserved = snapshot.totalInputCost
        totalOutputCostObserved = snapshot.totalOutputCost
        softLimitReported = !snapshot.warnIfExceeded
    }

    fun observe(response: ModelResponse): TokenBudgetCheckResult {
        if (!isEnabled()) {
            return TokenBudgetCheckResult.Ok
        }

        val attemptInputTokens = response.inputTokens?.toLong() ?: return TokenBudgetCheckResult.UsageUnavailable
        val attemptOutputTokens = response.outputTokens?.toLong() ?: return TokenBudgetCheckResult.UsageUnavailable
        val attemptTokens = attemptInputTokens + attemptOutputTokens
        totalInputTokensObserved += attemptInputTokens
        totalOutputTokensObserved += attemptOutputTokens

        settings.hardMaxTokensPerAttempt?.let { limit ->
            if (attemptTokens > limit) {
                return TokenBudgetCheckResult.HardLimitExceeded(
                    scope = "attempt",
                    limitTokens = limit,
                    observedTokens = attemptTokens,
                )
            }
        }
        settings.hardMaxTokensPerOperation?.let { limit ->
            val totalTokensObserved = totalInputTokensObserved + totalOutputTokensObserved
            if (totalTokensObserved > limit) {
                return TokenBudgetCheckResult.HardLimitExceeded(
                    scope = "operation",
                    limitTokens = limit,
                    observedTokens = totalTokensObserved,
                )
            }
        }
        settings.softMaxTokensPerOperation?.let { limit ->
            val totalTokensObserved = totalInputTokensObserved + totalOutputTokensObserved
            if (!softLimitReported && totalTokensObserved > limit) {
                softLimitReported = true
                return TokenBudgetCheckResult.SoftLimitExceeded(
                    limitTokens = limit,
                    observedTokens = totalTokensObserved,
                )
            }
        }
        return TokenBudgetCheckResult.Ok
    }

    private fun isEnabled(): Boolean =
        settings.hardMaxTokensPerAttempt != null ||
            settings.hardMaxTokensPerOperation != null ||
            settings.softMaxTokensPerOperation != null
}

internal sealed class TokenBudgetCheckResult {
    data object Ok : TokenBudgetCheckResult()

    data object UsageUnavailable : TokenBudgetCheckResult()

    data class SoftLimitExceeded(
        val limitTokens: Long,
        val observedTokens: Long,
    ) : TokenBudgetCheckResult()

    data class HardLimitExceeded(
        val scope: String,
        val limitTokens: Long,
        val observedTokens: Long,
    ) : TokenBudgetCheckResult()
}
