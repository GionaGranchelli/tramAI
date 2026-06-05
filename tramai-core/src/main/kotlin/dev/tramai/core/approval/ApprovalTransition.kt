package dev.tramai.core.approval

/**
 * A transition to apply to an existing approval request.
 *
 * Each variant specifies the [targetStatus] it produces. The store validates
 * whether the transition is legal for the current [ApprovalStatus].
 */
sealed interface ApprovalTransition {
    /**
     * The status that this transition would produce.
     */
    fun targetStatus(): ApprovalStatus

    /**
     * Approve the request. Requires [decidedBy] to identify the approver.
     *
     * @property decidedBy Identifier of the approving actor (must not be blank).
     * @property comment Optional human-readable justification.
     */
    data class Approve(
        val decidedBy: String,
        val comment: String? = null,
    ) : ApprovalTransition {
        override fun targetStatus() = ApprovalStatus.APPROVED
    }

    /**
     * Deny the request. Requires [decidedBy] to identify the denying actor.
     *
     * @property decidedBy Identifier of the denying actor (must not be blank).
     * @property comment Optional human-readable justification.
     */
    data class Deny(
        val decidedBy: String,
        val comment: String? = null,
    ) : ApprovalTransition {
        override fun targetStatus() = ApprovalStatus.DENIED
    }

    /**
     * Time out the request. Only valid when the request has expired
     * (i.e., [ApprovalRequest.expiresAt] is in the past).
     */
    data object Timeout : ApprovalTransition {
        override fun targetStatus() = ApprovalStatus.TIMED_OUT
    }
}
