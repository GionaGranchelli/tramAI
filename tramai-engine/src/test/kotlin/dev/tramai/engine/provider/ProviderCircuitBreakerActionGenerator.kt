package dev.tramai.engine.provider

/**
 * Deterministic 32 × 32 circuit-breaker action corpus (Epic 8.2g).
 *
 * Forced archetypes per `seed % 6` guarantee every interesting shape is present
 * in the corpus regardless of random drift:
 *  0 threshold-reached OPEN          3 stale completion after recovery
 *  1 expiry-to-HALF_OPEN + probe success close
 *  2 probe failure reopen (fresh deadline, generation +2)
 *  4 concurrent-expiry cluster (several BEFORE_CALLs at the same clock instant)
 *  5 mixed qualifying / non-qualifying failures
 *
 * The rest is a state-aware free run over a small provider alphabet
 * (alpha/beta/gamma): each candidate is checked against the running model so
 * the emitted sequence stays legal — completions only ever reference a live
 * permit (same generation as the current state); deliberately stale completions
 * are pinned by the forced archetypes, not by random drift.
 *
 * Forced prefixes assume the default fresh model (threshold 3, open duration
 * 1000ms, clock at 0), matching the house-style WorkerLifecycleActionGenerator.
 */
internal object ProviderCircuitBreakerActionGenerator {
    const val SEED_COUNT = 32L
    const val ACTION_COUNT = 32

    private val PROVIDERS = listOf("alpha", "beta", "gamma")
    private const val P = "alpha"

    fun generate(seed: Long, initial: CircuitBreakerModel = CircuitBreakerModel()): List<CircuitBreakerAction> {
        val rng = kotlin.random.Random(seed)
        val actions = ArrayList<CircuitBreakerAction>(ACTION_COUNT)
        var model = initial

        val forcedPrefix: List<CircuitBreakerAction> = when (seed % 6) {
            0L -> THRESHOLD_REACHED_OPEN
            1L -> EXPIRY_TO_HALF_OPEN_PROBE_SUCCESS
            2L -> PROBE_FAILURE_REOPEN
            3L -> STALE_COMPLETION_AFTER_RECOVERY
            4L -> CONCURRENT_EXPIRY_CLUSTER
            else -> MIXED_QUALIFYING
        }
        forcedPrefix.forEach { action ->
            actions.add(action)
            model = applyLegal(action, model)
        }

        while (actions.size < ACTION_COUNT) {
            val provider = PROVIDERS[rng.nextInt(PROVIDERS.size)]
            val liveGeneration = model.livePermitGeneration(provider)
            val candidates = ArrayList<CircuitBreakerAction>(5)
            candidates.add(CircuitBreakerAction.AdvanceClock(1L + rng.nextInt(3_000)))
            candidates.add(CircuitBreakerAction.BeforeCall(provider))
            candidates.add(CircuitBreakerAction.QueryOpenUntil(provider))
            if (liveGeneration != null) {
                candidates.add(CircuitBreakerAction.OnSuccess(provider, liveGeneration))
                candidates.add(CircuitBreakerAction.OnFailure(provider, liveGeneration, qualifying = rng.nextBoolean()))
            }
            val action = candidates[rng.nextInt(candidates.size)]
            actions.add(action)
            model = applyLegal(action, model)
        }
        return actions
    }

    /** Applies the action to the model so the emitted sequence stays in sync. */
    private fun applyLegal(action: CircuitBreakerAction, model: CircuitBreakerModel): CircuitBreakerModel =
        model.apply(action).next

    // ------------------------------------------------------------------ forced archetypes

    /** Seed % 6 == 0 — qualifying failures accumulate to the threshold and OPEN. */
    private val THRESHOLD_REACHED_OPEN: List<CircuitBreakerAction> = listOf(
        CircuitBreakerAction.BeforeCall(P),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // failures 3 >= threshold -> OPEN(1, 1000)
        CircuitBreakerAction.QueryOpenUntil(P), // 1000
    )

    /** Seed % 6 == 1 — expiry atomically enters HALF_OPEN with a single probe; probe success closes. */
    private val EXPIRY_TO_HALF_OPEN_PROBE_SUCCESS: List<CircuitBreakerAction> = listOf(
        CircuitBreakerAction.BeforeCall(P),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // OPEN(1, 1000)
        CircuitBreakerAction.AdvanceClock(1_000L), // exact expiry instant
        CircuitBreakerAction.BeforeCall(P), // -> HALF_OPEN(1), exactly ONE caller Allowed(1)
        CircuitBreakerAction.BeforeCall(P), // probe in flight -> Rejected
        CircuitBreakerAction.OnSuccess(P, 1L), // probe success -> CLOSED(1, 0)
    )

    /** Seed % 6 == 2 — probe failure reopens immediately with a fresh deadline. */
    private val PROBE_FAILURE_REOPEN: List<CircuitBreakerAction> = listOf(
        CircuitBreakerAction.BeforeCall(P),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // OPEN(1, 1000)
        CircuitBreakerAction.AdvanceClock(1_000L),
        CircuitBreakerAction.BeforeCall(P), // -> HALF_OPEN(1), probe Allowed(1)
        CircuitBreakerAction.OnFailure(P, 1L, qualifying = true), // probe failure -> OPEN(2, 2000)
        CircuitBreakerAction.QueryOpenUntil(P), // 2000
    )

    /** Seed % 6 == 3 — completions from a superseded generation after recovery are no-ops. */
    private val STALE_COMPLETION_AFTER_RECOVERY: List<CircuitBreakerAction> = listOf(
        CircuitBreakerAction.BeforeCall(P),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // OPEN(1, 1000)
        CircuitBreakerAction.AdvanceClock(1_000L),
        CircuitBreakerAction.BeforeCall(P), // -> HALF_OPEN(1), probe Allowed(1)
        CircuitBreakerAction.OnSuccess(P, 1L), // probe success -> CLOSED(1, 0), recovered
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // stale gen-0 completion -> no-op
        CircuitBreakerAction.OnSuccess(P, 0L), // stale gen-0 completion -> no-op
    )

    /** Seed % 6 == 4 — several BEFORE_CALLs at the exact expiry instant: one probe, all others rejected. */
    private val CONCURRENT_EXPIRY_CLUSTER: List<CircuitBreakerAction> = listOf(
        CircuitBreakerAction.BeforeCall(P),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // OPEN(1, 1000)
        CircuitBreakerAction.AdvanceClock(1_000L), // exact expiry instant
        CircuitBreakerAction.BeforeCall(P), // -> HALF_OPEN(1), exactly ONE caller Allowed(1)
        CircuitBreakerAction.BeforeCall(P), // Rejected
        CircuitBreakerAction.BeforeCall(P), // Rejected
        CircuitBreakerAction.BeforeCall(P), // Rejected
        CircuitBreakerAction.OnSuccess(P, 1L), // probe success -> CLOSED(1, 0)
    )

    /** Seed % 6 == 5 — non-qualifying failures never count toward the threshold. */
    private val MIXED_QUALIFYING: List<CircuitBreakerAction> = listOf(
        CircuitBreakerAction.BeforeCall(P),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // failures = 1
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = false), // ignored
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // failures = 2
        CircuitBreakerAction.OnSuccess(P, 0L), // failures reset to 0
        CircuitBreakerAction.BeforeCall(P),
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = false), // ignored
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // failures = 1
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = false), // ignored
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // failures = 2
        CircuitBreakerAction.OnFailure(P, 0L, qualifying = true), // failures = 3 -> OPEN(1, 1000)
    )
}
