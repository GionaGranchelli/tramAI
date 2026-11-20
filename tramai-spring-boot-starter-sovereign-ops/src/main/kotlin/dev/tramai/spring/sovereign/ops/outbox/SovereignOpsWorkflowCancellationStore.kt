package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.spring.sovereign.ops.WorkflowCancellationCommand

/**
 * Observable runtime and lifecycle state of a workflow run for cancellation qualification (0.7.7m).
 *
 * @property revision Current revision of the checkpoint.
 * @property status Current lifecycle status wire name (e.g. "active", "cancelled", "cancelled_uncertain").
 * @property cancelledBy Operator identity that cancelled the run, if cancelled.
 * @property cancelledAtEpochMillis Timestamp of cancellation in epoch millis, if cancelled.
 * @property isCancelled True if the run has reached a terminal cancelled state.
 */
data class SovereignOpsWorkflowRunState(
    val revision: Long,
    val status: String,
    val cancelledBy: String? = null,
    val cancelledAtEpochMillis: Long? = null,
    val isCancelled: Boolean = false,
)

/**
 * Result of a transactional workflow cancellation mutation (0.7.7m).
 *
 * @property revision Resulting durable revision.
 * @property status Resulting terminal status wire name ("cancelled" or "cancelled_uncertain").
 * @property cancelledBy Operator identity recorded as cancelling the run.
 * @property cancelledAtEpochMillis Epoch millisecond timestamp of cancellation.
 * @property alreadyCancelled Whether the run was already in terminal cancellation state.
 */
data class SovereignOpsWorkflowCancellationMutationResult(
    val revision: Long,
    val status: String,
    val cancelledBy: String,
    val cancelledAtEpochMillis: Long,
    val alreadyCancelled: Boolean = false,
)

/**
 * SPI for atomic, transactional workflow cancellation and audit outbox emission (0.7.7m).
 *
 * Implementations MUST commit both the checkpoint cancellation update and the audit outbox record
 * within a single atomic database transaction.
 */
interface SovereignOpsWorkflowCancellationStore {
    /**
     * Inspects current workflow run existence and lifecycle state.
     * Returns null if no checkpoint exists for the given target.
     */
    suspend fun queryRun(
        workflowName: String,
        runId: String,
    ): SovereignOpsWorkflowRunState?

    /**
     * Atomically transitions the workflow run to cancelled and persists the audit outbox intent.
     */
    suspend fun cancelWithAuditIntent(
        command: WorkflowCancellationCommand,
        actor: String,
        actorIssuer: String,
        actorIdentityDigest: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsWorkflowCancellationMutationResult
}

/**
 * Thrown when a workflow cancellation mutation fails due to an optimistic concurrency / CAS conflict.
 */
open class SovereignOpsWorkflowCancellationConflictException(
    message: String = "tramai-workflow-cancellation-conflict",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
