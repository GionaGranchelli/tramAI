package dev.tramai.core.approval

import java.time.Instant

interface ApprovalContinuationStore {
    suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation

    suspend fun get(approvalId: String): ApprovalContinuation?

    suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ClaimedApprovalContinuation

    suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ): ApprovalContinuation

    suspend fun expire(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation

    suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation

    suspend fun findStaleClaimed(
        claimedBefore: Instant,
        limit: Int,
    ): List<ApprovalContinuation>

    suspend fun forceCancelClaimed(
        approvalId: String,
        expectedVersion: Long,
        cancelledBy: String,
        reasonCode: String,
    ): ApprovalContinuation

    suspend fun sweepExpired(): Int
}
