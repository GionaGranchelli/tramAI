package dev.tramai.core.approval

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
    ): ApprovalContinuation

    suspend fun expire(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation

    suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation
}
