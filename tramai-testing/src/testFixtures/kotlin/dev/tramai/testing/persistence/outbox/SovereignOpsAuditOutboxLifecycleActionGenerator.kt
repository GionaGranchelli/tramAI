package dev.tramai.testing.persistence.outbox

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

private typealias A = SovereignOpsAuditOutboxLifecycleAction

/** Deterministic, state-aware audit-outbox lifecycle corpus generator. */
internal object SovereignOpsAuditOutboxLifecycleActionGenerator {

    const val SEED_COUNT: Long = 32L
    const val ACTIONS_PER_SEQUENCE: Int = 32

    fun generate(
        seed: Long,
        count: Int = ACTIONS_PER_SEQUENCE,
        initialNow: Instant,
        claimDuration: Duration,
    ): List<SovereignOpsAuditOutboxLifecycleAction> {
        val rng = Random(seed)
        var model = SovereignOpsAuditOutboxLifecycleModel.absent(initialNow)
        val spine = coverageSpine(seed)
        return buildList {
            repeat(count) { step ->
                val action = spine.getOrNull(step) ?: pick(rng, model)
                add(action)
                when (val outcome = model.apply(action, claimDuration)) {
                    is SovereignOpsAuditOutboxLifecycleOutcome.Success -> model = outcome.next
                    is SovereignOpsAuditOutboxLifecycleOutcome.Failure -> check(outcome.unchanged == model)
                }
            }
        }
    }

    /**
     * Complementary deterministic lanes guarantee every lifecycle
     * discriminator without adding reset operations to the real state model.
     */
    private fun coverageSpine(seed: Long): List<SovereignOpsAuditOutboxLifecycleAction> = when (seed % 8L) {
        0L -> listOf(
            A.AppendPrepared,
            A.MarkReady,
            A.ClaimWorkerA,
            A.MarkEmittedCurrent,
            A.ClaimWorkerB,
            A.MarkEmittedCurrent,
            A.MarkRetryableFailureCurrent,
            A.MarkPermanentFailureCurrent,
            A.MarkEmittedStaleAttempt,
            A.ObserveCurrent,
        )
        1L -> listOf(
            A.AppendPrepared,
            A.MarkPreparedPermanentFailure,
            A.MarkReady,
            A.ClaimWorkerA,
            A.MarkEmittedCurrent,
            A.MarkRetryableFailureCurrent,
            A.MarkPermanentFailureCurrent,
            A.ObserveCurrent,
        )
        2L -> listOf(
            A.AppendPrepared,
            A.MarkReady,
            A.ClaimWorkerA,
            A.MarkRetryableFailureCurrent,
            A.ClaimWorkerA,
            A.MarkEmittedStaleAttempt,
            A.MarkRetryableFailureStaleAttempt,
            A.MarkPermanentFailureStaleAttempt,
            A.MarkRetryableFailureCurrent,
            A.ClaimWorkerB,
            A.MarkEmittedStaleAttempt,
            A.MarkRetryableFailureStaleAttempt,
            A.MarkPermanentFailureStaleAttempt,
            A.MarkPermanentFailureCurrent,
            A.ClaimWorkerA,
        )
        3L -> listOf(
            A.AppendPrepared,
            A.MarkReady,
            A.ClaimWorkerA,
            A.AdvanceBeforeClaimExpiry,
            A.ClaimWorkerB,
            A.AdvanceToExactClaimExpiry,
            A.ClaimWorkerB,
            A.AdvancePastClaimExpiry,
            A.ClaimWorkerB,
            A.MarkEmittedStaleAttempt,
            A.MarkRetryableFailureStaleAttempt,
            A.MarkPermanentFailureStaleAttempt,
            A.AdvancePastClaimExpiry,
            A.ClaimWorkerA,
            A.MarkEmittedCurrent,
        )
        4L -> listOf(
            A.AppendPrepared,
            A.MarkReady,
            A.ClaimWorkerA,
            A.AdvancePastClaimExpiry,
            A.ClaimWorkerA,
            A.MarkEmittedStaleAttempt,
            A.MarkRetryableFailureStaleAttempt,
            A.MarkPermanentFailureStaleAttempt,
            A.AdvancePastClaimExpiry,
            A.ClaimWorkerA,
            A.MarkEmittedStaleAttempt,
            A.MarkRetryableFailureStaleAttempt,
            A.MarkPermanentFailureStaleAttempt,
            A.ObserveCurrent,
        )
        5L -> listOf(
            A.AppendPrepared,
            A.MarkReady,
            A.ClaimWorkerB,
            A.MarkPermanentFailureCurrent,
            A.MarkReady,
            A.ClaimWorkerA,
            A.MarkEmittedCurrent,
            A.MarkRetryableFailureCurrent,
            A.MarkPermanentFailureCurrent,
            A.ObserveCurrent,
        )
        6L -> listOf(
            A.AppendPrepared,
            A.ClaimWorkerA,
            A.MarkReady,
            A.ClaimWorkerB,
            A.MarkRetryableFailureCurrent,
            A.ClaimWorkerA,
            A.MarkEmittedCurrent,
            A.ClaimWorkerB,
            A.ObserveCurrent,
        )
        else -> listOf(
            A.ObserveCurrent,
            A.MarkReady,
            A.ClaimWorkerA,
            A.AppendPrepared,
            A.MarkReady,
            A.ClaimWorkerB,
            A.AdvanceToExactClaimExpiry,
            A.ClaimWorkerA,
            A.AdvancePastClaimExpiry,
            A.ClaimWorkerB,
            A.MarkRetryableFailureCurrent,
            A.ClaimWorkerA,
            A.MarkEmittedCurrent,
        )
    }

