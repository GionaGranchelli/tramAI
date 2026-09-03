package dev.tramai.engine

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Direct contract tests for [RetryPolicySettings] and [CircuitBreakerSettings].
 *
 * Defaults are behavioral: retry pacing and breaker thresholds gate provider
 * traffic, so silently changed defaults (e.g. jitterRatio collapsing to 0.0)
 * would alter production retry behavior without any caller noticing.
 */
class RetrySettingsContractTest {
    // ------------------------------------------------------------ RetryPolicySettings

    @Test
    fun `retry policy defaults to documented pacing`() {
        val settings = RetryPolicySettings()

        assertThat(settings.maxRetryAfterMillis).isEqualTo(30_000L)
        assertThat(settings.jitterRatio).isEqualTo(0.2)
    }

    @Test
    fun `retry policy rejects non-positive max retry after`() {
        assertThatThrownBy { RetryPolicySettings(maxRetryAfterMillis = 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be greater than zero")
    }

    @Test
    fun `retry policy rejects negative jitter ratio`() {
        assertThatThrownBy { RetryPolicySettings(jitterRatio = -0.01) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("jitterRatio")
    }

    // ------------------------------------------------------------ CircuitBreakerSettings

    @Test
    fun `circuit breaker defaults to disabled with documented thresholds`() {
        val settings = CircuitBreakerSettings()

        assertThat(settings.enabled).isFalse()
        assertThat(settings.failureThreshold).isEqualTo(3)
        assertThat(settings.openDurationMillis).isEqualTo(30_000L)
    }

    @Test
    fun `circuit breaker rejects non-positive failure threshold`() {
        assertThatThrownBy { CircuitBreakerSettings(failureThreshold = 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be greater than zero")
    }

    @Test
    fun `circuit breaker rejects non-positive open duration`() {
        assertThatThrownBy { CircuitBreakerSettings(openDurationMillis = 0L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must be greater than zero")
    }
}
