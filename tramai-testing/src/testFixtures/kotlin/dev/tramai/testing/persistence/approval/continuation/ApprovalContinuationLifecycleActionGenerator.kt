package dev.tramai.testing.persistence.approval.continuation

import dev.tramai.core.approval.ApprovalContinuationStatus
import java.time.Instant
import kotlin.random.Random

/**
 * Epic 8.2b: deterministic, state-aware generator for the continuation
 * lifecycle property corpus.
 *
 * [generate] is a pure function of (seed, count, initialNow, expiresAt):
 * the same seed always yields the same action trace, and the trace is
 * simulated against the pure model so choices stay state-aware. Coverage is
 * pinned by `ApprovalContinuationLifecycleActionGeneratorTest` across the
 * 32-seed corpus — semantic categories (reachable pre-state, not action
 * presence) must each occur at least once.
 *
 * Reachability is guaranteed, not left to luck:
 *  - every seed opens with a fixed non-mutating phase that pins the
 *    optimistic-concurrency and early-rejection discriminators against the
 *    PENDING non-expired pre-state: wrong-version claim, early explicit
 *    expire, wrong-version cancel, wrong-version expire, complete on
 *    non-claimed, force-cancel on non-claimed (all fail, all leave the
 *    model at PENDING@0);
 *  - seed % 4 then selects a lifecycle archetype, forced by state:
 *      0 — claim → complete | claim → cancelled-uncertain | claim →
 *          wrong-actor/wrong-version recovery exercises (by (seed/4)%3)
 *      1 — exact-expiry boundary: advance to exactly expiresAt, explicit
 *          expire at the boundary
 *      2 — advance past expiry, then lazy get expiry | late claim |
 *          late cancel (by (seed/4)%3) — each persists EXPIRED before
 *          reporting its typed failure where applicable
 *      3 — valid cancel | advance past expiry + explicit expire
 *          (by (seed/4)%2)
 */
internal object ApprovalContinuationLifecycleActionGenerator {

    const val SEED_COUNT: Long = 32L
    const val ACTIONS_PER_SEQUENCE: Int = 32

    private val workers = listOf("worker-a", "worker-b")
    private const val INTRUDER = "intruder"
    private const val RECOVERY_ACTOR = "recovery-1"
    private const val REASON_CODE = "stale-claim"

