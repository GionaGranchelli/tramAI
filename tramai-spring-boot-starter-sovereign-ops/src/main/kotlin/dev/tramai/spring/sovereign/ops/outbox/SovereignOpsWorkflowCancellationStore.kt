package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.orchestration.WorkflowLifecycleState
import dev.tramai.spring.sovereign.ops.WorkflowCancellationCommand

/**
 * Result of a transactional workflow cancellation mutation.
 *
 * @property state The resulting durable lifecycle state.
 * @property alreadyCancelled Whether the run was already in terminal cancellation state.
 */
data class SovereignOpsWorkflowCancellationMutationResult(
    val state: WorkflowLifecycleState,
    val alreadyCancelled: Boolean = false,
)

/**
 * SPI for atomic, transactional workflow cancellation and audit outbox emission (0.7.7m).
 *
 * Implementations MUST commit both the checkpoint cancellation update and the audit outbox record
 * within a single atomic database transaction.
 */
interface SovereignOpsWorkflowCancellationStore {
    suspend fun cancelWithAuditIntent(
        command: WorkflowCancellationCommand,
        actor: String,
        actorIssuer: String,
        actorIdentityDigest: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsWorkflowCancellationMutationResult
}
