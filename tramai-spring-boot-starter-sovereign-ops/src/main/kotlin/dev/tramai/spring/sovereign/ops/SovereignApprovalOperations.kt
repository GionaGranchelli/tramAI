package dev.tramai.spring.sovereign.ops

/**
 * Operations for inspecting and managing approval requests.
 *
 * Methods return only safe summaries. Approval tokens, resume tokens,
 * and raw tool arguments are NEVER exposed.
 *
 * Mutations (cancel/transition) are guarded by [SovereignOpsProperties.mutationsEnabled].
 */
interface SovereignApprovalOperations {

    /**
     * Retrieve a single approval by ID.
     * @return A safe summary, or null if not found.
     */
    suspend fun getApproval(approvalId: String): SovereignApprovalSummary?

    /**
     * Cancel a pending approval.
     *
     * Requires [SovereignOpsProperties.mutationsEnabled] to be true.
     *
     * @param approvalId The approval to cancel.
     * @param actor The identity of the actor performing the cancellation.
     * @param reason A human-readable reason for the cancellation.
     * @return The updated approval summary.
     * @throws IllegalStateException if mutations are disabled or validation fails.
     */
    suspend fun cancelApproval(
        approvalId: String,
        actor: String,
        reason: String,
    ): SovereignApprovalSummary
}
