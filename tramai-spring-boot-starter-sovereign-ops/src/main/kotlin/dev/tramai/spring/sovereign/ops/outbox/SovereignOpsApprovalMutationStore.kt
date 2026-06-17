package dev.tramai.spring.sovereign.ops.outbox

/**
 * Atomic approval mutation store that guarantees:
 *
 * **No approval denial is committed unless an audit intent was appended first.**
 *
 * The outbox record is appended before the approval transition under the same
 * critical section (lock or persistence boundary). If the outbox append fails,
 * the approval is never mutated. If the transition fails after a successful
 * outbox append, the outbox record is marked as
 * [SovereignOpsAuditOutboxStatus.FAILED_PERMANENT] — an orphaned audit intent.
 */
interface SovereignOpsApprovalMutationStore {

    /**
     * Atomically deny an approval and persist the audit outbox record.
     *
     * @param approvalId The approval to deny.
     * @param expectedVersion The version expected for optimistic concurrency.
     * @param actor The identity of the actor performing the denial.
     * @param reason The human-readable reason (never stored raw — only digested).
     * @param auditIntent The outbox record to append atomically with the transition.
     * @return The updated approval and the persisted outbox record.
     * @throws IllegalStateException if the approval does not exist or version mismatches.
     * @throws IllegalStateException if the outbox append fails (approval remains unchanged).
     */
    suspend fun denyApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult
}
