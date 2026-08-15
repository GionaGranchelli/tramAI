package dev.tramai.engine.provider

import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.security.DlpInspectionException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderFallbackPolicyTest {
    private val policy = ProviderFallbackPolicy()

    @Test fun `continues retryable provider and circuit failures`() {
        assertThat(policy.decide(ProviderException("temporary", retryable = true))).isEqualTo(ProviderFallbackDecision.Continue(ProviderFallbackReason.PROVIDER_FAILURE))
        assertThat(policy.decide(TimeoutException("slow"))).isEqualTo(ProviderFallbackDecision.Continue(ProviderFallbackReason.PROVIDER_FAILURE))
        assertThat(policy.decide(CircuitBreakerOpenException("p", 1))).isEqualTo(ProviderFallbackDecision.Continue(ProviderFallbackReason.CIRCUIT_BREAKER_OPEN))
    }

    @Test fun `stops permanent runtime and dlp failures`() {
        assertThat(policy.decide(ProviderException("permanent"))).isEqualTo(ProviderFallbackDecision.Stop)
        assertThat(policy.decide(RuntimeException("no"))).isEqualTo(ProviderFallbackDecision.Stop)
        assertThat(policy.decide(DlpInspectionException("blocked"))).isEqualTo(ProviderFallbackDecision.Stop)
    }

    @Test fun `maps fallback reasons to stable event strings`() {
        assertThat(policy.reasonString(ProviderFallbackReason.PROVIDER_FAILURE)).isEqualTo("provider-failure")
        assertThat(policy.reasonString(ProviderFallbackReason.CIRCUIT_BREAKER_OPEN)).isEqualTo("circuit-breaker-open")
    }
}
