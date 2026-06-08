package dev.tramai.core.exception

sealed class ApprovalContinuationStoreException(
    open val approvalId: String,
) : RuntimeException()

class ApprovalContinuationNotFoundException(
    override val approvalId: String,
) : ApprovalContinuationStoreException(approvalId) {
    override val message: String get() = "Continuation not found: $approvalId"
}

class ApprovalContinuationConflictException(
    override val approvalId: String,
) : ApprovalContinuationStoreException(approvalId) {
    override val message: String get() = "Continuation conflict: $approvalId"
}

class ApprovalContinuationNotClaimableException(
    override val approvalId: String,
) : ApprovalContinuationStoreException(approvalId) {
    override val message: String get() = "Continuation not claimable: $approvalId"
}

class ApprovalContinuationNotCompletableException(
    override val approvalId: String,
) : ApprovalContinuationStoreException(approvalId) {
    override val message: String get() = "Continuation not completable: $approvalId"
}
