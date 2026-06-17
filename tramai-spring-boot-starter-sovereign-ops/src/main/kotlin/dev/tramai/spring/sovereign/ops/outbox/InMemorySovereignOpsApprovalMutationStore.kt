package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * In-memory implementation of [SovereignOpsApprovalMutationStore].
 *
 * Guarantees safe outbox-first semantics using a [SovereignOpsAuditOutboxStatus.PREPARED]
 * state to close the crash window between outbox append and approval transition:
 *
 * 1. Append outbox record as **PREPARED** — not dispatchable yet
 * 2. Transition the approval — if this fails, mark outbox as FAILED_PERMANENT
 * 3. Mark outbox as **PENDING** — now dispatchable
 *
 * ## Crash safety
 * | Crash point | Result |
 * |---|---|
 * | Before outbox append | No denial, no audit |
 * | After PREPARED append, before transition | No denial, non-dispatchable intent |
 * | After transition, before mark PENDING | Denial committed, PREPARED record (recovery needed) |
 * | After mark PENDING | Safe to dispatch |
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

            // 2. Write outbox record as PREPARED (not dispatchable)
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
                // 4. Mark the outbox as PENDING — now dispatchable
                outboxStore.markReadyForDispatch(
                    outboxId = auditIntent.outboxId,
                    expectedStatus = SovereignOpsAuditOutboxStatus.PREPARED,
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
                    expectedStatus = SovereignOpsAuditOutboxStatus.PREPARED,
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
