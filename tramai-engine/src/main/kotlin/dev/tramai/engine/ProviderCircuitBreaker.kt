package dev.tramai.engine

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException

/**
 * Result of a circuit-breaker admission attempt for one provider.
 *
 * [Allowed] carries the [CircuitBreakerPermit] that the caller must present when
 * reporting the outcome of the admitted invocation. [Rejected] reports the
 * caller was not admitted; [blockedUntilMillis] is the earliest instant at
 * which the caller may retry — either the expiry of the current OPEN epoch
 * (provider still open) or, while a HALF_OPEN probe is in flight, the current
 * instant (callers may treat it as "retry after this instant").
 */
internal sealed interface CircuitBreakerAdmission {
    data class Allowed(val permit: CircuitBreakerPermit) : CircuitBreakerAdmission
    data class Rejected(val blockedUntilMillis: Long) : CircuitBreakerAdmission
}

/**
 * Ownership of one admitted invocation against a provider's circuit.
 *
 * The [generation] identifies the circuit epoch the invocation was admitted
 * under. Completions may mutate breaker state ONLY if their permit's generation
 * is still the authoritative generation for that provider — a completion from a
 * superseded epoch (pre-OPEN call finishing after OPEN, stale HALF_OPEN probe,
 * etc.) is ignored. This makes stale-authority rejection structural rather than
 * a special case: the breaker never adopts the current generation on behalf of
 * a caller that only possesses an old one.
 */
internal data class CircuitBreakerPermit(
    val providerId: String,
    val generation: Long,
)

/**
 * Engine-owned circuit breaker for provider routing (Epic 8.2g).
 *
 * Lifecycle per provider:
 *
 * ```
 * CLOSED(generation=N)
 *   │ qualifying failures >= failureThreshold
 *   ▼
 * OPEN(generation=N+1, blockedUntil=now+duration)
 *   │ now < blockedUntil → Rejected
 *   │ now >= blockedUntil → atomic transition (exactly ONE caller)
 *   ▼
 * HALF_OPEN(generation=N+1, probe in flight)
 *   ├─ probe success ────────────────► CLOSED(generation=N+1)
 *   └─ probe qualifying failure ─────► OPEN(generation=N+2, fresh deadline)
 * ```
 *
 * Generation advances on every entry into OPEN (CLOSED→OPEN and
 * HALF_OPEN→OPEN); HALF_OPEN inherits the OPEN generation it was entered from.
 * A permit minted under generation N is valid only while N is the provider's
 * current generation. Stale completions (success or failure) never close,
 * reopen, extend, or otherwise mutate a newer generation.
 *
 * Failure classification is deliberately narrow: only [TimeoutException] and
 * retryable [ProviderException] count as qualifying failures. Cancellation,
 * DLP, model-registry, policy, and arbitrary errors never become circuit
 * failures.
 */
