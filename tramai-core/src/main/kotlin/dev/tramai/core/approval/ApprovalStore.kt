package dev.tramai.core.approval

interface ApprovalStore {
    suspend fun create(request: ApprovalRequest): ApprovalRequest
    suspend fun get(approvalId: String): ApprovalRequest?
    suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest
}

sealed interface ApprovalTransition {
    data class Approve(
        val decidedBy: String?,
        val comment: String?,
    ) : ApprovalTransition

    data class Deny(
        val decidedBy: String?,
        val comment: String?,
    ) : ApprovalTransition

    data object Timeout : ApprovalTransition
}
