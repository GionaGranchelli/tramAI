package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.security.DlpInspectionException
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.ProviderCircuitBreaker
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CancellationException
import kotlin.test.Test

private val PROPERTY_PROVIDERS = listOf("alpha", "beta", "gamma")
private val PROPERTY_P = "alpha"

/** Errors that must never count toward the circuit (P6). */
private val NON_QUALIFYING_ERRORS: List<Throwable> = listOf(
    ProviderException("non-retryable provider failure", retryable = false),
    DlpInspectionException("dlp inspection failed"),
    IllegalStateException("arbitrary runtime failure"),
    CancellationException("cancelled"),
)

/** Provider of a completion action (OnSuccess/OnFailure/OnAbandoned), null otherwise. */
private val CircuitBreakerAction.completionProvider: String?
    get() = when (this) {
        is CircuitBreakerAction.OnSuccess -> provider
        is CircuitBreakerAction.OnFailure -> provider
        is CircuitBreakerAction.OnAbandoned -> provider
        else -> null
    }

/** Generation of a completion action (OnSuccess/OnFailure/OnAbandoned), null otherwise. */
private val CircuitBreakerAction.completionGeneration: Long?
    get() = when (this) {
        is CircuitBreakerAction.OnSuccess -> generation
        is CircuitBreakerAction.OnFailure -> generation
        is CircuitBreakerAction.OnAbandoned -> generation
        else -> null
    }

/**
 * Epic 8.2g — circuit-breaker lifecycle property suite (P1–P12).
 *
 * The pure [CircuitBreakerModel] is the authoritative oracle. For every seed of
 * the deterministic 32×32 action corpus (and the fixed scripts below), every
 * action is applied to BOTH the model and the real [ProviderCircuitBreaker]
 * under one shared injectable clock, and the per-action results plus the
 * post-action observable state (openUntilMillis per provider) must agree.
 *
 * Deterministic only: the clock is `var now = 0L; { now }`; there is no
 * Thread.sleep, no Instant.now, no delay.
 */
class ProviderCircuitBreakerLifecyclePropertyTest {

    private data class StepTrace(
        val step: Int,
        val action: CircuitBreakerAction,
        val modelBefore: CircuitBreakerModel,
        val modelResult: CircuitBreakerModelResult,
        val realAdmission: CircuitBreakerAdmission?,
        val realOpened: Boolean?,
        val realQueriedOpenUntil: Long?,
        val realOpenUntilBefore: Map<String, Long?>,
        val realOpenUntilAfter: Map<String, Long?>,
    )

