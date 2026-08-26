package dev.tramai.testing.persistence.outbox

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import java.time.Duration
import java.time.Instant

/** Pure deterministic oracle for the audit-outbox delivery state machine. */
internal data class SovereignOpsAuditOutboxLifecycleModel(
    val now: Instant,
    val current: ModeledOutbox?,
    val predecessorClaims: List<ModeledClaim>,
    val auditFields: ModeledAuditFields,
) {

    companion object {
        const val WORKER_A = "worker-A"
        const val WORKER_B = "worker-B"
        const val PREPARED_ERROR = "prepared-permanent"
        const val RETRYABLE_ERROR = "retryable-failure"
        const val PERMANENT_ERROR = "permanent-failure"
        const val STALE_RETRYABLE_ERROR = "stale-retryable-failure"
        const val STALE_PERMANENT_ERROR = "stale-permanent-failure"

        fun absent(
            now: Instant,
            auditFields: ModeledAuditFields = ModeledAuditFields.fixture(),
        ): SovereignOpsAuditOutboxLifecycleModel = SovereignOpsAuditOutboxLifecycleModel(
            now = now,
            current = null,
            predecessorClaims = emptyList(),
            auditFields = auditFields,
        )
    }

    fun apply(
        action: SovereignOpsAuditOutboxLifecycleAction,
        claimDuration: Duration,
    ): SovereignOpsAuditOutboxLifecycleOutcome {
        val outcome = when (action) {
            SovereignOpsAuditOutboxLifecycleAction.AppendPrepared -> appendPrepared()
            SovereignOpsAuditOutboxLifecycleAction.MarkReady -> markReady()
            SovereignOpsAuditOutboxLifecycleAction.MarkPreparedPermanentFailure ->
                markFailed(expectedAttemptCount = 0, retryable = false, errorCode = PREPARED_ERROR)
            SovereignOpsAuditOutboxLifecycleAction.ClaimWorkerA -> claim(WORKER_A, claimDuration)
            SovereignOpsAuditOutboxLifecycleAction.ClaimWorkerB -> claim(WORKER_B, claimDuration)
            SovereignOpsAuditOutboxLifecycleAction.MarkEmittedCurrent ->
                markEmitted(current?.attemptCount)
            SovereignOpsAuditOutboxLifecycleAction.MarkRetryableFailureCurrent ->
                markFailed(current?.attemptCount, retryable = true, errorCode = RETRYABLE_ERROR)
            SovereignOpsAuditOutboxLifecycleAction.MarkPermanentFailureCurrent ->
                markFailed(current?.attemptCount, retryable = false, errorCode = PERMANENT_ERROR)
            SovereignOpsAuditOutboxLifecycleAction.MarkEmittedStaleAttempt ->
                markEmitted(predecessorClaims.firstOrNull()?.generation)
            SovereignOpsAuditOutboxLifecycleAction.MarkRetryableFailureStaleAttempt ->
                markFailed(predecessorClaims.firstOrNull()?.generation, retryable = true, errorCode = STALE_RETRYABLE_ERROR)
            SovereignOpsAuditOutboxLifecycleAction.MarkPermanentFailureStaleAttempt ->
                markFailed(predecessorClaims.firstOrNull()?.generation, retryable = false, errorCode = STALE_PERMANENT_ERROR)
            SovereignOpsAuditOutboxLifecycleAction.AdvanceBeforeClaimExpiry -> advanceBeforeExpiry()
            SovereignOpsAuditOutboxLifecycleAction.AdvanceToExactClaimExpiry -> advanceToExactExpiry()
            SovereignOpsAuditOutboxLifecycleAction.AdvancePastClaimExpiry -> advancePastExpiry()
            SovereignOpsAuditOutboxLifecycleAction.ObserveCurrent ->
                SovereignOpsAuditOutboxLifecycleOutcome.Success(this)
        }
        validateTransition(action, outcome)
        return outcome
    }

    private fun appendPrepared(): SovereignOpsAuditOutboxLifecycleOutcome =
        if (current != null) {
            rejected()
        } else {
            success(
                copy(
                    current = ModeledOutbox(
                        auditFields = auditFields,
                        status = SovereignOpsAuditOutboxStatus.PREPARED,
                    ),
                ),
            )
        }

    private fun markReady(): SovereignOpsAuditOutboxLifecycleOutcome {
        val record = current ?: return rejected()
        if (record.status != SovereignOpsAuditOutboxStatus.PREPARED) return rejected()
        return success(copy(current = record.copy(status = SovereignOpsAuditOutboxStatus.PENDING)))
    }

    private fun claim(
        worker: String,
        claimDuration: Duration,
    ): SovereignOpsAuditOutboxLifecycleOutcome {
        val record = current ?: return rejected()
        if (!record.isClaimable(now)) return rejected()
        val previousClaim = record.modeledClaimOrNull()
        val nextAttempt = record.attemptCount + 1
        val updated = record.copy(
            status = SovereignOpsAuditOutboxStatus.EMITTING,
            attemptCount = nextAttempt,
            lastErrorCode = null,
            claimedBy = worker,
            claimedAt = now,
            claimExpiresAt = now.plus(claimDuration),
        )
        return success(
            copy(
                current = updated,
                predecessorClaims = predecessorClaims + listOfNotNull(previousClaim),
            ),
        )
    }

    private fun markEmitted(expectedAttemptCount: Int?): SovereignOpsAuditOutboxLifecycleOutcome {
        val record = current ?: return rejected()
        if (
            record.status != SovereignOpsAuditOutboxStatus.EMITTING ||
            expectedAttemptCount == null ||
            record.attemptCount != expectedAttemptCount
        ) {
            return rejected()
        }
        return success(
            copy(
                current = record.copy(
                    status = SovereignOpsAuditOutboxStatus.EMITTED,
                    emittedAt = now,
                ),
            ),
        )
    }

    private fun markFailed(
        expectedAttemptCount: Int?,
        retryable: Boolean,
        errorCode: String,
    ): SovereignOpsAuditOutboxLifecycleOutcome {
        val record = current ?: return rejected()
        val legalStatus = if (retryable) {
            record.status == SovereignOpsAuditOutboxStatus.EMITTING
        } else {
            record.status == SovereignOpsAuditOutboxStatus.EMITTING ||
                record.status == SovereignOpsAuditOutboxStatus.PREPARED
        }
        if (expectedAttemptCount == null || !legalStatus || record.attemptCount != expectedAttemptCount) {
            return rejected()
        }
        val target = if (retryable) {
            SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE
        } else {
            SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
        }
        return success(copy(current = record.copy(status = target, lastErrorCode = errorCode)))
    }

    private fun advanceBeforeExpiry(): SovereignOpsAuditOutboxLifecycleOutcome.Success {
        val expiry = current?.takeIf { it.status == SovereignOpsAuditOutboxStatus.EMITTING }?.claimExpiresAt
        val target = expiry?.minusMillis(1)?.let { maxOf(now, it) } ?: now.plusMillis(1)
        return success(copy(now = target))
    }

    private fun advanceToExactExpiry(): SovereignOpsAuditOutboxLifecycleOutcome.Success {
        val expiry = current?.takeIf { it.status == SovereignOpsAuditOutboxStatus.EMITTING }?.claimExpiresAt
        return success(copy(now = expiry?.let { maxOf(now, it) } ?: now.plusMillis(1)))
    }

    private fun advancePastExpiry(): SovereignOpsAuditOutboxLifecycleOutcome.Success {
        val expiry = current?.takeIf { it.status == SovereignOpsAuditOutboxStatus.EMITTING }?.claimExpiresAt
        return success(copy(now = expiry?.plusMillis(1)?.let { maxOf(now, it) } ?: now.plusMillis(1)))
    }

    private fun validateTransition(
        action: SovereignOpsAuditOutboxLifecycleAction,
        outcome: SovereignOpsAuditOutboxLifecycleOutcome,
    ) {
        val next = when (outcome) {
            is SovereignOpsAuditOutboxLifecycleOutcome.Success -> outcome.next
            is SovereignOpsAuditOutboxLifecycleOutcome.Failure -> outcome.unchanged
        }
        check(next.invariants().isEmpty()) {
            "${action.describe()} violated invariants: ${next.invariants().joinToString()}"
        }
        check(next.auditFields == auditFields) { "audit fields changed" }
        if (outcome is SovereignOpsAuditOutboxLifecycleOutcome.Failure) {
            check(outcome.unchanged == this) { "failed mutation changed the model" }
            return
        }

        val beforeAttempt = current?.attemptCount ?: 0
        val afterAttempt = next.current?.attemptCount ?: 0
        check(afterAttempt >= beforeAttempt) { "attemptCount decreased" }
        val successfulClaim = action.isClaim() && next.current?.attemptCount == beforeAttempt + 1
        if (successfulClaim) {
            check(afterAttempt == beforeAttempt + 1) { "successful claim did not increment exactly once" }
        } else {
            check(afterAttempt == beforeAttempt) { "non-claim action changed attemptCount" }
        }
        check(next.current?.auditFields == current?.auditFields || current == null) {
            "record audit fields changed"
        }
    }

    fun invariants(): List<String> {
        val violations = mutableListOf<String>()
        val record = current ?: return violations
        if (record.auditFields != auditFields) violations += "record audit fields differ from model audit fields"
        if (record.attemptCount < 0) violations += "negative attemptCount"
        if (
            record.status == SovereignOpsAuditOutboxStatus.PREPARED ||
            record.status == SovereignOpsAuditOutboxStatus.PENDING
        ) {
            if (record.attemptCount != 0) violations += "${record.status} attemptCount is not zero"
        }
        if (record.status == SovereignOpsAuditOutboxStatus.EMITTING) {
            if (record.attemptCount < 1) violations += "EMITTING attemptCount is less than one"
            if (record.claimedBy == null || record.claimedAt == null || record.claimExpiresAt == null) {
                violations += "EMITTING claim fields are incomplete"
            } else if (!record.claimExpiresAt.isAfter(record.claimedAt)) {
                violations += "claim expiry is not after claim timestamp"
            }
        }
        if (record.status == SovereignOpsAuditOutboxStatus.EMITTED) {
            if (record.emittedAt == null) violations += "EMITTED has no emittedAt"
        } else if (record.emittedAt != null) {
            violations += "non-EMITTED record has emittedAt"
        }
        if (predecessorClaims.any { it.generation >= record.attemptCount }) {
            violations += "predecessor generation is not older than current"
        }
        if (predecessorClaims.map { it.generation }.toSet().size != predecessorClaims.size) {
            violations += "duplicate predecessor generation"
        }
        return violations
    }

    private fun success(next: SovereignOpsAuditOutboxLifecycleModel) =
        SovereignOpsAuditOutboxLifecycleOutcome.Success(next)

    private fun rejected() = SovereignOpsAuditOutboxLifecycleOutcome.Failure(this)

    private fun SovereignOpsAuditOutboxLifecycleAction.isClaim(): Boolean =
        this == SovereignOpsAuditOutboxLifecycleAction.ClaimWorkerA ||
            this == SovereignOpsAuditOutboxLifecycleAction.ClaimWorkerB

    private fun ModeledOutbox.isClaimable(at: Instant): Boolean = when (status) {
        SovereignOpsAuditOutboxStatus.PENDING,
        SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE,
        -> true
        SovereignOpsAuditOutboxStatus.EMITTING -> claimExpiresAt?.isBefore(at) == true
        SovereignOpsAuditOutboxStatus.PREPARED,
        SovereignOpsAuditOutboxStatus.EMITTED,
        SovereignOpsAuditOutboxStatus.FAILED_PERMANENT,
        -> false
    }

    private fun ModeledOutbox.modeledClaimOrNull(): ModeledClaim? {
        val worker = claimedBy ?: return null
        val claimed = claimedAt ?: return null
        val expires = claimExpiresAt ?: return null
        return ModeledClaim(attemptCount, worker, claimed, expires)
    }

}

