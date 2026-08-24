package dev.tramai.testing.persistence.approval

import java.time.Instant
import kotlin.random.Random

/**
 * Epic 8.2a: deterministic, state-aware generator for the approval lifecycle
 * property corpus.
 *
 * [generate] is a pure function of (seed, count, initialNow, expiresAt):
 * the same seed always yields the same action trace, and the trace is
 * simulated against the pure model so the choices stay state-aware (legal
 * and illegal actions interleave deliberately — the corpus is not
 * malformed-input fuzzing). The TCK replays the same actions through the
 * same model against the real stores.
 *
 * Coverage is pinned by `ApprovalLifecycleActionGeneratorTest`: across the
 * 32-seed corpus every required category (valid approve/deny/timeout, early
 * timeout, after-expiry rejections, wrong-version conflicts, post-terminal
 * rejection, fresh consume, expired fresh-consume rejection, wrong-token,
 * exact replay, replay after expiry, wrong-actor/wrong-version replay
 * rejections) must occur at least once.
 */
internal object ApprovalLifecycleActionGenerator {

    const val SEED_COUNT: Long = 32L
    const val ACTIONS_PER_SEQUENCE: Int = 32

    private val actors = listOf("approver-a", "approver-b")
    private val workers = listOf("worker-a", "worker-b")

    fun generate(
        seed: Long,
        count: Int = ACTIONS_PER_SEQUENCE,
        initialNow: Instant,
        expiresAt: Instant,
    ): List<ApprovalLifecycleAction> {
        val rng = Random(seed)
        var model = ApprovalLifecycleModel.pending(initialNow)
        var wrongVersionEmitted = false
        // Odd seeds force the APPROVED→unconsumed→expired path so a fresh
        // consume is guaranteed to be evaluated against an EXPIRED APPROVED
        // pre-state (the expiry guard's discriminator) — never left to luck.
        val forceExpiredFresh = seed % 2L == 1L
        var advanceToExpiryEmitted = false
        var expiredFreshEmitted = false
        return buildList {
            repeat(count) {
                val action = when {
                    !wrongVersionEmitted && model.status == dev.tramai.core.approval.ApprovalStatus.PENDING && model.now < expiresAt -> {
                        // GUARANTEED reachable wrong-version decision against the
                        // PENDING pre-state (the only state where the transition
                        // version guard is the discriminator). Every seed opens
                        // with one — the corpus must never depend on luck for a
                        // primary optimistic-concurrency invariant.
                        wrongVersionEmitted = true
                        if (seed % 2L == 0L) {
                            ApprovalLifecycleAction.ApproveWrongVersion(actors[rng.nextInt(actors.size)])
                        } else {
                            ApprovalLifecycleAction.DenyWrongVersion(actors[rng.nextInt(actors.size)])
                        }
                    }
                    forceExpiredFresh && !advanceToExpiryEmitted &&
                        model.status == dev.tramai.core.approval.ApprovalStatus.APPROVED &&
                        model.consumedAt == null && model.now < expiresAt -> {
                        advanceToExpiryEmitted = true
                        ApprovalLifecycleAction.AdvancePastExpiry
                    }
                    forceExpiredFresh && !expiredFreshEmitted &&
                        model.status == dev.tramai.core.approval.ApprovalStatus.APPROVED &&
                        model.consumedAt == null && model.now >= expiresAt -> {
                        expiredFreshEmitted = true
                        ApprovalLifecycleAction.ConsumeValid(workers[rng.nextInt(workers.size)])
                    }
                    else -> pick(rng, model, expiresAt)
                }
                add(action)
                val outcome = model.apply(action, expiresAt)
                if (outcome is ApprovalLifecycleOutcome.Success) {
                    model = outcome.next
                }
            }
        }
    }

    // ── state-aware picking ─────────────────────────────────────────

    private fun pick(rng: Random, model: ApprovalLifecycleModel, expiresAt: Instant): ApprovalLifecycleAction =
        when (model.status) {
            dev.tramai.core.approval.ApprovalStatus.PENDING -> pickPending(rng, model, expiresAt)
            dev.tramai.core.approval.ApprovalStatus.APPROVED -> pickApproved(rng, model, expiresAt)
            else -> pickTerminal(rng, model, expiresAt)
        }

