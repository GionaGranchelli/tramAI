package dev.tramai.core.approval

import dev.tramai.core.approval.SafeActorIdPolicy
import java.time.Instant

/**
 * Safe reason-code pattern for privileged recovery operations.
 * Matches lowercase alphanumeric start, then lowercase alphanumeric,
 * underscore, colon, period, or hyphen, up to 63 characters total.
 */
const val SAFE_REASON_CODE_PATTERN = "[a-z0-9][a-z0-9._:-]{0,63}"

data class ForceCancelClaimedCommand(
    val approvalId: String,
    val expectedVersion: Long,
    val operatorId: String,
    val reasonCode: String,
) {
    init {
        require(approvalId.isNotBlank()) { "approvalId must not be blank" }
        SafeActorIdPolicy.validateActorId(operatorId, "operatorId")
        require(reasonCode.isNotBlank()) { "reasonCode must not be blank" }
        require(expectedVersion >= 0) { "expectedVersion must be non-negative" }
    }
}

/**
 * Trusted recovery coordinator for stale CLAIMED continuations.
 *
 * A CLAIMED continuation means the execution outcome may be uncertain.
 * Force cancellation is privileged and explicit — it does not imply
 * that the external side effect did not occur. Operator reconciliation
 * may be required against the external system.
 *
 * Automatic retry and automatic reclaim remain forbidden.
 */
interface ApprovalRecoveryCoordinator {
    /**
     * Find stale CLAIMED continuations whose [ApprovalContinuation.claimedAt]
     * is at or before [claimedBefore].
     *
     * Read-only operation. Returns metadata only — no raw arguments.
     */
    suspend fun findStaleClaims(
        claimedBefore: Instant,
        limit: Int,
    ): List<ApprovalContinuation>

    /**
     * Privileged explicit transition of a CLAIMED continuation to
     * CANCELLED_UNCERTAIN.
     *
     * @throws ApprovalContinuationNotFoundException if the approval does not exist
     * @throws ApprovalAuthorizationException if version is stale or status is not CLAIMED
     */
    suspend fun forceCancelClaimed(
        command: ForceCancelClaimedCommand,
    ): ApprovalContinuation
}
