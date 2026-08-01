package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation

/**
 * Raised when a recovery-resolution operation ([WorkflowRecoveryController.retryStep]
 * or [WorkflowRecoveryController.failWorkflow]) is invoked on a checkpoint that is
 * not in [WorkflowRecoveryState.Required] state.
 */
class WorkflowRecoveryStateException(
    message: String,
) : RuntimeException(message)

/**
 * Controller for resolving workflows in [WorkflowRecoveryState.Required] state.
 *
 * All methods are fenced by [expectedRevision] — if the checkpoint's revision
 * has changed since it was loaded, the operation throws [WorkflowCheckpointConflictException].
 * If the checkpoint is not in [WorkflowRecoveryState.Required] state, the operation
 * throws [WorkflowRecoveryStateException].
 *
 * NOTE: [confirmCompleted] is intentionally omitted from this PR. Safely advancing
 * a workflow past an unknown step without re-executing requires reconstructing or
 * supplying the step's post-state, which is not yet supported.
 */
interface WorkflowRecoveryController {
    /**
     * Resolve the unresolved step attempt and clear the recovery state, allowing the
     * worker to re-attempt the step with a NEW attempt on the next poll cycle.
     *
     * The exact attempt referenced by the recovery record is marked [StepAttemptStatus.FAILED]
     * with the operator's [reason] and timestamp BEFORE recovery is cleared — if clearing
     * fails, the checkpoint stays in `Required` (safe). The failed attempt remains in the
     * attempt store as audit evidence.
     *
     * For [ReplayPolicy.EXTERNALLY_IDEMPOTENT] steps the worker verifies on retry that
     * the recomputed idempotency key matches the stored key (a mismatch re-enters recovery
     * with `IDEMPOTENCY_KEY_MISMATCH`).
     *
     * @return the checkpoint after clearing recovery (revision advanced by one).
     * @throws WorkflowCheckpointConflictException if [expectedRevision] is stale.
     * @throws WorkflowRecoveryStateException if the checkpoint is not in [WorkflowRecoveryState.Required].
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
     * The workflow will not be polled again. When a [StepAttemptRecordStore] is available,
     * the resolution reason is recorded onto the exact failed attempt record as best-effort
     * audit evidence (storage failures are logged, not propagated); otherwise the operator's
     * reason is not persisted (documented limitation). Calling [retryStep] after
     * [failWorkflow] is not possible because the checkpoint no longer exists.
     *
     * @throws WorkflowCheckpointConflictException if [expectedRevision] is stale.
     * @throws WorkflowRecoveryStateException if the checkpoint is not in [WorkflowRecoveryState.Required].
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
 * Both operations first load and validate the checkpoint (exists, revision matches,
 * recovery state is [WorkflowRecoveryState.Required]) via [loadRequiredCheckpoint].
 *
 * [failWorkflow] deletes the checkpoint directly in a single fenced operation
 * (no clear-before-delete), so a failed delete leaves the checkpoint in
 * [WorkflowRecoveryState.Required] and the workflow stays blocked. When a
 * [StepAttemptRecordStore] is provided, the resolution reason is recorded onto
 * the exact unresolved attempt as best-effort audit evidence.
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
        val (_, record) = loadRequiredCheckpoint(workflowName, workflowId, expectedRevision)
        // Resolve the exact unresolved attempt BEFORE unblocking the checkpoint —
        // if clearing fails, the checkpoint stays Required (safe).
        resolveAttemptAsFailed(workflowName, workflowId, record, reason)
        return checkpointStore.clearRecovery(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
        )
    }

    override suspend fun failWorkflow(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    ) {
        val (_, record) = loadRequiredCheckpoint(workflowName, workflowId, expectedRevision)
        // Delete directly with the ORIGINAL revision. If this fails, the checkpoint
        // stays in Required and the workflow stays blocked — the safe behavior.
        checkpointStore.delete(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
        )
        // Record resolution evidence only AFTER a successful delete — never write
        // "resolved: failed" for a workflow that is still in Required state.
        resolveAttemptAsFailed(workflowName, workflowId, record, reason)
    }

    /**
     * Loads the checkpoint and validates it is eligible for a recovery-resolution
     * operation: it exists, its revision matches [expectedRevision], and its recovery
     * state is [WorkflowRecoveryState.Required].
     */
    private suspend fun loadRequiredCheckpoint(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
    ): Pair<WorkflowCheckpoint, WorkflowRecoveryRecord> {
        val checkpoint = checkpointStore.load(workflowName, workflowId)
            ?: throw WorkflowCheckpointConflictException(
                "Checkpoint does not exist for workflow '$workflowName'/'$workflowId' at expected revision $expectedRevision",
            )
        if (checkpoint.revision != expectedRevision) {
            throw WorkflowCheckpointConflictException(
                "Checkpoint for workflow '$workflowName'/'$workflowId' is at revision ${checkpoint.revision}, not expected revision $expectedRevision",
            )
        }
        val record = (checkpoint.recoveryState as? WorkflowRecoveryState.Required)?.record
            ?: throw WorkflowRecoveryStateException(
                "Workflow '$workflowName'/'$workflowId' is not in recovery-required state",
            )
        return checkpoint to record
    }

    /**
     * Marks the exact attempt referenced by [record] as [StepAttemptStatus.FAILED] with
     * the operator's resolution evidence. Best-effort: storage errors are logged and
     * swallowed so a resolution never blocks on evidence writes.
     */
    private suspend fun resolveAttemptAsFailed(
        workflowName: String,
        workflowId: String,
        record: WorkflowRecoveryRecord,
        reason: String,
    ) {
        val store = stepAttemptStore ?: return
        try {
            val attempt = store.listStepAttempts(workflowId).singleOrNull {
                it.stepName == record.stepName && it.attemptId == record.attemptId
            } ?: return
            store.updateStepAttempt(
                attempt.copy(
                    status = StepAttemptStatus.FAILED,
                    resolutionReason = reason,
                    resolutionAtEpochMillis = System.currentTimeMillis(),
                ),
            )
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            System.err.println(
                "Failed to record resolution evidence for '$workflowName'/'$workflowId' step '${record.stepName}' attempt '${record.attemptId}': $error",
            )
        }
    }
}