    private fun pickPending(rng: Random, model: ApprovalLifecycleModel, expiresAt: Instant): ApprovalLifecycleAction {
        val expired = model.now >= expiresAt
        val r = rng.nextInt(100)
        return when {
            r < 22 -> pickAdvance(rng)
            // Decision exploration, biased toward making a decision so the
            // corpus reaches consumption and replay phases.
            r < 34 -> ApprovalLifecycleAction.ApproveCurrentVersion(pickActor(rng))
            r < 46 -> ApprovalLifecycleAction.DenyCurrentVersion(pickActor(rng))
            r < 56 -> ApprovalLifecycleAction.TimeoutCurrentVersion
            r < 68 -> if (expired) {
                // After expiry the only legal decision is timeout; approve/deny
                // are rejections. Wrong-version is avoided here (version-vs-
                // expiry precedence is not contractually pinned).
                when (rng.nextInt(4)) {
                    0 -> ApprovalLifecycleAction.ApproveCurrentVersion(pickActor(rng))
                    1 -> ApprovalLifecycleAction.DenyCurrentVersion(pickActor(rng))
                    else -> ApprovalLifecycleAction.TimeoutCurrentVersion
                }
            } else {
                // Before expiry approve/deny are legal, timeout is illegal,
                // wrong-version is a clean CONFLICT.
                when (rng.nextInt(5)) {
                    0 -> ApprovalLifecycleAction.ApproveCurrentVersion(pickActor(rng))
                    1 -> ApprovalLifecycleAction.DenyCurrentVersion(pickActor(rng))
                    2 -> ApprovalLifecycleAction.TimeoutCurrentVersion
                    3 -> ApprovalLifecycleAction.ApproveWrongVersion(pickActor(rng))
                    else -> ApprovalLifecycleAction.DenyWrongVersion(pickActor(rng))
                }
            }
            r < 80 -> ApprovalLifecycleAction.ApproveCurrentVersion(pickActor(rng))
            r < 88 -> ApprovalLifecycleAction.DenyCurrentVersion(pickActor(rng))
            r < 94 -> ApprovalLifecycleAction.TimeoutCurrentVersion
            else -> pickAdvance(rng)
        }
    }

    private fun pickApproved(rng: Random, model: ApprovalLifecycleModel, expiresAt: Instant): ApprovalLifecycleAction {
        val unconsumed = model.consumedAt == null
        val expired = model.now >= expiresAt
        val r = rng.nextInt(100)
        if (unconsumed) {
            return when {
                r < 42 -> ApprovalLifecycleAction.ConsumeValid(pickWorker(rng))
                r < 56 -> ApprovalLifecycleAction.ConsumeWrongVersion(pickWorker(rng))
                r < 70 -> ApprovalLifecycleAction.ConsumeWrongToken(pickWorker(rng))
                r < 78 -> if (expired) ApprovalLifecycleAction.ConsumeValid(pickWorker(rng)) else ApprovalLifecycleAction.ApproveCurrentVersion(pickActor(rng))
                r < 88 -> ApprovalLifecycleAction.ApproveCurrentVersion(pickActor(rng))
                r < 94 -> ApprovalLifecycleAction.DenyCurrentVersion(pickActor(rng))
                else -> pickAdvance(rng)
            }
        }
        // Consumed — replay phase: exact replay, wrong-actor/wrong-version/
        // wrong-token rejections, post-terminal rejections.
        val sameWorker = model.consumedBy ?: pickWorker(rng)
        val otherWorker = workers.first { it != sameWorker }
        return when {
            r < 40 -> ApprovalLifecycleAction.ConsumeValid(sameWorker)
            r < 54 -> ApprovalLifecycleAction.ConsumeValid(otherWorker)
            r < 66 -> ApprovalLifecycleAction.ConsumeWrongVersion(sameWorker)
            r < 76 -> ApprovalLifecycleAction.ConsumeWrongToken(sameWorker)
            r < 86 -> ApprovalLifecycleAction.ApproveCurrentVersion(pickActor(rng))
            r < 93 -> ApprovalLifecycleAction.DenyCurrentVersion(pickActor(rng))
            else -> pickAdvance(rng)
        }
    }

    private fun pickTerminal(rng: Random, model: ApprovalLifecycleModel, expiresAt: Instant): ApprovalLifecycleAction =
        when (rng.nextInt(10)) {
            0, 1, 2, 3 -> ApprovalLifecycleAction.ApproveCurrentVersion(pickActor(rng))
            4, 5, 6 -> ApprovalLifecycleAction.DenyCurrentVersion(pickActor(rng))
            7, 8 -> ApprovalLifecycleAction.TimeoutCurrentVersion
            else -> ApprovalLifecycleAction.ConsumeValid(pickWorker(rng))
        }

    private fun pickAdvance(rng: Random): ApprovalLifecycleAction =
        when (rng.nextInt(10)) {
            0, 1, 2, 3, 4, 5 -> ApprovalLifecycleAction.AdvanceToBeforeExpiry
            6, 7 -> ApprovalLifecycleAction.AdvanceToExactExpiry
            else -> ApprovalLifecycleAction.AdvancePastExpiry
        }

    private fun pickActor(rng: Random): String = actors[rng.nextInt(actors.size)]

    private fun pickWorker(rng: Random): String = workers[rng.nextInt(workers.size)]
}
