package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.security.DlpInspectionException
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
 * Section H secondary regressions + concurrency discriminators C1-C4 for Epic 8.2g.
 *
 * Complements [ProviderCircuitBreakerLifecycleDiscriminatorTest] (P0, untouched).
 * Every test is deterministic: the clock is an injectable `{ now }` lambda, all
 * concurrency goes through runBlocking + async/awaitAll, and there is no
 * Thread.sleep / Instant.now / delay anywhere.
 *
 * The breaker API under test:
 * - beforeCall → Allowed(permit) | Rejected(blockedUntilMillis), with the
 *   OPEN → HALF_OPEN transition at exact expiry being atomic (one probe).
 * - onSuccess(permit) / onFailure(permit, error) mutate state only when the
 *   permit's generation is still authoritative.
 */
class ProviderCircuitBreakerSecondaryRegressionTest {

    // ------------------------------------------------------------ Section H-1b

    @Test
    fun `H1b stale pre-OPEN success cannot close an in-flight HALF_OPEN probe`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            // A and B admitted while CLOSED; A's failure opens the circuit.
            val permitA = admit(breaker, "primary")
            val permitB = admit(breaker, "primary")
            breaker.onFailure(permitA, ProviderException("A failed", retryable = true))
            assertThat(blockedUntil(breaker, "primary")).isEqualTo(100)

            // At exact expiry the probe is admitted (HALF_OPEN). B's stale
            // CLOSED-epoch success arrives while the probe is STILL IN FLIGHT —
            // it must NOT close the circuit.
            now = 100
            val probe = admit(breaker, "primary")
            breaker.onSuccess(permitB)
            assertThat(breaker.beforeCall("primary")).isInstanceOf(CircuitBreakerAdmission.Rejected::class.java)

            // The real probe failure still reopens with a fresh deadline.
            breaker.onFailure(probe, ProviderException("probe failed", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(now + 100)
        }
    }

    // ------------------------------------------------------------ Section H-1

