package dev.tramai.core.exception

sealed class ApprovalContinuationStoreException(
    open val approvalId: String,
) : RuntimeException()

class ApprovalContinuationNotFoundException(
    override val approvalId: String,
) : ApprovalContinuationStoreException(approvalId)

class ApprovalContinuationConflictException(
    override val approvalId: String,
) : ApprovalContinuationStoreException(approvalId)

class ApprovalContinuationNotClaimableException(
    override val approvalId: String,
) : ApprovalContinuationStoreException(approvalId)

class ApprovalContinuationNotCompletableException(
    override val approvalId: String,
) : ApprovalContinuationStoreException(approvalId)