    fun generate(
        seed: Long,
        count: Int = ACTIONS_PER_SEQUENCE,
        initialNow: Instant,
        expiresAt: Instant,
    ): List<ApprovalContinuationLifecycleAction> {
        val rng = Random(seed)
        var model = ApprovalContinuationLifecycleModel.pending(initialNow)

        // Opener phase flags (fixed, non-mutating).
        var openedWrongClaim = false
        var openedEarlyExpire = false
        var openedWrongCancel = false
        var openedWrongExpire = false
        var openedCompleteOnPending = false
        var openedForceCancelOnPending = false

        // Archetype forcing flags (seed % 4 selects the class).
        val archetype = seed % 4L
        var claimed = false
        var completed = false
        var uncertain = false
        var boundaryAdvanced = false
        var boundaryExpired = false
        var pastAdvanced = false
        var lazyGot = false
        var lateClaimed = false
        var lateCancelled = false
        var cancelled = false
        var explicitlyExpired = false
        var wrongExpiredAfter = false
        var wrongActor = false
        var wrongCompleted = false
        var wrongRecovered = false
        var wrongClaimOnClaimed = false

        return buildList {
            repeat(count) {
                val action = when {
                    !openedWrongClaim && model.status == ApprovalContinuationStatus.PENDING && model.now < expiresAt -> {
                        openedWrongClaim = true
                        ApprovalContinuationLifecycleAction.ClaimWrongVersion(workers[rng.nextInt(workers.size)])
                    }
                    !openedEarlyExpire && model.status == ApprovalContinuationStatus.PENDING && model.now < expiresAt -> {
                        openedEarlyExpire = true
                        ApprovalContinuationLifecycleAction.ExpireCurrentVersion
                    }
                    !openedWrongCancel && model.status == ApprovalContinuationStatus.PENDING && model.now < expiresAt -> {
                        openedWrongCancel = true
                        ApprovalContinuationLifecycleAction.CancelWrongVersion
                    }
                    !openedWrongExpire && model.status == ApprovalContinuationStatus.PENDING && model.now < expiresAt -> {
                        openedWrongExpire = true
                        ApprovalContinuationLifecycleAction.ExpireWrongVersion
                    }
                    !openedCompleteOnPending && model.status == ApprovalContinuationStatus.PENDING && model.now < expiresAt -> {
                        openedCompleteOnPending = true
                        ApprovalContinuationLifecycleAction.CompleteCurrentVersion(workers[rng.nextInt(workers.size)])
                    }
                    !openedForceCancelOnPending && model.status == ApprovalContinuationStatus.PENDING && model.now < expiresAt -> {
                        openedForceCancelOnPending = true
                        ApprovalContinuationLifecycleAction.ForceCancelCurrentVersion(RECOVERY_ACTOR, REASON_CODE)
                    }
                    // ── Archetype 0: claim paths ──
                    archetype == 0L && !claimed && model.status == ApprovalContinuationStatus.PENDING && model.now < expiresAt -> {
                        claimed = true
                        ApprovalContinuationLifecycleAction.ClaimCurrentVersion(workers[rng.nextInt(workers.size)])
                    }
                    archetype == 0L && !completed && model.status == ApprovalContinuationStatus.CLAIMED &&
                        (seed / 4L) % 3L == 0L -> {
                        completed = true
                        ApprovalContinuationLifecycleAction.CompleteCurrentVersion(model.claimedBy ?: workers[0])
                    }
                    archetype == 0L && !uncertain && model.status == ApprovalContinuationStatus.CLAIMED &&
                        (seed / 4L) % 3L == 1L -> {
                        uncertain = true
                        ApprovalContinuationLifecycleAction.ForceCancelCurrentVersion(RECOVERY_ACTOR, REASON_CODE)
                    }
                    archetype == 0L && !wrongActor && model.status == ApprovalContinuationStatus.CLAIMED &&
                        (seed / 4L) % 3L == 2L -> {
                        wrongActor = true
                        ApprovalContinuationLifecycleAction.CompleteWrongActor(INTRUDER)
                    }
                    archetype == 0L && !wrongCompleted && model.status == ApprovalContinuationStatus.CLAIMED &&
                        (seed / 4L) % 3L == 2L -> {
                        wrongCompleted = true
                        ApprovalContinuationLifecycleAction.CompleteWrongVersion(workers[rng.nextInt(workers.size)])
                    }
                    archetype == 0L && !wrongRecovered && model.status == ApprovalContinuationStatus.CLAIMED &&
                        (seed / 4L) % 3L == 2L -> {
                        wrongRecovered = true
                        ApprovalContinuationLifecycleAction.ForceCancelWrongVersion(RECOVERY_ACTOR, REASON_CODE)
                    }
                    archetype == 0L && !wrongClaimOnClaimed && model.status == ApprovalContinuationStatus.CLAIMED &&
                        (seed / 4L) % 3L == 2L -> {
                        wrongClaimOnClaimed = true
                        ApprovalContinuationLifecycleAction.ClaimWrongVersion(workers[rng.nextInt(workers.size)])
                    }
                    // ── Archetype 1: exact-expiry boundary ──
                    archetype == 1L && !boundaryAdvanced && model.status == ApprovalContinuationStatus.PENDING -> {
                        boundaryAdvanced = true
                        ApprovalContinuationLifecycleAction.AdvanceToExactExpiry
                    }
                    archetype == 1L && !boundaryExpired && model.status == ApprovalContinuationStatus.PENDING &&
                        model.now == expiresAt -> {
                        boundaryExpired = true
                        ApprovalContinuationLifecycleAction.ExpireCurrentVersion
                    }
                    // ── Archetype 2: past-expiry paths ──
                    archetype == 2L && !pastAdvanced && model.status == ApprovalContinuationStatus.PENDING -> {
                        pastAdvanced = true
                        ApprovalContinuationLifecycleAction.AdvancePastExpiry
                    }
                    archetype == 2L && !lazyGot && (seed / 4L) % 3L == 0L &&
                        model.status == ApprovalContinuationStatus.PENDING && model.now >= expiresAt -> {
                        lazyGot = true
                        ApprovalContinuationLifecycleAction.Get
                    }
                    archetype == 2L && !lateClaimed && (seed / 4L) % 3L == 1L &&
                        model.status == ApprovalContinuationStatus.PENDING && model.now >= expiresAt -> {
                        lateClaimed = true
                        ApprovalContinuationLifecycleAction.ClaimCurrentVersion(workers[rng.nextInt(workers.size)])
                    }
                    archetype == 2L && !lateCancelled && (seed / 4L) % 3L == 2L &&
                        model.status == ApprovalContinuationStatus.PENDING && model.now >= expiresAt -> {
                        lateCancelled = true
                        ApprovalContinuationLifecycleAction.CancelCurrentVersion
                    }
                    // ── Archetype 3: cancel / explicit expire ──
                    archetype == 3L && !cancelled && (seed / 4L) % 2L == 0L &&
                        model.status == ApprovalContinuationStatus.PENDING && model.now < expiresAt -> {
                        cancelled = true
                        ApprovalContinuationLifecycleAction.CancelCurrentVersion
                    }
                    archetype == 3L && !pastAdvanced && (seed / 4L) % 2L == 1L &&
                        model.status == ApprovalContinuationStatus.PENDING -> {
                        pastAdvanced = true
                        ApprovalContinuationLifecycleAction.AdvancePastExpiry
                    }
                    archetype == 3L && !wrongExpiredAfter && (seed / 4L) % 2L == 1L &&
                        model.status == ApprovalContinuationStatus.PENDING && model.now >= expiresAt -> {
                        // Wrong-version explicit expire evaluated AFTER the
                        // deadline: the version guard is the only thing
                        // standing between the record and a spurious EXPIRED
                        // transition (mutation M11's discriminator).
                        wrongExpiredAfter = true
                        ApprovalContinuationLifecycleAction.ExpireWrongVersion
                    }
                    archetype == 3L && !explicitlyExpired && (seed / 4L) % 2L == 1L &&
                        model.status == ApprovalContinuationStatus.PENDING && model.now >= expiresAt -> {
                        explicitlyExpired = true
                        ApprovalContinuationLifecycleAction.ExpireCurrentVersion
                    }
                    else -> pick(rng, model, expiresAt)
                }
                add(action)
                val outcome = model.apply(action, expiresAt)
                when (outcome) {
                    is ApprovalContinuationLifecycleOutcome.Success -> model = outcome.next
                    is ApprovalContinuationLifecycleOutcome.Failure -> model = outcome.next
                }
            }
        }
    }

