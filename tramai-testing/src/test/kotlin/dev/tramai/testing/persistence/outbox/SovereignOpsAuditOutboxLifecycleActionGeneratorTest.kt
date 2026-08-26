package dev.tramai.testing.persistence.outbox

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SovereignOpsAuditOutboxLifecycleActionGeneratorTest {

    @Test
    fun `same seed produces exactly the same outbox action trace`() {
        for (seed in 0L until SovereignOpsAuditOutboxLifecycleActionGenerator.SEED_COUNT) {
            val first = generate(seed)
            val second = generate(seed)
            assertThat(first.map { it.describe() })
                .withFailMessage("seed $seed must be deterministic")
                .isEqualTo(second.map { it.describe() })
        }
    }

    @Test
    fun `fixed seed corpus covers every semantic outbox lifecycle category`() {
        val coverage = recordCorpus()
        val expected = setOf(
            "fresh-prepared",
            "prepared-to-pending",
            "prepared-to-failed-permanent",
            "pending-first-claim",
            "emitting-to-emitted",
            "emitting-to-failed-retryable",
            "emitting-to-failed-permanent",
            "failed-retryable-reclaim",
            "before-expiry-rejected-reclaim",
            "exact-expiry-rejected-reclaim",
            "past-expiry-reclaim",
            "different-worker-reclaim",
            "same-worker-reclaim",
            "attempt-1",
            "attempt-2",
            "attempt-3-plus",
            "retry-clears-last-error",
            "stale-predecessor-emit",
            "stale-predecessor-retryable-failure",
            "stale-predecessor-permanent-failure",
            "terminal-emit-rejection",
            "terminal-failure-rejection",
            "terminal-claim-rejection",
            "emitted-absorbing",
            "failed-permanent-absorbing",
        )
        val missing = expected - coverage
        assertThat(missing)
            .withFailMessage {
                "corpus missing categories: ${missing.sorted()}\n" +
                    "available: ${coverage.sorted()}\n" +
                    "actions: ${SovereignOpsAuditOutboxLifecycleActionGenerator.SEED_COUNT * SovereignOpsAuditOutboxLifecycleActionGenerator.ACTIONS_PER_SEQUENCE}"
            }
            .isEmpty()
    }

    private fun recordCorpus(): Set<String> = buildSet {
        for (seed in 0L until SovereignOpsAuditOutboxLifecycleActionGenerator.SEED_COUNT) {
            var model = SovereignOpsAuditOutboxLifecycleModel.absent(SovereignOpsAuditOutboxFixtures.T0)
            generate(seed).forEach { action ->
                val before = model
                val outcome = model.apply(action, CLAIM_DURATION)
                record(this, action, before, outcome)
                if (outcome is SovereignOpsAuditOutboxLifecycleOutcome.Success) model = outcome.next
            }
        }
    }

    private fun record(
        coverage: MutableSet<String>,
        action: SovereignOpsAuditOutboxLifecycleAction,
        before: SovereignOpsAuditOutboxLifecycleModel,
        outcome: SovereignOpsAuditOutboxLifecycleOutcome,
    ) {
        val after = when (outcome) {
            is SovereignOpsAuditOutboxLifecycleOutcome.Success -> outcome.next
            is SovereignOpsAuditOutboxLifecycleOutcome.Failure -> outcome.unchanged
        }
        val beforeRecord = before.current
        val afterRecord = after.current
        val succeeded = outcome is SovereignOpsAuditOutboxLifecycleOutcome.Success

        if (action == A.AppendPrepared && succeeded) coverage += "fresh-prepared"
        if (beforeRecord?.status == S.PREPARED && afterRecord?.status == S.PENDING) {
            coverage += "prepared-to-pending"
        }
        if (beforeRecord?.status == S.PREPARED && afterRecord?.status == S.FAILED_PERMANENT) {
            coverage += "prepared-to-failed-permanent"
        }
        if (beforeRecord?.status == S.PENDING && action.isClaim() && succeeded) {
            coverage += "pending-first-claim"
        }
        if (beforeRecord?.status == S.EMITTING && afterRecord?.status == S.EMITTED) {
            coverage += "emitting-to-emitted"
        }
        if (beforeRecord?.status == S.EMITTING && afterRecord?.status == S.FAILED_RETRYABLE) {
            coverage += "emitting-to-failed-retryable"
        }
        if (beforeRecord?.status == S.EMITTING && afterRecord?.status == S.FAILED_PERMANENT) {
            coverage += "emitting-to-failed-permanent"
        }
        if (beforeRecord?.status == S.FAILED_RETRYABLE && action.isClaim() && succeeded) {
            coverage += "failed-retryable-reclaim"
        }

        if (beforeRecord?.status == S.EMITTING && action.isClaim()) {
            val expiry = requireNotNull(beforeRecord.claimExpiresAt)
            when {
                before.now.isBefore(expiry) && !succeeded -> coverage += "before-expiry-rejected-reclaim"
                before.now == expiry && !succeeded -> coverage += "exact-expiry-rejected-reclaim"
                before.now.isAfter(expiry) && succeeded -> coverage += "past-expiry-reclaim"
            }
        }

        if (action.isClaim() && succeeded && (beforeRecord?.attemptCount ?: 0) >= 1) {
            val worker = action.worker()
            coverage += if (beforeRecord?.claimedBy == worker) {
                "same-worker-reclaim"
            } else {
                "different-worker-reclaim"
            }
        }
        if (action.isClaim() && succeeded) {
            when (afterRecord?.attemptCount) {
                1 -> coverage += "attempt-1"
                2 -> coverage += "attempt-2"
                in 3..Int.MAX_VALUE -> coverage += "attempt-3-plus"
            }
            if (beforeRecord?.lastErrorCode != null && afterRecord?.lastErrorCode == null) {
                coverage += "retry-clears-last-error"
            }
        }

        when (action) {
            A.MarkEmittedStaleAttempt -> if (!succeeded && before.predecessorClaims.isNotEmpty()) {
                coverage += "stale-predecessor-emit"
            }
            A.MarkRetryableFailureStaleAttempt -> if (!succeeded && before.predecessorClaims.isNotEmpty()) {
                coverage += "stale-predecessor-retryable-failure"
            }
            A.MarkPermanentFailureStaleAttempt -> if (!succeeded && before.predecessorClaims.isNotEmpty()) {
                coverage += "stale-predecessor-permanent-failure"
            }
            else -> Unit
        }

        if (beforeRecord?.status == S.EMITTED || beforeRecord?.status == S.FAILED_PERMANENT) {
            if (!succeeded && action == A.MarkEmittedCurrent) coverage += "terminal-emit-rejection"
            if (!succeeded && (action == A.MarkRetryableFailureCurrent || action == A.MarkPermanentFailureCurrent)) {
                coverage += "terminal-failure-rejection"
            }
            if (!succeeded && action.isClaim()) coverage += "terminal-claim-rejection"
            if (after.current == before.current) {
                coverage += if (beforeRecord.status == S.EMITTED) "emitted-absorbing" else "failed-permanent-absorbing"
            }
        }
    }

    private fun generate(seed: Long): List<SovereignOpsAuditOutboxLifecycleAction> =
        SovereignOpsAuditOutboxLifecycleActionGenerator.generate(
            seed = seed,
            initialNow = SovereignOpsAuditOutboxFixtures.T0,
            claimDuration = CLAIM_DURATION,
        )

    private fun A.isClaim(): Boolean = this == A.ClaimWorkerA || this == A.ClaimWorkerB

    private fun A.worker(): String = when (this) {
        A.ClaimWorkerA -> SovereignOpsAuditOutboxLifecycleModel.WORKER_A
        A.ClaimWorkerB -> SovereignOpsAuditOutboxLifecycleModel.WORKER_B
        else -> error("$this is not a claim")
    }

    private companion object {
        val CLAIM_DURATION: Duration = SovereignOpsAuditOutboxFixtures.CLAIM_EXPIRY
    }
}

private typealias A = SovereignOpsAuditOutboxLifecycleAction
private typealias S = SovereignOpsAuditOutboxStatus
