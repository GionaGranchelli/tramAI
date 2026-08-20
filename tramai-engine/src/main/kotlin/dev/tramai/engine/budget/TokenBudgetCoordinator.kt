package dev.tramai.engine.budget

import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.engine.TokenBudgetSettings
import dev.tramai.engine.emitRuntimeEvent
import dev.tramai.engine.TokenBudgetSnapshot

internal class TokenBudgetCoordinator(
    private val settings: TokenBudgetSettings,
) {
    fun createTracker(): TokenBudgetTracker = TokenBudgetTracker(settings)

    fun restoreTracker(snapshot: TokenBudgetSnapshot?): TokenBudgetTracker =
        TokenBudgetTracker(settings).also { tracker ->
            snapshot?.let { tracker.restore(it) }
        }

    fun enforce(
        tracker: TokenBudgetTracker,
        response: ModelResponse,
        observation: OperationObservation,
        providerId: String,
        modelName: String,
    ) {
        when (val result = tracker.observe(response)) {
            is TokenBudgetCheckResult.Ok -> Unit
            is TokenBudgetCheckResult.UsageUnavailable -> observation.emitRuntimeEvent(
                RuntimeEvent.of(RuntimeEvents.TOKEN_BUDGET_USAGE_UNAVAILABLE) {
                    set(RuntimeAttributes.PROVIDER_ID, providerId)
                    set(RuntimeAttributes.EFFECTIVE_MODEL, modelName)
                },
            )
            is TokenBudgetCheckResult.SoftLimitExceeded -> observation.emitRuntimeEvent(
                RuntimeEvent.of(RuntimeEvents.TOKEN_BUDGET_SOFT_LIMIT_EXCEEDED) {
                    set(RuntimeAttributes.PROVIDER_ID, providerId)
                    set(RuntimeAttributes.EFFECTIVE_MODEL, modelName)
                    set(RuntimeAttributes.LIMIT_TOKENS, result.limitTokens)
                    set(RuntimeAttributes.OBSERVED_TOKENS, result.observedTokens)
                    set(RuntimeAttributes.SCOPE, "operation")
                },
            )
            is TokenBudgetCheckResult.HardLimitExceeded -> {
                observation.emitRuntimeEvent(
                    RuntimeEvent.of(RuntimeEvents.TOKEN_BUDGET_HARD_LIMIT_EXCEEDED) {
                        set(RuntimeAttributes.PROVIDER_ID, providerId)
                        set(RuntimeAttributes.EFFECTIVE_MODEL, modelName)
                        set(RuntimeAttributes.LIMIT_TOKENS, result.limitTokens)
                        set(RuntimeAttributes.OBSERVED_TOKENS, result.observedTokens)
                        set(RuntimeAttributes.SCOPE, result.scope)
                    },
                )
                throw TokenBudgetExceededException(
                    scope = result.scope,
                    limitTokens = result.limitTokens,
                    observedTokens = result.observedTokens,
                    providerId = providerId,
                    modelName = modelName,
                )
            }
        }
    }
}
