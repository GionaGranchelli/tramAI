package dev.tramai.core.approval

interface ApprovalContinuationStore {
    suspend fun create(continuation: ApprovalContinuation): ApprovalContinuation
    suspend fun get(approvalId: String): ApprovalContinuation?
    suspend fun claimForExecution(approvalId: String, expectedVersion: Long, claimedBy: String): ApprovalContinuation
    suspend fun complete(approvalId: String, expectedVersion: Long): ApprovalContinuation
    suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation
    suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation
}
