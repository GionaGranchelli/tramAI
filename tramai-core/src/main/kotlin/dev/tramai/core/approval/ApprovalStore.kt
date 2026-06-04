package dev.tramai.core.approval

/**
 * SPI for persisting and transitioning approval requests.
 *
 * Implementations must be thread-safe and provide atomic
 * read-modify-write semantics for [transition].
 */
interface ApprovalStore {

    /**
     * Create a new approval request.
     *
     * @param request The request to create; must be PENDING with version 0 and no decision fields set.
     * @return The stored request, identical to the input on success.
     * @throws IllegalArgumentException if validation fails (blank fields, already exists, etc.).
     */
    suspend fun create(request: ApprovalRequest): ApprovalRequest

    /**
     * Retrieve an approval request by its ID.
     *
     * @param approvalId The unique identifier of the approval.
     * @return The matching [ApprovalRequest], or `null` if no such approval exists.
     */
    suspend fun get(approvalId: String): ApprovalRequest?

    /**
     * Transition an existing approval to a new status.
     *
     * Uses optimistic concurrency via [expectedVersion] — the transition succeeds only
     * if the current version matches. Returns the updated request atomically.
     *
     * @param approvalId The approval to transition.
     * @param expectedVersion The version the caller expects the approval to be at.
     * @param transition The transition to apply ([Approve], [Deny], or [Timeout]).
     * @return The updated [ApprovalRequest] after applying the transition.
     * @throws IllegalArgumentException if the approval does not exist or the version does not match.
     * @throws IllegalApprovalTransitionException if the transition is not valid for the current status.
     */
    suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest
}

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
