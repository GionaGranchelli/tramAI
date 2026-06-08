package dev.tramai.core.approval

/**
 * Result of [ApprovalContinuationStore.claimForExecution].
 *
 * Carries the continuation metadata alongside the released raw arguments.
 * This is the only path through which raw arguments are exposed to callers.
 */
data class ClaimedApprovalContinuation(
    val continuation: ApprovalContinuation,
    val arguments: SensitiveToolArguments,
)
