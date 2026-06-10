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

    /**
     * Consume an approved approval request, or return the durable receipt for an exact replay.
     *
     * This method intentionally replaces the previous `consumeApproved(...)` SPI and is therefore
     * a pre-release SPI break for downstream implementers.
     *
     * Fresh consumption succeeds only when all of the following hold:
     * - status == APPROVED
     * - request.version == expectedVersion
     * - consumedAt == null
     * - consumedBy == null
     * - now < expiresAt
     * - presentedTokenDigest matches the stored [ApprovalBinding.approvalTokenDigest] using
     *   constant-time comparison
     *
     * On fresh success, implementations persist [consumedBy], persist consumedAt, increment the
     * version by exactly one, and return [ApprovalConsumptionReceipt.replayed] = false.
     *
     * Exact replay succeeds only when all of the following hold:
     * - status == APPROVED
     * - consumedAt != null
     * - stored consumedBy == command consumedBy
     * - stored version == expectedVersion + 1
     * - presentedTokenDigest matches the stored digest using constant-time comparison
     *
     * On exact replay success, implementations MUST return the existing durable request unchanged,
     * without incrementing version or replacing consumedAt, and set
     * [ApprovalConsumptionReceipt.replayed] = true.
     *
     * Implementations must reject every non-exact replay safely. Exact replay receipts may be
     * returned after approval expiry; the continuation store remains authoritative for execution
     * expiry.
     */
    suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalConsumptionReceipt
}

data class ApprovalConsumptionReceipt(
    val request: ApprovalRequest,
    val replayed: Boolean,
)
