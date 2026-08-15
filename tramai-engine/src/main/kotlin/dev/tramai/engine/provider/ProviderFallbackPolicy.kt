package dev.tramai.engine.provider

import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.security.DlpInspectionException

internal sealed interface ProviderFallbackDecision {
    data class Continue(val reason: ProviderFallbackReason) : ProviderFallbackDecision
    data object Stop : ProviderFallbackDecision
}
internal enum class ProviderFallbackReason { PROVIDER_FAILURE, CIRCUIT_BREAKER_OPEN }

internal open class ProviderFallbackPolicy {
    open fun decide(error: Throwable): ProviderFallbackDecision = when (error) {
        is DlpInspectionException -> ProviderFallbackDecision.Stop
        is CircuitBreakerOpenException -> ProviderFallbackDecision.Continue(ProviderFallbackReason.CIRCUIT_BREAKER_OPEN)
        is TimeoutException -> ProviderFallbackDecision.Continue(ProviderFallbackReason.PROVIDER_FAILURE)
        is ProviderException -> if (error.retryable) ProviderFallbackDecision.Continue(ProviderFallbackReason.PROVIDER_FAILURE) else ProviderFallbackDecision.Stop
        else -> ProviderFallbackDecision.Stop
    }

    fun reasonString(reason: ProviderFallbackReason): String = when (reason) {
        ProviderFallbackReason.PROVIDER_FAILURE -> "provider-failure"
        ProviderFallbackReason.CIRCUIT_BREAKER_OPEN -> "circuit-breaker-open"
    }
}
