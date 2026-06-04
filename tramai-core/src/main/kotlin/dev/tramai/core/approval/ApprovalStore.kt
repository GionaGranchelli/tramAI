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
    fun targetStatus(): ApprovalStatus

    data class Approve(
        val decidedBy: String,
        val comment: String? = null,
    ) : ApprovalTransition {
        override fun targetStatus() = ApprovalStatus.APPROVED
    }

    data class Deny(
        val decidedBy: String,
        val comment: String? = null,
    ) : ApprovalTransition {
        override fun targetStatus() = ApprovalStatus.DENIED
    }

    data object Timeout : ApprovalTransition {
        override fun targetStatus() = ApprovalStatus.TIMED_OUT
    }
}
