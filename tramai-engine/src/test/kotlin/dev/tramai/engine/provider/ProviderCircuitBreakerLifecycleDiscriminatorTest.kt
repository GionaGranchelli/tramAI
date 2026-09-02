package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
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
 *
 * The admit/blockedUntil helpers adapt the assertions to the permit-based API
 * without changing any assertion value or the encoded invariant.
 */
class ProviderCircuitBreakerLifecycleDiscriminatorTest {
    // ------------------------------------------------------------------ P0-A

    @Test
    fun `P0-A synchronous success resets the consecutive failure history`() {
        runBlocking {
            var now = 0L
            val breaker =
                breaker(failureThreshold = 2, clock = { now })
            var calls = 0
            val provider =
                FakeProvider {
                    calls++
                    when (calls) {
                        1, 3 -> throw ProviderException("down", retryable = true)
                        else -> ModelResponse("ok")
                    }
                }
            val coordinator =
                coordinator(
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
            val breaker =
                breaker(failureThreshold = 1, clock = { now })

            val permit = admit(breaker, "primary")
            breaker.onFailure(permit, ProviderException("down", retryable = true))
            assertThat(blockedUntil(breaker, "primary")).isEqualTo(100) // t=0, OPEN until 100

            now = 99
            assertThat(blockedUntil(breaker, "primary")).isEqualTo(100) // t=99 rejected

            now = 100
            val admissions =
                supervisorScope {
                    (1..8).map { async { blockedUntil(breaker, "primary") == null } }.awaitAll()
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
            val breaker =
                breaker(failureThreshold = 1, clock = { now })

            // A and B both admitted while CLOSED — each owns a CLOSED-epoch permit.
            val permitA = admit(breaker, "primary")
            val permitB = admit(breaker, "primary")

            // A fails → OPEN until 100.
            assertThat(breaker.onFailure(permitA, ProviderException("A failed", retryable = true))).isTrue()
            assertThat(blockedUntil(breaker, "primary")).isEqualTo(100)

            // B completes SUCCESS afterwards with its stale CLOSED-epoch permit —
            // must not close the circuit.
            breaker.onSuccess(permitB)
            assertThat(blockedUntil(breaker, "primary")).isEqualTo(100)
        }
    }

    // ------------------------------------------------------------------ P0-D

    @Test
    fun `P0-D stale pre-OPEN failure cannot extend the newer OPEN deadline`() {
        runBlocking {
            var now = 0L
            val breaker =
                breaker(failureThreshold = 1, clock = { now })

            // A and B both admitted while CLOSED.
            val permitA = admit(breaker, "primary")
            val permitB = admit(breaker, "primary")

            // t=0: A fails → OPEN until 100.
            assertThat(breaker.onFailure(permitA, ProviderException("A failed", retryable = true))).isTrue()
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // t=10: B (admitted pre-OPEN) fails with its stale permit → must not
            // rewrite the deadline.
            now = 10
            breaker.onFailure(permitB, ProviderException("B failed", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)
        }
    }

    // ------------------------------------------------------------------ P0-E

    @Test
    fun `P0-E failed HALF_OPEN probe immediately reopens with a fresh deadline`() {
        runBlocking {
            var now = 0L
            val breaker =
                breaker(failureThreshold = 3, clock = { now })

            // Three qualifying failures → OPEN.
            repeat(3) { breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true)) }
            val openedUntil = breaker.openUntilMillis("primary")
            assertThat(openedUntil).isNotNull

            // Advance exactly to expiry — the HALF_OPEN probe is admitted with a
            // probe permit.
            now = openedUntil!!
            val probe = admit(breaker, "primary")
            assertThat(probe).isNotNull

            // Probe fails with a retryable failure → immediately OPEN again,
            // fresh deadline = now + openDuration, NOT three more failures.
            breaker.onFailure(probe, ProviderException("probe failed", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(now + 100)
        }
    }

    // ------------------------------------------------------------ P0-F

    @Test
    fun `P0-F stale closed-era permit can never act as the next probe`() {
        runBlocking {
            // Three reopen routes from HALF_OPEN all advance the generation.
            // If any route instead DECREASED it (mutant generation - 1), the
            // stale generation-0 permit from the closed era would match the
            // state in the second HALF_OPEN epoch and wrongly reopen the
            // circuit as if it were the probe. This test forces two full
            // open/probe cycles so that collision would be observable.
            val reopenRoutes =
                listOf(
                    "probe-failure" to { breaker: ProviderCircuitBreaker, probe: CircuitBreakerPermit ->
                        breaker.onFailure(probe, ProviderException("probe failed", retryable = true))
                    },
                    "probe-neutral" to { breaker: ProviderCircuitBreaker, probe: CircuitBreakerPermit ->
                        breaker.onFailure(probe, IllegalStateException("probe neutral"))
                    },
                    "probe-abandoned" to { breaker: ProviderCircuitBreaker, probe: CircuitBreakerPermit ->
                        breaker.onAbandoned(probe)
                    },
                )

            reopenRoutes.forEach { (route, reopen) ->
                var now = 0L
                val breaker =
                    breaker(failureThreshold = 1, clock = { now })

                // Closed era: two generation-0 permits.
                val permitA = admit(breaker, "primary")
                val stalePermit = admit(breaker, "primary")

                // A fails → OPEN(gen 1) until 100.
                breaker.onFailure(permitA, ProviderException("A failed", retryable = true))
                assertThat(blockedUntil(breaker, "primary")).isEqualTo(100)

                // Cycle 1 probe: expires at 100, fails/abandons → OPEN(gen 2) until 200.
                now = 100
                val probe1 = admit(breaker, "primary")
                reopen(breaker, probe1)
                assertThat(blockedUntil(breaker, "primary")).isEqualTo(200)

                // Cycle 2: expires at 200, fresh probe is admitted under gen 2.
                now = 200
                val probe2 = admit(breaker, "primary")
                assertThat(probe2).isNotNull

                // The stale closed-era permit must NOT be able to reopen the
                // circuit while the cycle-2 probe is in flight.
                now = 210
                breaker.onFailure(stalePermit, ProviderException("stale failed", retryable = true))
                assertThat(blockedUntil(breaker, "primary"))
                    .withFailMessage("route '$route': stale permit acted as probe and reopened the circuit")
                    .isEqualTo(210)
            }
        }
    }

    // ------------------------------------------------------------ adapters

    /** Acquires a permit for [providerId] exactly as production does (beforeCall → Allowed). */
    private fun admit(
        breaker: ProviderCircuitBreaker,
        providerId: String,
    ): CircuitBreakerPermit = (breaker.beforeCall(providerId) as CircuitBreakerAdmission.Allowed).permit

    /** Returns the blocked-until millis when [providerId] is rejected, else null (admitted). */
    private fun blockedUntil(
        breaker: ProviderCircuitBreaker,
        providerId: String,
    ): Long? = (breaker.beforeCall(providerId) as? CircuitBreakerAdmission.Rejected)?.blockedUntilMillis

    // ------------------------------------------------------------ infra copy

    private fun plan(primary: FakeProvider) =
        ProviderRoutingPlan
            .builder()
            .provider("primary", primary)
            .model("model", "primary")
            .build()

    private fun executionRequest() =
        ProviderExecutionRequest(
            operation = componentOperation(),
            messages = emptyList(),
            attemptCounter = AttemptCounter(),
            correlationId = "cid",
            securityContext = ExecutionSecurityContext(),
            beforeRoute = ProviderRouteGate {},
        )

    private fun breaker(
        failureThreshold: Int,
        clock: () -> Long,
    ): ProviderCircuitBreaker =
        ProviderCircuitBreaker(
            CircuitBreakerSettings(
                enabled = true,
                failureThreshold = failureThreshold,
                openDurationMillis = 100,
            ),
            clock,
        )

    private fun coordinator(
        plan: ProviderRoutingPlan,
        breaker: ProviderCircuitBreaker,
    ): ProviderExecutionCoordinator {
        val attempt =
            executor(
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
