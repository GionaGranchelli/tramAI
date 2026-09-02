package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.ProviderCircuitBreaker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Direct contract tests for [ProviderCircuitBreaker.openUntilMillis] and the
 * disabled-state behavior.
 *
 * These pin the edge returns that the lifecycle tests only reach indirectly:
 * openUntilMillis on a disabled breaker, on an unknown provider, and at the
 * exact expiry boundary (now == blockedUntil must be treated as expired so a
 * replacement probe can be admitted).
 */
class ProviderCircuitBreakerContractTest {
    @Test
    fun `openUntilMillis returns null when the breaker is disabled`() {
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = false), { 0L })

        assertThat(breaker.openUntilMillis("primary")).isNull()
    }

    @Test
    fun `openUntilMillis returns null for an unknown provider`() {
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true), { 0L })

        assertThat(breaker.openUntilMillis("unknown")).isNull()
    }

    @Test
    fun `openUntilMillis treats exact expiry as expired`() {
        var now = 0L
        val breaker =
            ProviderCircuitBreaker(
                CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100),
                { now },
            )
        breaker.onFailure(admit(breaker), ProviderException("down", retryable = true))
        assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

        // At the exact boundary the breaker must read as expired so the next
        // caller can transition into HALF_OPEN instead of being rejected.
        now = 100
        assertThat(breaker.openUntilMillis("primary")).isNull()
    }

    @Test
    fun `disabled breaker reports failures without opening and admits freely`() {
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = false), { 0L })
        val permit = admit(breaker)

        assertThat(breaker.onFailure(permit, ProviderException("down", retryable = true))).isFalse()
        assertThat(breaker.openUntilMillis("primary")).isNull()
    }

    @Test
    fun `default clock produces a real future deadline`() {
        // Uses the production System.currentTimeMillis default. The default
        // openDurationMillis (30s) gives a wide race-free window: opening now
        // must yield a deadline strictly in the future.
        val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1))

        breaker.onFailure(admit(breaker), ProviderException("down", retryable = true))

        assertThat(breaker.openUntilMillis("primary")).isNotNull
        assertThat(breaker.openUntilMillis("primary")!!).isGreaterThan(System.currentTimeMillis())
    }

    private fun admit(breaker: ProviderCircuitBreaker): CircuitBreakerPermit =
        (breaker.beforeCall("primary") as CircuitBreakerAdmission.Allowed).permit
}
