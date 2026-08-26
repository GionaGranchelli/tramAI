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
     * The exact attempt referenced by the recovery record remains [StepAttemptStatus.UNKNOWN]
     * and receives [StepAttemptResolutionAction.RETRY_APPROVED] with the operator's [reason]
     * BEFORE recovery is cleared. The worker consumes the approval by marking that attempt
     * failed immediately before starting a new attempt. Persistence failures propagate.
     *
     * Supported only for [WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN]. Retrying
     * [WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING] or
     * [WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH] with this overload is rejected:
     * those reasons require the key-bound overload with an explicit approved key, so an
     * approval can never authorize an unspecified future key.
     *
     * @return the checkpoint after clearing recovery (revision advanced by one).
     * @throws WorkflowCheckpointConflictException if [expectedRevision] is stale.
     * @throws WorkflowRecoveryStateException if the checkpoint is not in [WorkflowRecoveryState.Required],
     * if the recovery reason is not retryable, if no [StepAttemptRecordStore] is configured,
     * or if the referenced unresolved attempt cannot be found — in every case the checkpoint
     * stays `Required`.
     */
    suspend fun retryStep(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    ): WorkflowCheckpoint

    /**
     * Approve retry of an externally idempotent unresolved attempt, binding approval to
     * exactly [approvedIdempotencyKey]. The worker executes only when the current workflow
     * definition produces the same key.
     *
     * @throws IllegalArgumentException if [approvedIdempotencyKey] is blank.
     */
    suspend fun retryStep(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
        approvedIdempotencyKey: String,
    ): WorkflowCheckpoint

    /**
     * Permanently delete the checkpoint, resolving the workflow as failed.
     *
     * The workflow will not be polled again. When a [StepAttemptRecordStore] is available,
     * [StepAttemptResolutionAction.WORKFLOW_FAILED] and the resolution reason are recorded
     * onto the exact failed attempt record as best-effort audit evidence (storage failures
     * are logged, not propagated); otherwise the operator's
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
 * the exact unresolved attempt as best-effort audit evidence (storage errors are
 * logged, not propagated).
 *
 * [retryStep]'s approval write is MANDATORY — it must succeed before the checkpoint is
 * unblocked. Approval and checkpoint state live in independent stores, so a clear failure
 * preserves a retryable partial state rather than claiming cross-store atomicity.
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
    ): WorkflowCheckpoint = retryStepInternal(
        workflowName = workflowName,
        workflowId = workflowId,
        expectedRevision = expectedRevision,
        reason = reason,
        approvedIdempotencyKey = null,
    )

    override suspend fun retryStep(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
        approvedIdempotencyKey: String,
    ): WorkflowCheckpoint {
        require(approvedIdempotencyKey.isNotBlank()) { "approvedIdempotencyKey must not be blank" }
        return retryStepInternal(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
            reason = reason,
            approvedIdempotencyKey = approvedIdempotencyKey,
        )
    }

    private suspend fun retryStepInternal(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
        approvedIdempotencyKey: String?,
    ): WorkflowCheckpoint {
        val (checkpoint, record) = loadRequiredCheckpoint(workflowName, workflowId, expectedRevision)
        if (record.reason == WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING ||
            record.reason == WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH
        ) {
            if (!approvedIdempotencyKey.isNullOrBlank()) {
                approveAttemptForRetry(workflowName, workflowId, record, reason, approvedIdempotencyKey)
                return checkpointStore.clearRecovery(
                    workflowName,
                    workflowId,
                    expectedRevision,
                    checkpoint.checkpointGeneration,
                )
            }
            throw WorkflowRecoveryStateException(
                "Cannot retry workflow '$workflowName'/'$workflowId': recovery reason ${record.reason} " +
                    "requires a non-blank operator-approved idempotency key.",
            )
        }
        approveAttemptForRetry(workflowName, workflowId, record, reason, approvedIdempotencyKey)
        return checkpointStore.clearRecovery(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
            expectedGeneration = checkpoint.checkpointGeneration,
        )
    }

    override suspend fun failWorkflow(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        reason: String,
    ) {
        val (checkpoint, record) = loadRequiredCheckpoint(workflowName, workflowId, expectedRevision)
        // Delete directly with the ORIGINAL revision. If this fails, the checkpoint
        // stays in Required and the workflow stays blocked — the safe behavior.
        checkpointStore.delete(
            workflowName = workflowName,
            workflowId = workflowId,
            expectedRevision = expectedRevision,
            expectedGeneration = checkpoint.checkpointGeneration,
        )
        // Best-effort evidence only AFTER a successful delete — never write
        // "resolved: failed" for a workflow that is still in Required state.
        recordFailureEvidenceBestEffort(workflowName, workflowId, record, reason)
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
     * Keeps the exact attempt referenced by [record] [StepAttemptStatus.UNKNOWN] and writes
     * worker-visible retry approval plus the operator's resolution evidence.
     *
     * MANDATORY for retry: this transition must succeed before the checkpoint is unblocked,
     * otherwise the worker re-detects the same UNKNOWN attempt and re-enters Required.
     * Storage failures are NOT caught — they propagate so the checkpoint remains Required.
     *
     * @throws WorkflowRecoveryStateException if no [StepAttemptRecordStore] is configured
     * or the referenced attempt cannot be found.
     */
    private suspend fun approveAttemptForRetry(
        workflowName: String,
        workflowId: String,
        record: WorkflowRecoveryRecord,
        reason: String,
        approvedIdempotencyKey: String?,
    ) {
        val store = stepAttemptStore
            ?: throw WorkflowRecoveryStateException(
                "Cannot retry workflow '$workflowName'/'$workflowId': no StepAttemptRecordStore is configured",
            )
        val attempt = store.listStepAttempts(workflowId).singleOrNull {
            it.stepName == record.stepName && it.attemptId == record.attemptId
        } ?: throw WorkflowRecoveryStateException(
            "Cannot retry workflow '$workflowName'/'$workflowId': unresolved attempt '${record.attemptId}' for step '${record.stepName}' was not found",
        )
        if (attempt.status != StepAttemptStatus.UNKNOWN) {
            throw WorkflowRecoveryStateException(
                "Cannot retry workflow '$workflowName'/'$workflowId': attempt '${attempt.attemptId}' is ${attempt.status}, not UNKNOWN",
            )
        }
        if (attempt.replayDescriptor.repetitionSafety == WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT &&
            approvedIdempotencyKey.isNullOrBlank()
        ) {
            throw WorkflowRecoveryStateException(
                "Cannot retry workflow '$workflowName'/'$workflowId': externally idempotent attempt '${attempt.attemptId}' requires a non-blank operator-approved idempotency key",
            )
        }
        if (attempt.resolutionAction != null) {
            val identical = attempt.resolutionAction == StepAttemptResolutionAction.RETRY_APPROVED &&
                attempt.resolutionReason == reason &&
                attempt.approvedIdempotencyKey == approvedIdempotencyKey
            if (!identical) {
                throw WorkflowRecoveryStateException(
                    "Cannot retry workflow '$workflowName'/'$workflowId': attempt '${attempt.attemptId}' already has a conflicting resolution",
                )
            }
            return
        }
        val resolvedAt = System.currentTimeMillis()
        val updated = attempt.copy(
            resolutionAction = StepAttemptResolutionAction.RETRY_APPROVED,
            resolutionReason = reason,
            resolutionAtEpochMillis = resolvedAt,
            approvedIdempotencyKey = approvedIdempotencyKey,
        )
        // Atomic CAS: a concurrent operator may have written an approval between our read and
        // here, and the checkpoint-revision fence cannot protect the attempt record (attempt
        // store and checkpoint store are independent). A failed CAS must never overwrite a
        // successful concurrent authorization.
        if (!store.compareAndSetStepAttempt(expected = attempt, updated = updated)) {
            val current = store.listStepAttempts(workflowId).singleOrNull {
                it.stepName == record.stepName && it.attemptId == record.attemptId
            } ?: throw WorkflowRecoveryStateException(
                "Cannot retry workflow '$workflowName'/'$workflowId': unresolved attempt '${record.attemptId}' for step '${record.stepName}' was not found after a concurrent modification",
            )
            val identical = current.resolutionAction == StepAttemptResolutionAction.RETRY_APPROVED &&
                current.resolutionReason == reason &&
                current.approvedIdempotencyKey == approvedIdempotencyKey
            if (!identical) {
                throw WorkflowRecoveryStateException(
                    "Cannot retry workflow '$workflowName'/'$workflowId': attempt '${attempt.attemptId}' has a conflicting concurrent resolution; reload the checkpoint and retry",
                )
            }
        }
    }

    /**
     * Best-effort audit evidence for [failWorkflow]: marks the exact attempt referenced by
     * [record] as [StepAttemptStatus.FAILED] with the operator's resolution evidence.
     *
     * Runs only AFTER the checkpoint was deleted successfully. Storage errors are logged
     * and swallowed — evidence is optional and must never block the resolution. The
     * attempt-record write is not revision-fenced, so concurrent resolutions could
     * overwrite each other's evidence (checkpoint execution safety is unaffected).
     */
    private suspend fun recordFailureEvidenceBestEffort(
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
            val resolvedAt = System.currentTimeMillis()
            store.updateStepAttempt(
                attempt.copy(
                    status = StepAttemptStatus.FAILED,
                    completedAt = attempt.completedAt ?: resolvedAt,
                    resolutionAction = StepAttemptResolutionAction.WORKFLOW_FAILED,
                    resolutionReason = reason,
                    resolutionAtEpochMillis = resolvedAt,
                    approvedIdempotencyKey = null,
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
