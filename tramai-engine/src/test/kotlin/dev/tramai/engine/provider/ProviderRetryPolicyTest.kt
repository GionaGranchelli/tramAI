package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.engine.RetryPolicySettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderRetryPolicyTest {
    private val policy =
        ProviderRetryPolicy(
            ProviderRetryDelayPolicy(RetryPolicySettings(maxRetryAfterMillis = 250, jitterRatio = 0.2)) { 0.0 },
        )

    @Test fun `retries retryable provider and timeout failures`() {
        assertThat(
            policy.decide(ProviderException("temporary", retryable = true), 0, 2),
        ).isInstanceOf(ProviderRetryDecision.Retry::class.java)
        assertThat(policy.decide(TimeoutException("slow"), 0, 2)).isInstanceOf(ProviderRetryDecision.Retry::class.java)
    }

    @Test fun `stops non retryable and exhausted failures`() {
        assertThat(policy.decide(ProviderException("permanent"), 0, 2)).isEqualTo(ProviderRetryDecision.Stop)
        assertThat(policy.decide(RuntimeException("no"), 0, 2)).isEqualTo(ProviderRetryDecision.Stop)
        assertThat(policy.decide(ProviderException("temporary", retryable = true), 1, 2)).isEqualTo(ProviderRetryDecision.Stop)
    }

    @Test fun `honours capped retry after and records source`() {
        val honoured =
            policy.decide(
                ProviderException("wait", retryable = true, retryAfterMillis = 100),
                0,
                2,
            ) as ProviderRetryDecision.Retry
        val capped = policy.decide(ProviderException("wait", retryable = true, retryAfterMillis = 500), 0, 2) as ProviderRetryDecision.Retry
        assertThat(honoured.delayMillis).isEqualTo(100)
        assertThat(honoured.delaySource).isEqualTo("retry_after")
        assertThat(capped.delayMillis).isEqualTo(250)
        assertThat(capped.delaySource).isEqualTo("retry_after")
    }

    @Test fun `uses deterministic exponential backoff when retry after is absent`() {
        val delays =
            (0..5).map { attempt ->
                val decision = policy.decide(ProviderException("temporary", retryable = true), attempt, 7)
                (decision as ProviderRetryDecision.Retry).delayMillis
            }
        assertThat(delays).containsExactly(50, 100, 200, 400, 800, 1000)
        assertThat(
            (policy.decide(ProviderException("temporary", retryable = true), 0, 2) as ProviderRetryDecision.Retry)
                .delaySource,
        ).isEqualTo("backoff")
    }

    @Test fun `applies jitter ratio to nonzero jitter sample`() {
        // jitterRatio=0.2, sample=0.5: delay = fallback + fallback * 0.2 * 0.5.
        // With a fixed nonzero sample both the multiplication and the addition
        // are observable (a zero sample would make * and / indistinguishable).
        val jittered =
            ProviderRetryPolicy(
                ProviderRetryDelayPolicy(RetryPolicySettings(maxRetryAfterMillis = 250, jitterRatio = 0.2)) { 0.5 },
            )
        val retry = jittered.decide(ProviderException("temporary", retryable = true), 0, 2)
        val decision = retry as ProviderRetryDecision.Retry
        assertThat(decision.delayMillis).isEqualTo(55) // 50 + 50 * 0.2 * 0.5
    }

    @Test fun `caps retry after before applying jitter`() {
        val jittered =
            ProviderRetryPolicy(
                ProviderRetryDelayPolicy(RetryPolicySettings(maxRetryAfterMillis = 250, jitterRatio = 0.2)) { 0.5 },
            )
        val retry =
            jittered.decide(
                ProviderException("wait", retryable = true, retryAfterMillis = 500),
                0,
                2,
            ) as ProviderRetryDecision.Retry
        // capped to 250 then jittered: 250 + 250 * 0.2 * 0.5 = 275
        assertThat(retry.delayMillis).isEqualTo(275)
    }

    @Test fun `rejects out of range jitter samples`() {
        val tooHigh =
            ProviderRetryPolicy(
                ProviderRetryDelayPolicy(RetryPolicySettings(maxRetryAfterMillis = 250, jitterRatio = 0.2)) { 1.0 },
            )
        assertThat(
            runCatching {
                tooHigh.decide(ProviderException("temporary", retryable = true), 0, 2)
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)

        val negative =
            ProviderRetryPolicy(
                ProviderRetryDelayPolicy(RetryPolicySettings(maxRetryAfterMillis = 250, jitterRatio = 0.2)) { -0.1 },
            )
        assertThat(
            runCatching {
                negative.decide(ProviderException("temporary", retryable = true), 0, 2)
            }.exceptionOrNull(),
        ).isInstanceOf(IllegalArgumentException::class.java)
    }
}
