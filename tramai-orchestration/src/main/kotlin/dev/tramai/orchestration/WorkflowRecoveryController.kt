package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation

/**
 * Controller for resolving workflows in [WorkflowRecoveryState.Required] state.
 *
 * All methods are fenced by [expectedRevision] — if the checkpoint's revision
 * has changed since it was loaded, the operation throws [WorkflowCheckpointConflictException].
 *
 * NOTE: [confirmCompleted] is intentionally omitted from this PR. Safely advancing
 * a workflow past an unknown step without re-executing requires reconstructing or
 * supplying the step's post-state, which is not yet supported.
 */
interface WorkflowRecoveryController {
    /**
     * Clear the recovery state, allowing the worker to re-attempt the unresolved step
     * on the next poll cycle.
     *
     * The original [WorkflowRecoveryRecord] and unknown attempt record remain in the
     * stores as audit evidence. For [ReplayPolicy.EXTERNALLY_IDEMPOTENT] steps the
     * worker verifies on retry that the recomputed idempotency key matches the stored
     * key (a mismatch re-enters recovery with `IDEMPOTENCY_KEY_MISMATCH`).
     *
     * @return the checkpoint after clearing recovery (revision advanced by one).
     * @throws WorkflowCheckpointConflictException if [expectedRevision] is stale.
     */
    suspend fun retryStep(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    ): WorkflowCheckpoint

    /**
     * Permanently delete the checkpoint, resolving the workflow as failed.
     *
     * The workflow will not be polled again. When a [StepAttemptRecordStore] is
     * available, the resolution reason is recorded onto the unknown attempt record
     * as durable audit evidence; otherwise the operator's reason is not persisted
     * (documented limitation). Calling [retryStep] after [failWorkflow] is not
     * possible because the checkpoint no longer exists.
     *
     * @throws WorkflowCheckpointConflictException if [expectedRevision] is stale.
     */
    suspend fun failWorkflow(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    )
}

/**
 * Default in-memory implementation of [WorkflowRecoveryController].
 *
 * [failWorkflow] deletes the checkpoint directly in a single fenced operation
 * (no clear-before-delete), so a failed delete leaves the checkpoint in
 * [WorkflowRecoveryState.Required] and the workflow stays blocked. When a
 * [StepAttemptRecordStore] is provided, the resolution reason is recorded onto
 * the unknown attempt record as durable audit evidence.
 *
 * Override for a genuinely atomic implementation (e.g. JDBC transaction).
 */
class InMemoryWorkflowRecoveryController(
    private val checkpointStore: WorkflowCheckpointStore,
    private val stepAttemptStore: StepAttemptRecordStore? = null,
) : WorkflowRecoveryController {

    override suspend fun retryStep(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    ): WorkflowCheckpoint {
        val stepName = recoveryStepName(workflowName, workflowId)
        val result = checkpointStore.clearRecovery(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
        )
        recordResolutionEvidence(workflowName, workflowId, stepName, reason)
        return result
    }

    override suspend fun failWorkflow(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    ) {
        val stepName = recoveryStepName(workflowName, workflowId)
        // Delete directly with the ORIGINAL revision. If this fails, the checkpoint
        // stays in Required and the workflow stays blocked — the safe behavior.
        checkpointStore.delete(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
        )
        // Record resolution evidence only AFTER a successful delete — never write
        // "resolved: failed" for a workflow that is still in Required state.
        recordResolutionEvidence(workflowName, workflowId, stepName, reason)
    }

    private suspend fun recoveryStepName(
        workflowName: String,
        workflowId: String,
    ): String? = try {
        val checkpoint = checkpointStore.load(workflowName, workflowId)
        (checkpoint?.recoveryState as? WorkflowRecoveryState.Required)?.record?.stepName
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        System.err.println("Failed to read recovery record for '$workflowName'/'$workflowId': $error")
        null
    }

    private suspend fun recordResolutionEvidence(
        workflowName: String,
        workflowId: String,
        stepName: String?,
        reason: String,
    ) {
        val store = stepAttemptStore ?: return
        if (stepName == null) return
        try {
            val attempt = store.latestStepAttempt(workflowId, stepName) ?: return
            store.updateStepAttempt(
                attempt.copy(
                    resolutionReason = reason,
                    resolutionAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            System.err.println("Failed to record resolution evidence for '$workflowName'/'$workflowId' step '$stepName': $error")
        }
    }
}
