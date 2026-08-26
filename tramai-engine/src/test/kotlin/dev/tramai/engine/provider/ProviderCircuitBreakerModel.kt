package dev.tramai.engine.provider

import dev.tramai.engine.CircuitBreakerSettings

/**
 * Pure independent oracle for the provider circuit breaker (Epic 8.2g).
 *
 * The model is deliberately NOT derived from production internals: it reasons
 * over observable breaker facts (per-provider state, generation, failure
 * history, open deadline, live permits) and predicts what a conforming
 * [dev.tramai.engine.ProviderCircuitBreaker] must observe after every action.
 *
 * Semantics mirrored exactly from the production contract:
 *  - CLOSED(gen N, f): beforeCall -> Allowed(gen N); qualifying onFailure -> f+1,
 *    opening to OPEN(N+1, now+duration) when f+1 >= failureThreshold; onSuccess resets f.
 *  - OPEN(gen N, until T): beforeCall rejects while now < T; at exact expiry it
 *    atomically enters HALF_OPEN(N) and grants the single probe permit (gen N).
 *  - HALF_OPEN(gen N): probe in flight — beforeCall rejects; probe success closes
 *    to CLOSED(N, 0); probe qualifying failure reopens to OPEN(N+1, fresh deadline).
 *  - Generation advances ONLY on entry into OPEN; HALF_OPEN inherits the OPEN
 *    generation; no state = CLOSED(gen 0, failures 0).
 *  - Completions whose permit generation differs from the authoritative state
 *    generation are stale no-ops (they never close, reopen, or extend).
 *  - Non-qualifying failures (qualifying=false) never mutate state.
 *
 * All clock-driven via [nowMillis]; no Thread.sleep, no Instant.now.
 */
internal sealed interface CircuitBreakerState {
    val generation: Long

    data class Closed(
        override val generation: Long,
        val consecutiveFailures: Int,
    ) : CircuitBreakerState

    data class Open(
        override val generation: Long,
        val blockedUntilMillis: Long,
    ) : CircuitBreakerState

    data class HalfOpen(
        override val generation: Long,
    ) : CircuitBreakerState
}

internal sealed interface CircuitBreakerAction {
    data class AdvanceClock(val deltaMillis: Long) : CircuitBreakerAction
    data class BeforeCall(val provider: String) : CircuitBreakerAction
    data class OnSuccess(val provider: String, val generation: Long) : CircuitBreakerAction
    data class OnFailure(val provider: String, val generation: Long, val qualifying: Boolean) : CircuitBreakerAction
    data class QueryOpenUntil(val provider: String) : CircuitBreakerAction
}

/** Model-local mirror of the production admission outcome (keeps the oracle independent). */
internal sealed interface CircuitBreakerAdmissionOutcome {
    data class Allowed(val generation: Long) : CircuitBreakerAdmissionOutcome
    data class Rejected(val blockedUntilMillis: Long) : CircuitBreakerAdmissionOutcome
}

/** Result of one [CircuitBreakerAction] on the model. */
internal data class CircuitBreakerModelResult(
    val next: CircuitBreakerModel,
    /** Admission outcome of a BEFORE_CALL, null for other actions. */
    val admission: CircuitBreakerAdmissionOutcome? = null,
    /** True when THIS completion caused the transition into OPEN. */
    val opened: Boolean = false,
    /** openUntilMillis query result of a QUERY_OPEN_UNTIL, null otherwise. */
    val openUntilMillis: Long? = null,
) {
    /** Live permits (provider -> generation) held after this action. */
    val livePermits: Map<String, Long> get() = next.livePermits
}

/** Observable breaker state, comparable against the real breaker's behavior. */
internal data class CircuitBreakerSnapshot(
    val nowMillis: Long,
    val states: Map<String, CircuitBreakerState>,
) {
    fun openUntilMillis(provider: String): Long? {
        val open = states[provider] as? CircuitBreakerState.Open ?: return null
        return if (nowMillis < open.blockedUntilMillis) open.blockedUntilMillis else null
    }
}