    /**
     * Drives [actions] through the model and the real breaker under one shared
     * clock, asserting after EVERY action:
     *  - model admission outcome == real admission outcome (permit generation /
     *    blockedUntil);
     *  - model OPEN-transition flag == real onFailure return value;
     *  - model QueryOpenUntil result == real openUntilMillis result;
     *  - model snapshot == real observable state (openUntilMillis per provider);
     *  - model invariants hold.
     */
    private fun driveScript(
        label: String,
        actions: List<CircuitBreakerAction>,
        model: CircuitBreakerModel = CircuitBreakerModel(),
        breakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(enabled = true, failureThreshold = 3, openDurationMillis = 1_000L),
        nonQualifyingError: (Int) -> Throwable = { NON_QUALIFYING_ERRORS[it % NON_QUALIFYING_ERRORS.size] },
    ): List<StepTrace> {
        var now = model.nowMillis
        val breaker = ProviderCircuitBreaker(breakerSettings, clockMillis = { now })
        var current = model
        val traces = ArrayList<StepTrace>(actions.size)
        actions.forEachIndexed { step, action ->
            val modelBefore = current
            val predicted = current.apply(action)
            current = predicted.next
            now = current.nowMillis

            val realOpenUntilBefore = PROPERTY_PROVIDERS.associateWith { breaker.openUntilMillis(it) }
            var realAdmission: CircuitBreakerAdmission? = null
            var realOpened: Boolean? = null
            var realQueried: Long? = null
            when (action) {
                is CircuitBreakerAction.AdvanceClock -> Unit
                is CircuitBreakerAction.BeforeCall -> realAdmission = breaker.beforeCall(action.provider)
                is CircuitBreakerAction.OnSuccess ->
                    breaker.onSuccess(CircuitBreakerPermit(action.provider, action.generation))
                is CircuitBreakerAction.OnFailure ->
                    realOpened = breaker.onFailure(
                        CircuitBreakerPermit(action.provider, action.generation),
                        if (action.qualifying) TimeoutException("qualifying provider failure") else nonQualifyingError(step),
                    )
                is CircuitBreakerAction.OnAbandoned ->
                    breaker.onAbandoned(CircuitBreakerPermit(action.provider, action.generation))
                is CircuitBreakerAction.QueryOpenUntil -> realQueried = breaker.openUntilMillis(action.provider)
            }
            val realOpenUntilAfter = PROPERTY_PROVIDERS.associateWith { breaker.openUntilMillis(it) }

            val message = "$label step=$step action=$action\nmodelBefore=$modelBefore"
            when (val expected = predicted.admission) {
                is CircuitBreakerAdmissionOutcome.Allowed -> {
                    val provider = (action as CircuitBreakerAction.BeforeCall).provider
                    assertThat(realAdmission)
                        .withFailMessage("$message\nadmission expected Allowed(gen ${expected.generation}) but was $realAdmission")
                        .isEqualTo(CircuitBreakerAdmission.Allowed(CircuitBreakerPermit(provider, expected.generation)))
                }
                is CircuitBreakerAdmissionOutcome.Rejected -> {
                    val provider = (action as CircuitBreakerAction.BeforeCall).provider
                    assertThat(realAdmission)
                        .withFailMessage("$message\nadmission expected Rejected(${expected.blockedUntilMillis}) but was $realAdmission")
                        .isEqualTo(CircuitBreakerAdmission.Rejected(expected.blockedUntilMillis))
                }
                null -> assertThat(realAdmission)
                    .withFailMessage("$message\nunexpected admission $realAdmission")
                    .isNull()
            }
            if (action is CircuitBreakerAction.OnFailure) {
                assertThat(realOpened)
                    .withFailMessage("$message\nopened expected=${predicted.opened} actual=$realOpened")
                    .isEqualTo(predicted.opened)
            }
            if (action is CircuitBreakerAction.QueryOpenUntil) {
                assertThat(realQueried)
                    .withFailMessage("$message\nopenUntil expected=${predicted.openUntilMillis} actual=$realQueried")
                    .isEqualTo(predicted.openUntilMillis)
            }
            PROPERTY_PROVIDERS.forEach { provider ->
                assertThat(realOpenUntilAfter[provider])
                    .withFailMessage("$message\nsnapshot openUntilMillis($provider) expected=${current.openUntilMillis(provider)}")
                    .isEqualTo(current.openUntilMillis(provider))
            }
            assertThat(current.invariantViolation())
                .withFailMessage("$message\nmodel invariant violated")
                .isNull()

            traces += StepTrace(step, action, modelBefore, predicted, realAdmission, realOpened, realQueried, realOpenUntilBefore, realOpenUntilAfter)
        }
        return traces
    }

    private fun corpus(seed: Long): List<StepTrace> =
        driveScript("seed=$seed", ProviderCircuitBreakerActionGenerator.generate(seed))

    // ── P1: model/reality equivalence over the full corpus ───────────────────

    @Test
    fun `P1 model and reality agree after every action of every seed`() {
        for (seed in 0L until ProviderCircuitBreakerActionGenerator.SEED_COUNT) {
            corpus(seed)
        }
    }

    // ── P2: exactly-one-authoritative-generation ─────────────────────────────

