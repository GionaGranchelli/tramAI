package dev.tramai.spring.sovereign.ops

/**
 * Operations for inspecting and managing approval requests.
 *
 * Methods return only safe summaries. Approval tokens, resume tokens,
 * and raw tool arguments are NEVER exposed.
 *
 * Mutations (deny) are guarded by [SovereignOpsProperties.mutationsEnabled].
 */
interface SovereignApprovalOperations {

    /**
     * Retrieve a single approval by ID.
     * @return A safe summary, or null if not found.
     */
    suspend fun getApproval(approvalId: String): SovereignApprovalSummary?

    /**
     * Administratively deny a pending approval by applying a Deny transition.
     *
     * This is the mutation path for rejecting pending approvals through the
     * operations layer. In the underlying store the result is a DENIED status
     * (operationally "cancelled" or "rejected").
     *
     * Requires [SovereignOpsProperties.mutationsEnabled] to be true.
     *
     * @param approvalId The approval to deny.
     * @param actor The identity of the actor performing the denial.
     * @param reason A human-readable reason for the denial.
     * @return The updated approval summary.
     * @throws IllegalStateException if mutations are disabled or validation fails.
     */
    suspend fun denyApproval(
        approvalId: String,
        actor: String,
        reason: String,
    ): SovereignApprovalSummary
}