internal data class ModeledClaim(
    val generation: Int,
    val claimedBy: String,
    val claimedAt: Instant,
    val claimExpiresAt: Instant,
)

internal data class ModeledOutbox(
    val auditFields: ModeledAuditFields,
    val status: SovereignOpsAuditOutboxStatus,
    val attemptCount: Int = 0,
    val lastErrorCode: String? = null,
    val claimedBy: String? = null,
    val claimedAt: Instant? = null,
    val claimExpiresAt: Instant? = null,
    val emittedAt: Instant? = null,
)

internal data class ModeledAuditFields(
    val outboxId: String,
    val aggregateType: String,
    val aggregateIdDigest: String,
    val operation: String,
    val eventKey: String,
    val actor: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val approvalStatus: String,
    val approvalVersion: Long?,
    val reasonDigest: String,
    val reasonLength: Int,
    val createdAt: Instant,
) {
    companion object {
        fun fixture(outboxId: String = "modeled-outbox"): ModeledAuditFields = ModeledAuditFields(
            outboxId = outboxId,
            aggregateType = "approval",
            aggregateIdDigest = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            operation = "denyApproval",
            eventKey = "event-$outboxId",
            actor = "operator-1",
            workflowRunId = "workflow-$outboxId",
            correlationId = "correlation-$outboxId",
            approvalStatus = "DENIED",
            approvalVersion = 7L,
            reasonDigest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            reasonLength = 42,
            createdAt = SovereignOpsAuditOutboxFixtures.T0,
        )
    }
}

internal sealed interface SovereignOpsAuditOutboxLifecycleOutcome {
    data class Success(val next: SovereignOpsAuditOutboxLifecycleModel) : SovereignOpsAuditOutboxLifecycleOutcome
    data class Failure(val unchanged: SovereignOpsAuditOutboxLifecycleModel) : SovereignOpsAuditOutboxLifecycleOutcome
}

internal enum class SovereignOpsAuditOutboxLifecycleAction {
    AppendPrepared,
    MarkReady,
    MarkPreparedPermanentFailure,
    ClaimWorkerA,
    ClaimWorkerB,
    MarkEmittedCurrent,
    MarkRetryableFailureCurrent,
    MarkPermanentFailureCurrent,
    MarkEmittedStaleAttempt,
    MarkRetryableFailureStaleAttempt,
    MarkPermanentFailureStaleAttempt,
    AdvanceBeforeClaimExpiry,
    AdvanceToExactClaimExpiry,
    AdvancePastClaimExpiry,
    ObserveCurrent,
    ;

    fun describe(): String = name
}