    @Test
    fun `P2 live permits never carry a newer generation and stale completions are no-ops`() {
        for (seed in 0L until ProviderCircuitBreakerActionGenerator.SEED_COUNT) {
            val issued = mutableMapOf<String, MutableSet<Long>>()
            for (trace in corpus(seed)) {
                (trace.realAdmission as? CircuitBreakerAdmission.Allowed)?.permit?.let { permit ->
                    issued.getOrPut(permit.providerId) { mutableSetOf() }.add(permit.generation)
                }
                // Never a NEWER generation than the current authoritative one.
                issued.forEach { (provider, generations) ->
                    val authoritative = trace.modelResult.next.states[provider]?.generation ?: 0L
                    assertThat(generations)
                        .withFailMessage("seed=$seed step=${trace.step} provider=$provider authoritative=$authoritative issued=$generations")
                        .allSatisfy { generation ->
                            assertThat(generation).isLessThanOrEqualTo(authoritative)
                        }
                }
                // Any completion with an older generation is a no-op on the real breaker.
                val action = trace.action
                val completionProvider = action.completionProvider ?: continue
                val stateGeneration = trace.modelBefore.states[completionProvider]?.generation
                if (stateGeneration != null && stateGeneration != action.completionGeneration) {
                    assertThat(trace.realOpenUntilAfter)
                        .withFailMessage("seed=$seed step=${trace.step} stale $action mutated the breaker")
                        .isEqualTo(trace.realOpenUntilBefore)
                    if (action is CircuitBreakerAction.OnFailure) {
                        assertThat(trace.realOpened)
                            .withFailMessage("seed=$seed step=${trace.step} stale failure reported an OPEN transition")
                            .isFalse()
                    }
                }
            }
        }
    }

    // ── P3: at most one HALF_OPEN probe per expiry ───────────────────────────

    @Test
    fun `P3 open expiry admits at most one half-open probe per instant`() {
        for (seed in 0L until ProviderCircuitBreakerActionGenerator.SEED_COUNT) {
            val clusters = mutableMapOf<Pair<String, Long>, MutableList<CircuitBreakerAdmission>>()
            for (trace in corpus(seed)) {
                val action = trace.action as? CircuitBreakerAction.BeforeCall ?: continue
                val state = trace.modelBefore.states[action.provider] as? CircuitBreakerState.Open ?: continue
                if (trace.modelBefore.nowMillis < state.blockedUntilMillis) continue // not yet expired
                clusters.getOrPut(action.provider to trace.modelBefore.nowMillis) { mutableListOf() }
                    .add(trace.realAdmission!!)
            }
            clusters.forEach { (key, admissions) ->
                val allowed = admissions.count { it is CircuitBreakerAdmission.Allowed }
                assertThat(allowed)
                    .withFailMessage("seed=$seed cluster=$key admissions=$admissions")
                    .isEqualTo(1)
                assertThat(admissions.count { it is CircuitBreakerAdmission.Rejected })
                    .withFailMessage("seed=$seed cluster=$key admissions=$admissions")
                    .isEqualTo(admissions.size - 1)
                assertThat(admissions.first())
                    .withFailMessage("seed=$seed cluster=$key first caller must win the probe")
                    .isInstanceOf(CircuitBreakerAdmission.Allowed::class.java)
            }
        }
    }

    // ── P4: stale completions never mutate ───────────────────────────────────

    @Test
    fun `P4 stale completions never mutate breaker state`() {
        for (seed in 0L until ProviderCircuitBreakerActionGenerator.SEED_COUNT) {
            for (trace in corpus(seed)) {
                val action = trace.action
                val provider = action.completionProvider ?: continue
                val state = trace.modelBefore.states[provider] ?: continue
                if (state.generation == action.completionGeneration) continue // not stale
                // The model — authoritative — treats it as a pure no-op.
                assertThat(trace.modelResult.next.states)
                    .withFailMessage("seed=$seed step=${trace.step} model mutated on stale $action")
                    .isEqualTo(trace.modelBefore.states)
                // The real breaker must not close, reopen, or extend either.
                assertThat(trace.realOpenUntilAfter)
                    .withFailMessage("seed=$seed step=${trace.step} real breaker mutated on stale $action")
                    .isEqualTo(trace.realOpenUntilBefore)
                if (action is CircuitBreakerAction.OnFailure) {
                    assertThat(trace.realOpened)
                        .withFailMessage("seed=$seed step=${trace.step} stale failure reported an OPEN transition")
                        .isFalse()
                }
            }
        }
    }

