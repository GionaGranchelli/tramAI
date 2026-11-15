package dev.tramai.spring.sovereign.ops

import dev.tramai.orchestration.WorkflowLifecycleStatus

/**
 * Command to cancel a governed workflow run (0.7.7m).
 *
 * [expectedRevision] is a mandatory precondition (`>= 0`).
 * [reason] is mandatory and explains the administrative cancellation.
 */
data class WorkflowCancellationCommand(
    val workflowName: String,
    val runId: String,
    val expectedRevision: Long,
    val reason: String,
    val correlationId: String? = null,
) {
    init {
        require(workflowName.isNotBlank()) { "workflowName must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(expectedRevision >= 0) { "expectedRevision must be non-negative" }
        require(reason.isNotBlank()) { "reason must not be blank" }
    }
}

/**
 * The typed result of an authoritative workflow cancellation attempt (0.7.7m).
 */
sealed interface WorkflowCancellationResult {
    /**
     * Cancellation successfully committed or was already committed.
     *
     * [alreadyCancelled] is true if the run was already in a terminal cancellation state
     * (idempotent repeat call).
     */
    data class Cancelled(
        val workflowName: String,
        val runId: String,
        val revision: Long,
        val status: WorkflowLifecycleStatus,
        val cancelledBy: String,
        val cancelledAtEpochMillis: Long,
        val alreadyCancelled: Boolean = false,
    ) : WorkflowCancellationResult

    /**
     * Stale revision: caller presented [expectedRevision], but current revision is [currentRevision].
     */
    data class Stale(
        val workflowName: String,
        val runId: String,
        val expectedRevision: Long,
        val currentRevision: Long,
    ) : WorkflowCancellationResult

    /**
     * Target workflow run was not found.
     */
    data class NotFound(
        val workflowName: String,
        val runId: String,
    ) : WorkflowCancellationResult

    /**
     * Cancellation rejected due to conflicting state transition or database constraint.
     */
    data class Conflict(
        val workflowName: String,
        val runId: String,
        val reason: String,
    ) : WorkflowCancellationResult

    /**
     * Operator lacks authority: missing capability or target authorization rejected.
     */
    data class Unauthorized(
        val workflowName: String,
        val runId: String,
        val reason: WorkflowUnauthorizedReason,
    ) : WorkflowCancellationResult
}

/**
 * The domain control plane interface for authoritative workflow run cancellation.
 */
interface WorkflowCancellationControlPlane {
    suspend fun cancel(
        command: WorkflowCancellationCommand,
        authority: OperatorAuthority,
    ): WorkflowCancellationResult
}
