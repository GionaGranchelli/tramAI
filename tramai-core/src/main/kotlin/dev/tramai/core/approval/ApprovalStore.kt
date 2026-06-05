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
     * Consume an approved approval request exactly once.
     *
     * Validates that the presented token digest matches the stored [ApprovalBinding.approvalTokenDigest]
     * using constant-time comparison. On success, marks the request as consumed by persisting
     * [consumedBy] and [consumedAt] and incrementing the version.
     *
     * @param approvalId The approval to consume.
     * @param expectedVersion The version the caller expects.
     * @param presentedTokenDigest The SHA-256 digest of the approval token presented by the caller.
     * @param consumedBy Identifier of the consuming actor. Must not be blank, must not exceed
     *                    the maximum ID length, must not contain control characters,
     *                    and must not have surrounding whitespace.
     * @return The updated [ApprovalRequest] with consumption fields set.
     * @throws IllegalArgumentException if the approval does not exist, version mismatch,
     *         status is not APPROVED, already consumed, expired, or token digest does not match.
     */
    suspend fun consumeApproved(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalRequest
}
