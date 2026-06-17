package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * In-memory implementation of [SovereignOpsApprovalMutationStore].
 *
 * Guarantees atomic denial + outbox append by managing both the approval
 * state and the outbox record under the same per-approval lock:
 *
 * 1. Outbox record is appended first — if this fails, the approval
 *    is never mutated.
 * 2. Approval transition happens second, writing through to the
 *    underlying [ApprovalStore] for dual-consistency: the mutation
 *    store is the source of truth for atomicity, but the
 *    [ApprovalStore] is kept in sync so read operations see
 *    the same state.
 *
 * If the transition fails (version conflict from a concurrent mutation
 * that bypassed this lock), the outbox record is left in a
 * [SovereignOpsAuditOutboxStatus.FAILED_PERMANENT] state — this is a
 * safety net for code paths that don't go through this mutation store.
 *
 * @param approvalStore The underlying [ApprovalStore] (kept in sync for reads).
 * @param outboxStore The outbox store for audit intent records.
 */
class InMemorySovereignOpsApprovalMutationStore(
    private val approvalStore: ApprovalStore,
    private val outboxStore: SovereignOpsAuditOutboxStore,
) : SovereignOpsApprovalMutationStore {

    /** Per-approval locks for atomic transition + outbox. */
    private val approvalLocks = ConcurrentHashMap<String, ReentrantLock>()

    override suspend fun denyApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult {
        val lock = approvalLocks.computeIfAbsent(approvalId) { ReentrantLock() }
        lock.lock()
        try {
            // 1. Re-read under lock for consistency
            val current = approvalStore.get(approvalId)
                ?: throw IllegalStateException("tramai-sovereign-ops-invalid-approval-id")
            require(current.version == expectedVersion) {
                "tramai-sovereign-ops-approval-version-conflict"
            }

            // 2. Write outbox record FIRST
            //    If this fails, the approval is never mutated.
            outboxStore.append(auditIntent)

            // 3. Transition the approval SECOND
            //    Optimistic concurrency in ApprovalStore catches any
            //    external mutation that bypassed this lock.
            return try {
                val updated = approvalStore.transition(
                    approvalId = approvalId,
                    expectedVersion = expectedVersion,
                    transition = ApprovalTransition.Deny(decidedBy = actor, comment = reason),
                )
                SovereignOpsApprovalMutationResult(
                    approval = updated,
                    auditOutboxRecord = auditIntent,
                )
            } catch (e: Exception) {
                // Transition failed after outbox append. Mark the outbox
                // as permanently failed — the audit intent is orphaned.
                outboxStore.markFailed(
                    outboxId = auditIntent.outboxId,
                    expectedStatus = SovereignOpsAuditOutboxStatus.PENDING,
                    errorCode = e::class.simpleName ?: "transition-failed",
                    retryable = false,
                )
                throw e
            }
        } finally {
            lock.unlock()
        }
    }
}