    // ── state-aware picking (free-run; keeps the corpus dense after the
    //    archetype has driven the model into CLAIMED/terminal states) ──

    private fun pick(
        rng: Random,
        model: ApprovalContinuationLifecycleModel,
        expiresAt: Instant,
    ): ApprovalContinuationLifecycleAction = when (model.status) {
        ApprovalContinuationStatus.PENDING -> pickPending(rng, model, expiresAt)
        ApprovalContinuationStatus.CLAIMED -> pickClaimed(rng, model, expiresAt)
        else -> pickTerminal(rng, model)
    }

    private fun pickPending(
        rng: Random,
        model: ApprovalContinuationLifecycleModel,
        expiresAt: Instant,
    ): ApprovalContinuationLifecycleAction {
        val r = rng.nextInt(100)
        return when {
            r < 15 && model.now < expiresAt ->
                ApprovalContinuationLifecycleAction.ClaimCurrentVersion(workers[rng.nextInt(workers.size)])
            r < 25 ->
                ApprovalContinuationLifecycleAction.ClaimWrongVersion(workers[rng.nextInt(workers.size)])
            r < 35 && model.now < expiresAt ->
                ApprovalContinuationLifecycleAction.CancelCurrentVersion
            r < 45 ->
                ApprovalContinuationLifecycleAction.CancelWrongVersion
            r < 55 && model.now < expiresAt ->
                ApprovalContinuationLifecycleAction.AdvanceToExactExpiry
            r < 65 && model.now < expiresAt ->
                ApprovalContinuationLifecycleAction.AdvancePastExpiry
            r < 75 && model.now < expiresAt ->
                ApprovalContinuationLifecycleAction.AdvanceToBeforeExpiry
            r < 85 ->
                ApprovalContinuationLifecycleAction.ExpireCurrentVersion
            r < 92 ->
                ApprovalContinuationLifecycleAction.ExpireWrongVersion
            else -> ApprovalContinuationLifecycleAction.Get
        }
    }

