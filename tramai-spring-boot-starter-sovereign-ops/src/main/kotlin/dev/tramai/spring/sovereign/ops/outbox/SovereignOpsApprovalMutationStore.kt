package dev.tramai.spring.sovereign.ops.outbox

/**
 * Atomic approval mutation store that guarantees:
 *
 * **No durable approval transition without a durable audit outbox record.**
 *
 * The mutation and outbox append are performed under the same critical
 * section (lock or persistence boundary). If either fails, neither
 * is committed.
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