    private fun pick(
        rng: Random,
        model: SovereignOpsAuditOutboxLifecycleModel,
    ): SovereignOpsAuditOutboxLifecycleAction {
        val record = model.current ?: return when (rng.nextInt(100)) {
            in 0..54 -> A.AppendPrepared
            in 55..69 -> A.MarkReady
            in 70..79 -> pickClaim(rng)
            else -> A.ObserveCurrent
        }

        return when (record.status) {
            dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus.PREPARED ->
                listOf(A.MarkReady, A.MarkPreparedPermanentFailure, pickClaim(rng), A.ObserveCurrent).random(rng)
            dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus.PENDING ->
                listOf(pickClaim(rng), A.MarkReady, A.MarkEmittedCurrent, A.ObserveCurrent).random(rng)
            dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus.EMITTING -> when (rng.nextInt(100)) {
                in 0..14 -> pickClaim(rng)
                in 15..27 -> A.MarkEmittedCurrent
                in 28..39 -> A.MarkRetryableFailureCurrent
                in 40..49 -> A.MarkPermanentFailureCurrent
                in 50..57 -> A.MarkEmittedStaleAttempt
                in 58..65 -> A.MarkRetryableFailureStaleAttempt
                in 66..73 -> A.MarkPermanentFailureStaleAttempt
                in 74..81 -> A.AdvanceBeforeClaimExpiry
                in 82..88 -> A.AdvanceToExactClaimExpiry
                in 89..95 -> A.AdvancePastClaimExpiry
                else -> A.ObserveCurrent
            }
            dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE ->
                listOf(pickClaim(rng), A.MarkEmittedCurrent, A.MarkPermanentFailureCurrent, A.ObserveCurrent).random(rng)
            dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus.EMITTED,
            dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus.FAILED_PERMANENT,
            -> listOf(
                pickClaim(rng),
                A.MarkReady,
                A.MarkEmittedCurrent,
                A.MarkRetryableFailureCurrent,
                A.MarkPermanentFailureCurrent,
                A.ObserveCurrent,
            ).random(rng)
        }
    }

    private fun pickClaim(rng: Random): SovereignOpsAuditOutboxLifecycleAction =
        if (rng.nextBoolean()) A.ClaimWorkerA else A.ClaimWorkerB
}