    private fun pickClaimed(
        rng: Random,
        model: ApprovalContinuationLifecycleModel,
        expiresAt: Instant,
    ): ApprovalContinuationLifecycleAction {
        val r = rng.nextInt(100)
        return when {
            r < 20 -> ApprovalContinuationLifecycleAction.CompleteCurrentVersion(model.claimedBy ?: workers[0])
            r < 32 -> ApprovalContinuationLifecycleAction.CompleteWrongVersion(workers[rng.nextInt(workers.size)])
            r < 44 -> ApprovalContinuationLifecycleAction.CompleteWrongActor(INTRUDER)
            r < 56 -> ApprovalContinuationLifecycleAction.ForceCancelCurrentVersion(RECOVERY_ACTOR, REASON_CODE)
            r < 66 -> ApprovalContinuationLifecycleAction.ForceCancelWrongVersion(RECOVERY_ACTOR, REASON_CODE)
            r < 76 -> ApprovalContinuationLifecycleAction.ClaimCurrentVersion(workers[rng.nextInt(workers.size)])
            r < 86 -> ApprovalContinuationLifecycleAction.ClaimWrongVersion(workers[rng.nextInt(workers.size)])
            r < 93 -> ApprovalContinuationLifecycleAction.CancelCurrentVersion
            else -> ApprovalContinuationLifecycleAction.Get
        }
    }

    private fun pickTerminal(
        rng: Random,
        model: ApprovalContinuationLifecycleModel,
    ): ApprovalContinuationLifecycleAction {
        // Deliberately NO wrong-version actions on terminal/EXPIRED states:
        // the combined-invalid precedence (already-EXPIRED + wrong
        // expected-version) is an implementation detail that currently
        // differs across stores — the corpus pins version-first only on the
        // discriminating pre-states (PENDING before expiry, CLAIMED).
        val r = rng.nextInt(100)
        return when {
            r < 12 -> ApprovalContinuationLifecycleAction.ClaimCurrentVersion(workers[rng.nextInt(workers.size)])
            r < 24 -> ApprovalContinuationLifecycleAction.CancelCurrentVersion
            r < 36 -> ApprovalContinuationLifecycleAction.ExpireCurrentVersion
            r < 48 -> ApprovalContinuationLifecycleAction.CompleteCurrentVersion(workers[rng.nextInt(workers.size)])
            r < 60 -> ApprovalContinuationLifecycleAction.ForceCancelCurrentVersion(RECOVERY_ACTOR, REASON_CODE)
            else -> ApprovalContinuationLifecycleAction.Get
        }
    }
}
