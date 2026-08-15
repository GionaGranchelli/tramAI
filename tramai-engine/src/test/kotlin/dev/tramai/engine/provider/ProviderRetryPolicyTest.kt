package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.engine.RetryPolicySettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderRetryPolicyTest {
    private val policy = ProviderRetryPolicy(ProviderRetryDelayPolicy(RetryPolicySettings(maxRetryAfterMillis = 250, jitterRatio = 0.2)) { 0.0 })

    @Test fun `retries retryable provider and timeout failures`() {
        assertThat(policy.decide(ProviderException("temporary", retryable = true), 0, 2)).isInstanceOf(ProviderRetryDecision.Retry::class.java)
        assertThat(policy.decide(TimeoutException("slow"), 0, 2)).isInstanceOf(ProviderRetryDecision.Retry::class.java)
    }

    @Test fun `stops non retryable and exhausted failures`() {
        assertThat(policy.decide(ProviderException("permanent"), 0, 2)).isEqualTo(ProviderRetryDecision.Stop)
        assertThat(policy.decide(RuntimeException("no"), 0, 2)).isEqualTo(ProviderRetryDecision.Stop)
        assertThat(policy.decide(ProviderException("temporary", retryable = true), 1, 2)).isEqualTo(ProviderRetryDecision.Stop)
    }

    @Test fun `honours capped retry after and records source`() {
        val honoured = policy.decide(ProviderException("wait", retryable = true, retryAfterMillis = 100), 0, 2) as ProviderRetryDecision.Retry
        val capped = policy.decide(ProviderException("wait", retryable = true, retryAfterMillis = 500), 0, 2) as ProviderRetryDecision.Retry
        assertThat(honoured.delayMillis).isEqualTo(100); assertThat(honoured.delaySource).isEqualTo("retry_after")
        assertThat(capped.delayMillis).isEqualTo(250); assertThat(capped.delaySource).isEqualTo("retry_after")
    }

    @Test fun `uses deterministic exponential backoff when retry after is absent`() {
        val delays = (0..5).map { (policy.decide(ProviderException("temporary", retryable = true), it, 7) as ProviderRetryDecision.Retry).delayMillis }
        assertThat(delays).containsExactly(50, 100, 200, 400, 800, 1000)
        assertThat((policy.decide(ProviderException("temporary", retryable = true), 0, 2) as ProviderRetryDecision.Retry).delaySource).isEqualTo("backoff")
    }
}