    // ── P5: qualifying-failure counting ──────────────────────────────────────

    @Test
    fun `P5 qualifying failures open exactly at the threshold and return true exactly once`() {
        for (threshold in 1..4) {
            val settings = CircuitBreakerSettings(enabled = true, failureThreshold = threshold, openDurationMillis = 1_000L)
            val actions = buildList {
                add(CircuitBreakerAction.BeforeCall(PROPERTY_P))
                repeat(threshold) { add(CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true)) }
                add(CircuitBreakerAction.QueryOpenUntil(PROPERTY_P))
                add(CircuitBreakerAction.BeforeCall(PROPERTY_P))
                add(CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true)) // stale after OPEN
            }
            val traces = driveScript(
                "threshold=$threshold",
                actions,
                model = CircuitBreakerModel(settings = settings),
                breakerSettings = settings,
            )
            // Below threshold: stays CLOSED. At threshold: OPEN, true exactly once; the stale follow-up is false.
            val opened = traces.filter { it.action is CircuitBreakerAction.OnFailure }.map { it.modelResult.opened }
            assertThat(opened)
                .withFailMessage("threshold=$threshold opened=$opened")
                .isEqualTo(List(threshold - 1) { false } + listOf(true, false))
            // The transition opened with the deadline now + openDuration.
            assertThat(traces[threshold + 1].realQueriedOpenUntil)
                .withFailMessage("threshold=$threshold query after OPEN")
                .isEqualTo(1_000L)
            assertThat(traces[threshold + 2].realAdmission)
                .withFailMessage("threshold=$threshold admission while OPEN")
                .isEqualTo(CircuitBreakerAdmission.Rejected(1_000L))
        }
    }

    // ── P6: non-qualifying failures never count ──────────────────────────────

    @Test
    fun `P6 non-qualifying failures never count never open and never return true`() {
        NON_QUALIFYING_ERRORS.forEachIndexed { index, error ->
            val actions = listOf(
                CircuitBreakerAction.BeforeCall(PROPERTY_P),
                CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = false),
                CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = false),
                CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = false), // threshold 3, never reached
                CircuitBreakerAction.QueryOpenUntil(PROPERTY_P),
                CircuitBreakerAction.BeforeCall(PROPERTY_P),
            )
            val traces = driveScript(
                "error[$index]=${error::class.simpleName}",
                actions,
                nonQualifyingError = { error },
            )
            assertThat(traces.filter { it.action is CircuitBreakerAction.OnFailure }.map { it.modelResult.opened })
                .withFailMessage("${error::class.simpleName} must never open")
                .containsOnly(false)
            assertThat(traces[4].realQueriedOpenUntil)
                .withFailMessage("${error::class.simpleName} must not open the circuit")
                .isNull()
            assertThat((traces[5].realAdmission as? CircuitBreakerAdmission.Allowed)?.permit?.generation)
                .withFailMessage("${error::class.simpleName} must leave the circuit closed")
                .isEqualTo(0L)
        }
        // Mixed: two qualifying + two non-qualifying + one qualifying → opens on the THIRD qualifying failure.
        val mixed = listOf(
            CircuitBreakerAction.BeforeCall(PROPERTY_P),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = false),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = false),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P),
        )
        val traces = driveScript("mixed", mixed)
        assertThat(traces.filter { it.action is CircuitBreakerAction.OnFailure }.map { it.modelResult.opened })
            .isEqualTo(listOf(false, false, false, false, true))
        assertThat(traces[6].realQueriedOpenUntil).isEqualTo(1_000L)
    }

    // ── P7: HALF_OPEN probe success closes, probe failure reopens ────────────

    @Test
    fun `P7 half-open probe success closes with generation preserved and probe failure reopens with fresh deadline`() {
        // Probe success: HALF_OPEN closes to CLOSED with the SAME generation.
        val successActions = listOf(
            CircuitBreakerAction.BeforeCall(PROPERTY_P),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true), // OPEN(1, 1000)
            CircuitBreakerAction.AdvanceClock(1_000L),
            CircuitBreakerAction.BeforeCall(PROPERTY_P), // probe Allowed(1)
            CircuitBreakerAction.OnSuccess(PROPERTY_P, 1L), // probe success → CLOSED(1, 0)
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P),
            CircuitBreakerAction.BeforeCall(PROPERTY_P),
        )
        val successTraces = driveScript("probe-success", successActions)
        assertThat((successTraces[5].realAdmission as? CircuitBreakerAdmission.Allowed)?.permit?.generation)
            .withFailMessage("probe must be admitted with the OPEN generation")
            .isEqualTo(1L)
        assertThat(successTraces[7].realQueriedOpenUntil)
            .withFailMessage("probe success must close the circuit")
            .isNull()
        assertThat((successTraces[8].realAdmission as? CircuitBreakerAdmission.Allowed)?.permit?.generation)
            .withFailMessage("generation must be preserved across recovery, not reset")
            .isEqualTo(1L)

        // Probe failure: reopens immediately with a fresh deadline REGARDLESS of the threshold.
        for (threshold in 2..4) {
            val settings = CircuitBreakerSettings(enabled = true, failureThreshold = threshold, openDurationMillis = 1_000L)
            val actions = buildList {
                add(CircuitBreakerAction.BeforeCall(PROPERTY_P))
                repeat(threshold) { add(CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true)) } // OPEN(1, 1000)
                add(CircuitBreakerAction.AdvanceClock(1_000L))
                add(CircuitBreakerAction.BeforeCall(PROPERTY_P)) // probe Allowed(1)
                add(CircuitBreakerAction.OnFailure(PROPERTY_P, 1L, qualifying = true)) // probe failure → OPEN(2, 2000)
                add(CircuitBreakerAction.QueryOpenUntil(PROPERTY_P))
            }
            val traces = driveScript(
                "probe-failure threshold=$threshold",
                actions,
                model = CircuitBreakerModel(settings = settings),
                breakerSettings = settings,
            )
            assertThat((traces[threshold + 2].realAdmission as? CircuitBreakerAdmission.Allowed)?.permit?.generation)
                .withFailMessage("threshold=$threshold probe must carry the OPEN generation")
                .isEqualTo(1L)
            assertThat(traces[threshold + 3].modelResult.opened)
                .withFailMessage("threshold=$threshold probe failure must reopen")
                .isTrue()
            assertThat(traces[threshold + 4].realQueriedOpenUntil)
                .withFailMessage("threshold=$threshold fresh deadline must be now + openDuration")
                .isEqualTo(2_000L)
        }
    }

    // ── P8: rejected callers get a blockedUntil >= their admission time ──────

    @Test
    fun `P8 rejected callers receive blockedUntil at or after their admission time`() {
        for (seed in 0L until ProviderCircuitBreakerActionGenerator.SEED_COUNT) {
            for (trace in corpus(seed)) {
                val admission = trace.realAdmission
                if (admission is CircuitBreakerAdmission.Rejected) {
                    assertThat(admission.blockedUntilMillis)
                        .withFailMessage("seed=$seed step=${trace.step} rejected with blockedUntil=${admission.blockedUntilMillis} at now=${trace.modelBefore.nowMillis}")
                        .isGreaterThanOrEqualTo(trace.modelBefore.nowMillis)
                }
                // While a HALF_OPEN probe is in flight every admission is a rejection.
                val action = trace.action
                if (action is CircuitBreakerAction.BeforeCall &&
                    trace.modelBefore.states[action.provider] is CircuitBreakerState.HalfOpen
                ) {
                    assertThat(trace.realAdmission)
                        .withFailMessage("seed=$seed step=${trace.step} probe in flight must reject")
                        .isInstanceOf(CircuitBreakerAdmission.Rejected::class.java)
                }
            }
        }
    }

    // ── P9: openUntilMillis shape ────────────────────────────────────────────

    @Test
    fun `P9 openUntilMillis reports the deadline only while open and unexpired`() {
        // Fixed walk covering every state shape: no state → CLOSED → OPEN →
        // expired → HALF_OPEN → CLOSED after recovery.
        val actions = listOf(
            CircuitBreakerAction.BeforeCall(PROPERTY_P),
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P), // no state → null
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true), // OPEN(1, 1000)
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P), // OPEN unexpired → 1000
            CircuitBreakerAction.AdvanceClock(500L),
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P), // OPEN unexpired → 1000
            CircuitBreakerAction.AdvanceClock(600L), // now 1100 > 1000
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P), // OPEN expired → null
            CircuitBreakerAction.BeforeCall(PROPERTY_P), // probe → HALF_OPEN
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P), // HALF_OPEN → null
            CircuitBreakerAction.OnSuccess(PROPERTY_P, 1L), // probe success → CLOSED(1, 0)
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P), // CLOSED → null
        )
        assertShapeWalk("shape-walk", driveScript("shape-walk", actions))
        // Corpus-driven: the same shape rule for every query in every seed.
        for (seed in 0L until ProviderCircuitBreakerActionGenerator.SEED_COUNT) {
            assertShapeWalk("seed=$seed", corpus(seed))
        }
    }

    private fun assertShapeWalk(label: String, traces: List<StepTrace>) {
        for (trace in traces) {
            val action = trace.action as? CircuitBreakerAction.QueryOpenUntil ?: continue
            val state = trace.modelBefore.states[action.provider]
            val expected = (state as? CircuitBreakerState.Open)
                ?.takeIf { trace.modelBefore.nowMillis < it.blockedUntilMillis }
                ?.blockedUntilMillis
            assertThat(trace.realQueriedOpenUntil)
                .withFailMessage("$label step=${trace.step} state=$state now=${trace.modelBefore.nowMillis} expected=$expected")
                .isEqualTo(expected)
        }
    }

    // ── P10: stale completions after recovery never disturb CLOSED ───────────

    @Test
    fun `P10 stale completions after recovery never disturb the closed state`() {
        // Corpus-driven: the archetype seeds (seed % 6 == 3) pin stale
        // success/failure pairs right after a probe-success recovery.
        for (seed in 0L until ProviderCircuitBreakerActionGenerator.SEED_COUNT) {
            for (trace in corpus(seed)) {
                val action = trace.action
                val provider = action.completionProvider ?: continue
                val state = trace.modelBefore.states[provider] as? CircuitBreakerState.Closed ?: continue
                if (state.generation == action.completionGeneration) continue // not stale
                // Section-H: after recovery to CLOSED, stale completions from the
                // superseded epoch must not reset failures, reopen, or extend.
                assertThat(trace.modelResult.next.states)
                    .withFailMessage("seed=$seed step=${trace.step} stale $action disturbed CLOSED")
                    .isEqualTo(trace.modelBefore.states)
                assertThat(trace.realOpenUntilAfter)
                    .withFailMessage("seed=$seed step=${trace.step} stale $action disturbed the real breaker")
                    .isEqualTo(trace.realOpenUntilBefore)
                if (action is CircuitBreakerAction.OnFailure) {
                    assertThat(trace.realOpened)
                        .withFailMessage("seed=$seed step=${trace.step} stale failure after recovery reported an OPEN transition")
                        .isFalse()
                }
            }
        }
        // The recovery epoch's failure accounting is untouched: the first qualifying
        // failure of the NEW generation counts as ONE — it does not reopen.
        val actions = listOf(
            CircuitBreakerAction.BeforeCall(PROPERTY_P),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true), // OPEN(1, 1000)
            CircuitBreakerAction.AdvanceClock(1_000L),
            CircuitBreakerAction.BeforeCall(PROPERTY_P), // probe Allowed(1)
            CircuitBreakerAction.OnSuccess(PROPERTY_P, 1L), // recovered → CLOSED(1, 0)
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true), // stale gen-0 → no-op
            CircuitBreakerAction.OnSuccess(PROPERTY_P, 0L), // stale gen-0 → no-op
            CircuitBreakerAction.BeforeCall(PROPERTY_P), // Allowed(1), mints gen 1
            CircuitBreakerAction.OnFailure(PROPERTY_P, 1L, qualifying = true), // failures = 1, still closed
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 1L, qualifying = true), // failures = 2, still closed
        )
        val traces = driveScript("recovery-accounting", actions)
        assertThat(traces[10].modelResult.opened).isFalse()
        assertThat(traces[10].realOpened).isFalse()
        assertThat(traces[11].realQueriedOpenUntil).isNull()
        assertThat(traces[12].modelResult.opened).isFalse()
    }

    // ── P11: generation monotonicity ─────────────────────────────────────────

    @Test
    fun `P11 generation is monotonic and increases exactly once per open entry`() {
        for (seed in 0L until ProviderCircuitBreakerActionGenerator.SEED_COUNT) {
            val lastGeneration = mutableMapOf<String, Long>()
            for (trace in corpus(seed)) {
                val openedProvider = if (trace.modelResult.opened) {
                    (trace.action as CircuitBreakerAction.OnFailure).provider
                } else {
                    null
                }
                PROPERTY_PROVIDERS.forEach { provider ->
                    val generation = trace.modelResult.next.states[provider]?.generation ?: return@forEach
                    val previous = lastGeneration[provider] ?: 0L
                    assertThat(generation)
                        .withFailMessage("seed=$seed step=${trace.step} provider=$provider generation decreased $previous -> $generation")
                        .isGreaterThanOrEqualTo(previous)
                    if (provider == openedProvider) {
                        assertThat(generation)
                            .withFailMessage("seed=$seed step=${trace.step} OPEN entry must advance the generation by exactly one ($previous -> $generation)")
                            .isEqualTo(previous + 1)
                    }
                    lastGeneration[provider] = generation
                }
            }
        }
    }

    // ── P12: disabled breaker is transparent ─────────────────────────────────

    @Test
    fun `P12 disabled breaker is transparent`() {
        val settings = CircuitBreakerSettings(enabled = false, failureThreshold = 3, openDurationMillis = 1_000L)
        val model = CircuitBreakerModel(settings = settings)
        for (seed in 0L until ProviderCircuitBreakerActionGenerator.SEED_COUNT) {
            for (trace in driveScript("seed=$seed", ProviderCircuitBreakerActionGenerator.generate(seed), model = model, breakerSettings = settings)) {
                if (trace.action is CircuitBreakerAction.BeforeCall) {
                    assertThat(trace.realAdmission)
                        .withFailMessage("seed=$seed step=${trace.step} disabled breaker must allow")
                        .isEqualTo(CircuitBreakerAdmission.Allowed(CircuitBreakerPermit(trace.action.provider, 0L)))
                }
                if (trace.action is CircuitBreakerAction.OnFailure) {
                    assertThat(trace.realOpened)
                        .withFailMessage("seed=$seed step=${trace.step} disabled breaker must never open")
                        .isFalse()
                }
                if (trace.action is CircuitBreakerAction.QueryOpenUntil) {
                    assertThat(trace.realQueriedOpenUntil)
                        .withFailMessage("seed=$seed step=${trace.step} disabled breaker reports no deadline")
                        .isNull()
                }
                trace.realOpenUntilAfter.values.forEach {
                    assertThat(it)
                        .withFailMessage("seed=$seed step=${trace.step} disabled breaker must stay transparent")
                        .isNull()
                }
            }
        }
        // Fixed script: even qualifying failures are no-ops when disabled.
        val actions = listOf(
            CircuitBreakerAction.BeforeCall(PROPERTY_P),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P),
            CircuitBreakerAction.BeforeCall(PROPERTY_P),
        )
        val traces = driveScript("disabled-fixed", actions, model = model, breakerSettings = settings)
        assertThat(traces.filter { it.action is CircuitBreakerAction.OnFailure }.map { it.modelResult.opened }).containsOnly(false)
        assertThat(traces[4].realQueriedOpenUntil).isNull()
        assertThat((traces[5].realAdmission as? CircuitBreakerAdmission.Allowed)?.permit?.generation).isEqualTo(0L)
    }

    // ── P13: every HALF_OPEN probe has a terminal breaker transition ────────

    @Test
    fun `P13 every HALF_OPEN probe reaches a terminal breaker transition`() {
        // A probe that terminates neutrally (abandoned) must release probe
        // ownership: the breaker re-enters OPEN with a fresh deadline and an
        // ADVANCED generation, so a replacement probe is eventually admitted
        // and the abandoned permit can never regain authority.
        val actions = listOf(
            CircuitBreakerAction.BeforeCall(PROPERTY_P),
            CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true), // open, gen 1
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P),
            CircuitBreakerAction.AdvanceClock(1_000L), // exact expiry
            CircuitBreakerAction.BeforeCall(PROPERTY_P), // probe admitted, gen 1
            CircuitBreakerAction.OnAbandoned(PROPERTY_P, 1L), // neutral termination
            CircuitBreakerAction.QueryOpenUntil(PROPERTY_P),
            CircuitBreakerAction.AdvanceClock(1_000L), // new expiry
            CircuitBreakerAction.BeforeCall(PROPERTY_P), // replacement probe admitted, gen 2
        )
        val p13Settings = CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 1_000L)
        val traces = driveScript("probe-abandoned", actions, model = CircuitBreakerModel(settings = p13Settings), breakerSettings = p13Settings)

        // Abandonment releases: openUntilMillis reports the fresh deadline (now
        // 1000 + openDuration 1000 = 2000).
        assertThat(traces[6].realQueriedOpenUntil).isEqualTo(2_000L)
        // The replacement probe is minted under the ADVANCED generation.
        assertThat((traces[8].realAdmission as? CircuitBreakerAdmission.Allowed)?.permit?.generation).isEqualTo(2L)

        // Stale abandoned permit can never regain authority: firing the OLD
        // permit after the replacement probe is in flight is a no-op (the
        // replacement probe stays authoritative — next admission still rejected).
        val traces2 = driveScript(
            "stale-abandoned-fenced",
            listOf(
                CircuitBreakerAction.BeforeCall(PROPERTY_P),
                CircuitBreakerAction.OnFailure(PROPERTY_P, 0L, qualifying = true),
                CircuitBreakerAction.AdvanceClock(1_000L),
                CircuitBreakerAction.BeforeCall(PROPERTY_P), // probe gen 1
                CircuitBreakerAction.OnAbandoned(PROPERTY_P, 1L), // abandoned -> OPEN gen 2
                CircuitBreakerAction.AdvanceClock(1_000L),
                CircuitBreakerAction.BeforeCall(PROPERTY_P), // replacement probe gen 2
                CircuitBreakerAction.OnSuccess(PROPERTY_P, 1L), // STALE old success
                CircuitBreakerAction.OnFailure(PROPERTY_P, 1L, qualifying = true), // STALE old failure
                CircuitBreakerAction.BeforeCall(PROPERTY_P), // must still be rejected (probe in flight)
            ),
            model = CircuitBreakerModel(settings = p13Settings),
            breakerSettings = p13Settings,
        )
        assertThat(traces2[9].realAdmission).isInstanceOf(CircuitBreakerAdmission.Rejected::class.java)
    }
}