internal data class CircuitBreakerModel(
    val settings: CircuitBreakerSettings = CircuitBreakerSettings(enabled = true, failureThreshold = 3, openDurationMillis = 1_000L),
    val nowMillis: Long = 0L,
    val states: Map<String, CircuitBreakerState> = emptyMap(),
    /** Most recently minted permit generation per provider; permits persist (staleness is a generation comparison). */
    val livePermits: Map<String, Long> = emptyMap(),
) {
    fun apply(action: CircuitBreakerAction): CircuitBreakerModelResult = when (action) {
        is CircuitBreakerAction.AdvanceClock -> {
            require(action.deltaMillis >= 0L) { "clock cannot move backwards" }
            copy(nowMillis = nowMillis + action.deltaMillis).result()
        }
        is CircuitBreakerAction.BeforeCall -> beforeCall(action.provider)
        is CircuitBreakerAction.OnSuccess -> onSuccess(action.provider, action.generation)
        is CircuitBreakerAction.OnFailure -> onFailure(action.provider, action.generation, action.qualifying)
        is CircuitBreakerAction.QueryOpenUntil -> result(openUntilMillis = openUntilMillis(action.provider))
    }

    /**
     * Generation a completion permit must carry to be non-stale for [provider], or null when no
     * live permit exists (no mint yet, or the provider's generation advanced past the last mint —
     * e.g. every permit is stale while OPEN).
     */
    fun livePermitGeneration(provider: String): Long? {
        val generation = livePermits[provider] ?: return null
        val stateGeneration = states[provider]?.generation
        return if (stateGeneration == null || stateGeneration == generation) generation else null
    }

    /** Mirrors the production expiry-aware query: null when not OPEN or the deadline has passed. */
    fun openUntilMillis(provider: String): Long? {
        val open = states[provider] as? CircuitBreakerState.Open ?: return null
        return if (nowMillis < open.blockedUntilMillis) open.blockedUntilMillis else null
    }

    fun snapshot(): CircuitBreakerSnapshot = CircuitBreakerSnapshot(nowMillis, states)

    /** Invariants that must hold after EVERY action. */
    fun invariantViolation(): String? = states.values.firstNotNullOfOrNull { state ->
        when {
            state.generation < 0L -> "negative generation"
            state is CircuitBreakerState.Closed && state.consecutiveFailures < 0 -> "negative failure count"
            state is CircuitBreakerState.Closed && state.consecutiveFailures >= settings.failureThreshold -> "closed state at/over failure threshold"
            else -> null
        }
    }

    private fun result(
        admission: CircuitBreakerAdmissionOutcome? = null,
        opened: Boolean = false,
        openUntilMillis: Long? = null,
    ) = CircuitBreakerModelResult(next = this, admission = admission, opened = opened, openUntilMillis = openUntilMillis)

    private fun beforeCall(provider: String): CircuitBreakerModelResult {
        if (!settings.enabled) {
            return copy(livePermits = livePermits + (provider to 0L)).result(admission = CircuitBreakerAdmissionOutcome.Allowed(0L))
        }
        return when (val state = states[provider]) {
            null -> copy(livePermits = livePermits + (provider to 0L)).result(admission = CircuitBreakerAdmissionOutcome.Allowed(0L))
            is CircuitBreakerState.Closed ->
                copy(livePermits = livePermits + (provider to state.generation))
                    .result(admission = CircuitBreakerAdmissionOutcome.Allowed(state.generation))
            is CircuitBreakerState.Open -> {
                if (nowMillis < state.blockedUntilMillis) {
                    result(admission = CircuitBreakerAdmissionOutcome.Rejected(state.blockedUntilMillis))
                } else {
                    // Exact expiry: atomically enter HALF_OPEN and grant the single probe.
                    copy(
                        states = states + (provider to CircuitBreakerState.HalfOpen(state.generation)),
                        livePermits = livePermits + (provider to state.generation),
                    ).result(admission = CircuitBreakerAdmissionOutcome.Allowed(state.generation))
                }
            }
            is CircuitBreakerState.HalfOpen ->
                result(admission = CircuitBreakerAdmissionOutcome.Rejected(nowMillis))
        }
    }

    private fun onSuccess(provider: String, generation: Long): CircuitBreakerModelResult {
        if (!settings.enabled) {
            return result()
        }
        val state = states[provider] ?: return result() // no state — no-op
        if (state.generation != generation) {
            return result() // stale completion from a superseded epoch
        }
        return when (state) {
            is CircuitBreakerState.Closed ->
                copy(states = states + (provider to state.copy(consecutiveFailures = 0))).result()
            is CircuitBreakerState.HalfOpen ->
                copy(states = states + (provider to CircuitBreakerState.Closed(state.generation, 0))).result()
            is CircuitBreakerState.Open -> result() // no permit minted for an Open epoch; defensive no-op
        }
    }

    private fun onFailure(provider: String, generation: Long, qualifying: Boolean): CircuitBreakerModelResult {
        if (!settings.enabled || !qualifying) {
            return result()
        }
        val now = nowMillis
        val state = states[provider] ?: CircuitBreakerState.Closed(generation, consecutiveFailures = 0)
        if (state.generation != generation) {
            return result() // stale completion — must not count, reopen, or extend
        }
        return when (state) {
            is CircuitBreakerState.Closed -> {
                val failures = state.consecutiveFailures + 1
                if (failures >= settings.failureThreshold) {
                    copy(
                        states = states + (provider to CircuitBreakerState.Open(generation + 1, now + settings.openDurationMillis)),
                    ).result(opened = true)
                } else {
                    copy(states = states + (provider to state.copy(consecutiveFailures = failures))).result()
                }
            }
            is CircuitBreakerState.HalfOpen ->
                // Probe failed: reopen immediately with a fresh deadline, regardless of the closed-state threshold.
                copy(
                    states = states + (provider to CircuitBreakerState.Open(generation + 1, now + settings.openDurationMillis)),
                ).result(opened = true)
            is CircuitBreakerState.Open -> result() // no permit minted for an Open epoch; defensive no-op
        }
    }
}