internal open class ProviderCircuitBreaker(
    private val settings: CircuitBreakerSettings,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val states = mutableMapOf<String, CircuitState>()

    /**
     * Attempts to admit one invocation for [providerId].
     *
     * The OPEN → HALF_OPEN transition at exact expiry is atomic: exactly one
     * concurrent caller observes [CircuitBreakerAdmission.Allowed] with the probe
     * permit; every other caller in the same instant observes [Rejected].
     */
    @Synchronized
    open fun beforeCall(providerId: String): CircuitBreakerAdmission {
        if (!settings.enabled) {
            return CircuitBreakerAdmission.Allowed(CircuitBreakerPermit(providerId, 0))
        }

        val state = states[providerId] ?: return CircuitBreakerAdmission.Allowed(CircuitBreakerPermit(providerId, 0))
        val now = clockMillis()
        return when (state) {
            is CircuitState.Closed -> CircuitBreakerAdmission.Allowed(CircuitBreakerPermit(providerId, state.generation))
            is CircuitState.Open -> {
                if (now < state.blockedUntilMillis) {
                    CircuitBreakerAdmission.Rejected(state.blockedUntilMillis)
                } else {
                    // Exact expiry: atomically enter HALF_OPEN and grant the single probe.
                    states[providerId] = CircuitState.HalfOpen(state.generation)
                    CircuitBreakerAdmission.Allowed(CircuitBreakerPermit(providerId, state.generation))
                }
            }
            is CircuitState.HalfOpen -> {
                // Probe in flight: everyone else is rejected. Retry after it resolves.
                CircuitBreakerAdmission.Rejected(now)
            }
        }
    }

    /** Current open-deadline for [providerId], or null when not open (or expired). */
    @Synchronized
    open fun openUntilMillis(providerId: String): Long? {
        if (!settings.enabled) {
            return null
        }
        val state = states[providerId] ?: return null
        val openUntil = (state as? CircuitState.Open)?.blockedUntilMillis ?: return null
        return if (clockMillis() < openUntil) openUntil else null
    }

    /**
     * Records a successful completion for [permit].
     *
     * Only a permit whose generation is still authoritative may reset failure
     * history (CLOSED) or close the circuit (HALF_OPEN probe success). Stale
     * completions are no-ops.
     */
    @Synchronized
    open fun onSuccess(permit: CircuitBreakerPermit) {
        if (!settings.enabled) {
            return
        }
        val state = states[permit.providerId] ?: return
        if (state.generation != permit.generation) {
            return // stale completion from a superseded epoch — no authority.
        }
        when (state) {
            is CircuitState.Closed -> states[permit.providerId] = CircuitState.Closed(state.generation, consecutiveFailures = 0)
            is CircuitState.HalfOpen -> states[permit.providerId] = CircuitState.Closed(state.generation, consecutiveFailures = 0)
            is CircuitState.Open -> Unit // no permit is ever minted for an Open epoch; defensive no-op.
        }
    }

    /**
     * Records a failure completion for [permit].
     *
     * @return true when THIS completion caused the transition into OPEN (the
     * caller emits the CIRCUIT_OPENED runtime event exactly once per transition).
     * Stale completions return false and never mutate the current generation.
     */
    @Synchronized
    open fun onFailure(
        permit: CircuitBreakerPermit,
        error: Throwable,
    ): Boolean {
        if (!settings.enabled) {
            return false
        }
        val now = clockMillis()
        val state = states[permit.providerId] ?: CircuitState.Closed(permit.generation, consecutiveFailures = 0)
        if (state.generation != permit.generation) {
            return false // stale completion — must not count, reopen, or extend.
        }
        if (!isCircuitBreakingFailure(error)) {
            // Neutral terminal outcome. Never counts as a breaker failure, but
            // if this was the authoritative HALF_OPEN probe the slot must be
            // released or recovery strands forever: re-enter OPEN with a fresh
            // deadline and an ADVANCED generation so this permit can never
            // regain authority. No CIRCUIT_OPENED event (opened=false).
            if (state is CircuitState.HalfOpen) {
                states[permit.providerId] = CircuitState.Open(permit.generation + 1, now + settings.openDurationMillis)
            }
            return false
        }
        return when (state) {
            is CircuitState.Closed -> {
                val failures = state.consecutiveFailures + 1
                if (failures >= settings.failureThreshold) {
                    states[permit.providerId] = CircuitState.Open(permit.generation + 1, now + settings.openDurationMillis)
                    true
                } else {
                    states[permit.providerId] = CircuitState.Closed(permit.generation, failures)
                    false
                }
            }
            is CircuitState.HalfOpen -> {
                // Probe failed: reopen immediately with a fresh deadline, regardless
                // of the closed-state threshold.
                states[permit.providerId] = CircuitState.Open(permit.generation + 1, now + settings.openDurationMillis)
                true
            }
            is CircuitState.Open -> false // no permit is ever minted for an Open epoch; defensive no-op.
        }
    }

    /**
     * Records a neutral/abandoned completion for [permit] — a terminal outcome
     * that is neither success nor a qualifying provider failure (caller
     * cancellation, DLP/sanitizer failure, policy/model-registry rejection,
     * non-retryable provider error, token-budget exhaustion).
     *
     * A neutral outcome must NEVER be treated as a breaker failure, but it must
     * also not strand the circuit: if [permit] was the authoritative HALF_OPEN
     * probe, releasing it is mandatory or every later caller is rejected
     * forever (HALF_OPEN has no expiry of its own). The probe is released by
     * re-entering OPEN with a FRESH deadline and an ADVANCED generation, so
     * the abandoned permit can never regain authority — a replacement probe
     * is minted under the new generation and the old permit is permanently
     * stale.
     *
     * For non-probe permits (CLOSED-epoch admissions) a neutral outcome is a
     * no-op: it does not count toward the threshold and does not reset the
     * failure history. Stale permits are no-ops.
     */
    @Synchronized
    open fun onAbandoned(permit: CircuitBreakerPermit) {
        if (!settings.enabled) {
            return
        }
        val state = states[permit.providerId] ?: return
        if (state.generation != permit.generation) {
            return // stale permit — no authority.
        }
        if (state is CircuitState.HalfOpen) {
            states[permit.providerId] = CircuitState.Open(permit.generation + 1, clockMillis() + settings.openDurationMillis)
        }
    }

    private fun isCircuitBreakingFailure(error: Throwable): Boolean = when (error) {
        is TimeoutException -> true
        is ProviderException -> error.retryable
        else -> false
    }
}

private sealed interface CircuitState {
    val generation: Long

    data class Closed(
        override val generation: Long,
        val consecutiveFailures: Int,
    ) : CircuitState

    data class Open(
        override val generation: Long,
        val blockedUntilMillis: Long,
    ) : CircuitState

    data class HalfOpen(
        override val generation: Long,
    ) : CircuitState
}
