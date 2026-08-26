package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ProviderCircuitBreaker
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

/**
 * P0 discriminators for Epic 8.2g — circuit-breaker lifecycle.
 *
 * Each test encodes ONE invariant and must be RED on the current production
 * implementation (baseline) and GREEN after the 8.2g generation-fenced fix.
 *
 * Current production defects (verified at code level):
 * - sync success never reaches onSuccess (ProviderAttemptExecutor returns without it)
 * - expiry transition clears OPEN + resets failures → stampede (no HALF_OPEN probe)
 * - onSuccess removes provider state unconditionally → stale success closes newer OPEN
 * - onFailure re-opens with fresh deadline on already-open state → stale failure extends
 * - expiry resets the failure counter → one post-expiry failure cannot reopen
 */
class ProviderCircuitBreakerLifecycleDiscriminatorTest {

    // ------------------------------------------------------------------ P0-A

    @Test
    fun `P0-A synchronous success resets the consecutive failure history`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 2, openDurationMillis = 100), { now })
            var calls = 0
            val provider = FakeProvider {
                calls++
                when (calls) {
                    1 -> throw ProviderException("down", retryable = true)
                    2 -> ModelResponse("ok")
                    else -> throw ProviderException("down again", retryable = true)
                }
            }
            val coordinator = coordinator(
                plan = plan(provider),
                breaker = breaker,
            )

            // call 1: retryable failure → failures = 1
            assertThat(runCatching { coordinator.execute(executionRequest()) }.exceptionOrNull())
                .isInstanceOf(ProviderException::class.java)
            // call 2: SUCCESS — must reset failures to 0
            assertThat(coordinator.execute(executionRequest()).response.content).isEqualTo("ok")
            // call 3: retryable failure → failures = 1 (not 2)
            assertThat(runCatching { coordinator.execute(executionRequest()) }.exceptionOrNull())
                .isInstanceOf(ProviderException::class.java)
            // call 4: provider must still be attempted — success breaks the sequence
            assertThat(coordinator.execute(executionRequest()).response.content).isEqualTo("ok")
            assertThat(calls).isEqualTo(4)
        }
    }

    // ------------------------------------------------------------------ P0-B

    @Test
    fun `P0-B exact expiry admits exactly one probe and rejects the rest`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            breaker.onFailure("primary", ProviderException("down", retryable = true))
            assertThat(breaker.beforeCall("primary")).isEqualTo(100) // t=0, OPEN until 100

            now = 99
            assertThat(breaker.beforeCall("primary")).isEqualTo(100) // t=99 rejected

            now = 100
            val admissions = supervisorScope {
                (1..8).map { async { breaker.beforeCall("primary") == null } }.awaitAll()
            }
            // Exactly one HALF_OPEN probe may enter; the other 7 are rejected.
            assertThat(admissions.count { it }).isEqualTo(1)
        }
    }

    // ------------------------------------------------------------------ P0-C

    @Test
    fun `P0-C stale pre-OPEN success cannot close the newer OPEN generation`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            // A and B both admitted while CLOSED.
            assertThat(breaker.beforeCall("primary")).isNull()
            assertThat(breaker.beforeCall("primary")).isNull()

            // A fails → OPEN until 100.
            assertThat(breaker.onFailure("primary", ProviderException("A failed", retryable = true))).isTrue()
            assertThat(breaker.beforeCall("primary")).isEqualTo(100)

            // B completes SUCCESS afterwards — stale completion must not close the circuit.
            breaker.onSuccess("primary")
            assertThat(breaker.beforeCall("primary")).isEqualTo(100)
        }
    }

    // ------------------------------------------------------------------ P0-D

    @Test
    fun `P0-D stale pre-OPEN failure cannot extend the newer OPEN deadline`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            // A and B both admitted while CLOSED.
            assertThat(breaker.beforeCall("primary")).isNull()
            assertThat(breaker.beforeCall("primary")).isNull()

            // t=0: A fails → OPEN until 100.
            assertThat(breaker.onFailure("primary", ProviderException("A failed", retryable = true))).isTrue()
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // t=10: B (admitted pre-OPEN) fails → must not rewrite the deadline.
            now = 10
            breaker.onFailure("primary", ProviderException("B failed", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)
        }
    }

    // ------------------------------------------------------------------ P0-E

    @Test
    fun `P0-E failed HALF_OPEN probe immediately reopens with a fresh deadline`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 3, openDurationMillis = 100), { now })

            // Three qualifying failures → OPEN.
            repeat(3) { breaker.onFailure("primary", ProviderException("down", retryable = true)) }
            val openedUntil = breaker.openUntilMillis("primary")
            assertThat(openedUntil).isNotNull

            // Advance exactly to expiry — the HALF_OPEN probe is admitted.
            now = openedUntil!!
            assertThat(breaker.beforeCall("primary")).isNull()

            // Probe fails with a retryable failure → immediately OPEN again,
            // fresh deadline = now + openDuration, NOT three more failures.
            breaker.onFailure("primary", ProviderException("probe failed", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(now + 100)
        }
    }

    // ------------------------------------------------------------ infra copy

    private fun plan(primary: FakeProvider) = ProviderRoutingPlan.builder()
        .provider("primary", primary)
        .model("model", "primary")
        .build()

    private fun executionRequest() = ProviderExecutionRequest(
        operation = componentOperation(),
        messages = emptyList(),
        attemptCounter = AttemptCounter(),
        correlationId = "cid",
        securityContext = ExecutionSecurityContext(),
        beforeRoute = ProviderRouteGate {},
    )

    private fun coordinator(plan: ProviderRoutingPlan, breaker: ProviderCircuitBreaker): ProviderExecutionCoordinator {
        val observer = dev.tramai.core.observation.OperationObserver { RecordingObservation() }
        val attempt = executor(
            observation = RecordingObservation(),
            circuitBreaker = breaker,
        )
        return ProviderExecutionCoordinator(
            routingPlan = plan,
            circuitBreaker = breaker,
            attemptExecutor = attempt,
            fallbackPolicy = ProviderFallbackPolicy(),
            beforeResolution = ProviderResolutionGate { _, _, _ -> },
            fallbackGate = ProviderFallbackGate { _, _, _, _, _, _ -> },
        )
    }
}