    @Test
    fun `H1 HALF_OPEN concurrent success admits exactly one probe and closes the circuit`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            // Threshold 1 → one qualifying failure opens the breaker until t=100.
            breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))
            assertThat(blockedUntil(breaker, "primary")).isEqualTo(100)

            // At exact expiry, N concurrent callers: the atomic transition must
            // mint exactly ONE probe permit and reject every other caller.
            now = 100
            val admissions = supervisorScope {
                (1..16).map { async { breaker.beforeCall("primary") } }.awaitAll()
            }
            val allowed = admissions.filterIsInstance<CircuitBreakerAdmission.Allowed>()
            assertThat(allowed).hasSize(1)
            assertThat(admissions.filterIsInstance<CircuitBreakerAdmission.Rejected>()).hasSize(15)

            // Probe success closes the circuit: no deadline, subsequent call admitted.
            breaker.onSuccess(allowed.single().permit)
            assertThat(breaker.openUntilMillis("primary")).isNull()
            assertThat(breaker.beforeCall("primary")).isInstanceOf(CircuitBreakerAdmission.Allowed::class.java)
        }
    }

    // ------------------------------------------------------------ Section H-2

    @Test
    fun `H2 stale success after successful newer recovery cannot disturb the closed state`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 2, openDurationMillis = 100), { now })

            // Stale CLOSED-epoch permit; two qualifying failures open the breaker.
            val stale = admit(breaker, "primary")
            breaker.onFailure(admit(breaker, "primary"), ProviderException("a", retryable = true))
            breaker.onFailure(admit(breaker, "primary"), ProviderException("b", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // Recovery: HALF_OPEN probe succeeds → CLOSED in the NEW generation.
            now = 100
            breaker.onSuccess(admit(breaker, "primary"))
            assertThat(breaker.openUntilMillis("primary")).isNull()

            // The stale pre-OPEN success arrives late; its generation is no longer
            // authoritative, so it must be a no-op on the closed new-generation state.
            breaker.onSuccess(stale)

            // A fresh qualifying failure still counts from 1 (threshold 2 not yet
            // reached): the stale success neither reset nor advanced the counter.
            assertThat(breaker.onFailure(admit(breaker, "primary"), ProviderException("c", retryable = true))).isFalse()
            assertThat(breaker.openUntilMillis("primary")).isNull()
            assertThat(breaker.onFailure(admit(breaker, "primary"), ProviderException("d", retryable = true))).isTrue()
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(now + 100)
        }
    }

    // ------------------------------------------------------------ Section H-3

    @Test
    fun `H3 stale failure after successful newer recovery cannot reopen the circuit`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            // Stale CLOSED-epoch permit; one qualifying failure opens the breaker.
            val stale = admit(breaker, "primary")
            breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // Recovery: probe succeeds → CLOSED in the NEW generation.
            now = 100
            breaker.onSuccess(admit(breaker, "primary"))
            assertThat(breaker.openUntilMillis("primary")).isNull()

            // Stale pre-OPEN failure arrives late → must NOT reopen the breaker.
            assertThat(breaker.onFailure(stale, ProviderException("stale", retryable = true))).isFalse()
            assertThat(breaker.openUntilMillis("primary")).isNull()
        }
    }

    // ------------------------------------------------------------ Section H-5

    @Test
    fun `H5 sync coordinator HALF_OPEN probe failure reopens with fresh deadline`() {
        runBlocking {
            var now = 0L
            var calls = 0
            val observation = RecordingObservation()
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
            val coordinator = coordinator(
                plan = plan(FakeProvider {
                    calls++
                    throw ProviderException("always down", retryable = true)
                }),
                breaker = breaker,
                observation = observation,
            )

            // First call fails retryably -> OPEN until t=100.
            assertThat(runCatching { coordinator.execute(executionRequest()) }.exceptionOrNull())
                .isInstanceOf(ProviderException::class.java)
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)
            assertThat(observation.events.map { it.first }.filter { it == "tramai.circuit.opened" }).hasSize(1)

            // At exact expiry the sync coordinator is admitted as the HALF_OPEN
            // probe; its qualifying failure must immediately reopen with a fresh
            // deadline (now + openDuration), NOT get stuck in HALF_OPEN.
            now = 100
            assertThat(runCatching { coordinator.execute(executionRequest()) }.exceptionOrNull())
                .isInstanceOf(ProviderException::class.java)
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(now + 100)
            // A qualifying probe failure is a breaker TRIP: exactly one more
            // CIRCUIT_OPENED event. (An abandoned/neutral probe would reopen
            // WITHOUT the event — this discriminates the two paths.)
            assertThat(observation.events.map { it.first }.filter { it == "tramai.circuit.opened" }).hasSize(2)

            // A call at the new deadline is again admitted (probe cycles continue).
            now = 200
            assertThat(runCatching { coordinator.execute(executionRequest()) }.exceptionOrNull())
                .isInstanceOf(ProviderException::class.java)
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(now + 100)
            assertThat(observation.events.map { it.first }.filter { it == "tramai.circuit.opened" }).hasSize(3)
        }
    }

    // ------------------------------------------------------------ Section H-8/H-9/H-10

    @Test
    fun `H8 neutral HALF_OPEN failure cannot strand the circuit`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            // Threshold 1: one qualifying failure opens the circuit until t=100.
            breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // At exact expiry the probe is admitted; it then terminates with a
            // NON-RETRYABLE ProviderException — a neutral outcome that must
            // release probe ownership (reopen with fresh deadline) rather than
            // strand the circuit in HALF_OPEN.
            now = 100
            val probe = admit(breaker, "primary")
            assertThat(breaker.onFailure(probe, ProviderException("permanent", retryable = false))).isFalse()
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(200)

            // A replacement probe is admitted at the new expiry.
            now = 200
            val replacement = admit(breaker, "primary")
            breaker.onSuccess(replacement)
            assertThat(breaker.openUntilMillis("primary")).isNull()
        }
    }

    @Test
    fun `H9 abandoned HALF_OPEN probe is released and a replacement probe is eventually admitted`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // Probe admitted at exact expiry and abandoned (the caller was
            // cancelled / a pre-invocation gate failed). Cancellation must NOT
            // count as a breaker failure — but recovery must remain possible.
            now = 100
            val probe = admit(breaker, "primary")
            breaker.onAbandoned(probe)
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(200)

            // Next expiry: a fresh probe can be admitted.
            now = 200
            val replacement = admit(breaker, "primary")
            breaker.onSuccess(replacement)
            assertThat(breaker.openUntilMillis("primary")).isNull()
        }
    }

    @Test
    fun `H10 abandoned probe is fenced after replacement recovery begins`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))
            now = 100
            val abandoned = admit(breaker, "primary") // probe gen 1
            breaker.onAbandoned(abandoned) // -> OPEN(gen 2, 200)
            now = 200
            val replacement = (breaker.beforeCall("primary") as CircuitBreakerAdmission.Allowed).permit // probe gen 2

            // The abandoned permit can never regain authority: stale success
            // and stale qualifying failure while the replacement probe is in
            // flight must not close/reopen/reset it.
            breaker.onSuccess(abandoned)
            breaker.onFailure(abandoned, ProviderException("stale", retryable = true))
            assertThat(breaker.beforeCall("primary")).isInstanceOf(dev.tramai.engine.CircuitBreakerAdmission.Rejected::class.java)

            // Only the replacement probe resolves the circuit.
            breaker.onSuccess(replacement)
            assertThat(breaker.openUntilMillis("primary")).isNull()
        }
    }

    // ------------------------------------------------------------ Section H-12

    @Test
    fun `H12 sync coordinator DLP-neutral HALF_OPEN probe cannot strand recovery`() {
        runBlocking {
            var now = 0L
            var calls = 0
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
            val provider = FakeProvider {
                calls++
                if (calls == 1 || calls == 3) throw ProviderException("down", retryable = true) else ModelResponse("ok")
            }
            val dlp = DlpInspectionException("dlp blocked")
            val coordinator = coordinator(
                plan = plan(provider),
                breaker = breaker,
                sanitizer = ProviderResponseSanitizer { _, _, _, _, _, _, _ -> throw dlp },
            )

            // First call fails retryably -> OPEN until t=100.
            assertThat(runCatching { coordinator.execute(executionRequest()) }.exceptionOrNull())
                .isInstanceOf(ProviderException::class.java)
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // At exact expiry the probe is admitted; the provider succeeds but
            // the response sanitizer rejects with DLP — a neutral terminal
            // outcome that must release probe ownership (reopen with a fresh
            // deadline) instead of stranding the sync recovery.
            now = 100
            assertThat(runCatching { coordinator.execute(executionRequest()) }.exceptionOrNull())
                .isInstanceOf(DlpInspectionException::class.java)
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(200)

            // At the new expiry a call is again admitted as the next probe; its
            // qualifying provider failure reopens with yet another fresh deadline.
            now = 200
            assertThat(runCatching { coordinator.execute(executionRequest()) }.exceptionOrNull())
                .isInstanceOf(ProviderException::class.java)
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(300)
        }
    }

    // ------------------------------------------------------------ Section H-4

    @Test
    fun `H4 sync coordinator and breaker-level lifecycle agree on OPEN then CLOSE recovery`() {
        runBlocking {
            var now = 0L
            var calls = 0
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
            val coordinator = coordinator(
                plan = plan(FakeProvider {
                    calls++
                    if (calls == 1) throw ProviderException("down", retryable = true) else ModelResponse("ok")
                }),
                breaker = breaker,
            )

            // Sync path: first call fails retryably → breaker OPEN until t=100.
            assertThat(runCatching { coordinator.execute(executionRequest()) }.exceptionOrNull())
                .isInstanceOf(ProviderException::class.java)
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // After expiry the next call is the HALF_OPEN probe; it succeeds → CLOSED.
            now = 100
            assertThat(coordinator.execute(executionRequest()).response.content).isEqualTo("ok")
            assertThat(breaker.openUntilMillis("primary")).isNull()

            // Breaker-level parity: the identical beforeCall/onFailure/onSuccess
            // action sequence (which is what the streaming coordinator routes
            // through the same breaker) yields the identical observable state.
            val parity = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })
            assertThat(parity.onFailure(admit(parity, "primary"), ProviderException("down", retryable = true))).isTrue()
            val parityOpenUntil = parity.openUntilMillis("primary")
            assertThat(parityOpenUntil).isEqualTo(now + 100)
            now = parityOpenUntil!! // advance to parity's expiry → probe admitted
            parity.onSuccess(admit(parity, "primary"))
            assertThat(parity.openUntilMillis("primary")).isNull()

            // Both recovered circuits react identically to a fresh qualifying failure.
            assertThat(breaker.onFailure(admit(breaker, "primary"), ProviderException("again", retryable = true))).isTrue()
            assertThat(parity.onFailure(admit(parity, "primary"), ProviderException("again", retryable = true))).isTrue()
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(parity.openUntilMillis("primary"))
        }
    }

    // ------------------------------------------------------------ C1

    @Test
    fun `C1 atomic expiry admits exactly one HALF_OPEN probe under 16 concurrent callers`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // All 16 callers hit the exact expiry instant in parallel; the
            // OPEN → HALF_OPEN transition must remain atomic under contention.
            now = 100
            val admissions = supervisorScope {
                (1..16).map { async { breaker.beforeCall("primary") } }.awaitAll()
            }
            assertThat(admissions.filterIsInstance<CircuitBreakerAdmission.Allowed>()).hasSize(1)
            assertThat(admissions.filterIsInstance<CircuitBreakerAdmission.Rejected>()).hasSize(15)
        }
    }

    // ------------------------------------------------------------ C2

    @Test
    fun `C2 concurrent stale completions cannot mutate the open deadline or state`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            // 8 permits minted while CLOSED, then the breaker opens until t=100.
            val stalePermits = (1..8).map { admit(breaker, "primary") }
            breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)

            // Fire all stale completions concurrently: failures must all be
            // non-authoritative (false) and none may close or reopen.
            val failureSignals = supervisorScope {
                stalePermits.map { async { breaker.onFailure(it, ProviderException("stale", retryable = true)) } }.awaitAll()
            }
            assertThat(failureSignals).containsOnly(false)
            supervisorScope {
                stalePermits.map { async { breaker.onSuccess(it) } }.awaitAll()
            }

            // Original deadline untouched, breaker still OPEN — no reopen, no close.
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(100)
        }
    }

    // ------------------------------------------------------------ C3

    @Test
    fun `C3 concurrent probe and competing callers reopen exactly once on probe failure`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            // Stale CLOSED-epoch permits; open the breaker, then admit the probe.
            val staleA = admit(breaker, "primary")
            val staleB = admit(breaker, "primary")
            breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))
            now = 100
            val probe = admit(breaker, "primary")

            // While the probe is in flight every competing caller is rejected.
            val competing = supervisorScope {
                (1..8).map { async { breaker.beforeCall("primary") } }.awaitAll()
            }
            assertThat(competing).allMatch { it is CircuitBreakerAdmission.Rejected }

            // Probe failure races stale completions: exactly ONE onFailure is
            // authoritative (the CIRCUIT_OPENED-equivalent signal), the rest no-op.
            val signals = supervisorScope {
                listOf(
                    async { breaker.onFailure(probe, ProviderException("probe failed", retryable = true)) },
                    async { breaker.onFailure(staleA, ProviderException("stale", retryable = true)) },
                    async { breaker.onSuccess(staleB); false },
                ).awaitAll()
            }
            assertThat(signals.count { it }).isEqualTo(1)
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(now + 100)
        }
    }

    // ------------------------------------------------------------ C4

    @Test
    fun `C4 generation strictly increases across rapid cycles and stale permits are ignored`() {
        runBlocking {
            var now = 0L
            val breaker = ProviderCircuitBreaker(CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 100), { now })

            // One stale permit per cycle, collected before that cycle opens.
            val stalePermits = mutableListOf<CircuitBreakerPermit>()
            val openGenerations = mutableListOf<Long>()

            repeat(4) {
                stalePermits += admit(breaker, "primary") // pre-OPEN permit for THIS cycle
                assertThat(breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))).isTrue()
                now += 100 // advance to expiry → HALF_OPEN probe inherits the OPEN generation
                val probe = admit(breaker, "primary")
                openGenerations += probe.generation
                breaker.onSuccess(probe) // probe success → CLOSED in that generation
                now += 1
            }

            // Every OPEN entry advanced the generation: 1, 2, 3, 4 — strictly increasing.
            assertThat(openGenerations).isEqualTo(listOf(1L, 2L, 3L, 4L))

            // Stale permits from every earlier cycle are ignored: no reopen, no close.
            for (stale in stalePermits) {
                assertThat(breaker.onFailure(stale, ProviderException("stale", retryable = true))).isFalse()
                breaker.onSuccess(stale)
            }
            assertThat(breaker.openUntilMillis("primary")).isNull()

            // The breaker still behaves as a healthy CLOSED circuit in the final
            // generation: one fresh failure crosses threshold 1 and reopens.
            assertThat(breaker.onFailure(admit(breaker, "primary"), ProviderException("down", retryable = true))).isTrue()
            assertThat(breaker.openUntilMillis("primary")).isEqualTo(now + 100)
        }
    }

    // ------------------------------------------------------------ adapters

    /** Acquires a permit for [providerId] exactly as production does (beforeCall → Allowed). */
    private fun admit(breaker: ProviderCircuitBreaker, providerId: String): CircuitBreakerPermit =
        (breaker.beforeCall(providerId) as CircuitBreakerAdmission.Allowed).permit

    /** Returns the blocked-until millis when [providerId] is rejected, else null (admitted). */
    private fun blockedUntil(breaker: ProviderCircuitBreaker, providerId: String): Long? =
        (breaker.beforeCall(providerId) as? CircuitBreakerAdmission.Rejected)?.blockedUntilMillis

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

    private fun coordinator(
        plan: ProviderRoutingPlan,
        breaker: ProviderCircuitBreaker,
        sanitizer: ProviderResponseSanitizer = ProviderResponseSanitizer { response, _, _, _, _, _, _ -> response },
        observation: RecordingObservation = RecordingObservation(),
    ): ProviderExecutionCoordinator {
        val attempt = executor(
            observation = observation,
            circuitBreaker = breaker,
            sanitizer = sanitizer,
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
