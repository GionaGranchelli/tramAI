package dev.tramai.orchestration

/**
 * Recovery state machine for checkpoints with unknown step attempts.
 *
 * Owns the #215-#218 recovery semantics verbatim: unknown-attempt detection
 * outcomes (non-replayable, externally idempotent with key verification),
 * retry-approval consumption, stale-approval voiding, and the durable
 * recovery records written through the fenced checkpoint store.
 *
 * The coordinator is deliberately stateless: all mutable execution state
 * lives in [ExecutionTracker] (owned by the execution supervisor) and the
 * step-attempt store.
 */
internal class WorkflowRecoveryCoordinator(
    private val leaseStore: WorkflowLeaseStore,
    private val stepAttemptStore: StepAttemptRecordStore,
    private val replayDecisionPolicy: WorkflowReplayDecisionPolicy = WorkflowReplayDecisionPolicy(),
) {
    suspend fun recoverUnknownAttempt(
        checkpoint: WorkflowCheckpoint,
        tracker: ExecutionTracker,
        fencedCheckpointStore: WorkflowCheckpointStore,
        unknownAttempt: StepAttemptRecord,
    ) {
        // The semantic decision is delegated to the pure policy; this
        // coordinator owns only state transitions and durable records.
        when (val decision = replayDecisionPolicy.decide(
            descriptor = unknownAttempt.replayDescriptor,
            storedIdempotencyKey = unknownAttempt.idempotencyKey,
            currentIdempotencyKey = tracker.currentStepReplayDescriptor()?.idempotencyKey,
        )) {
            WorkflowReplayDecision.Replay -> Unit

            is WorkflowReplayDecision.RequireRecovery -> when (decision.reason) {
                WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN -> {
                    fencedCheckpointStore.requireRecovery(
                        workflowName = checkpoint.workflowName,
                        workflowId = checkpoint.workflowId,
                        expectedRevision = checkpoint.revision,
                        expectedGeneration = checkpoint.checkpointGeneration,
                        record = WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
                            stepName = unknownAttempt.stepName,
                            attemptId = unknownAttempt.attemptId,
                            priorWorkerId = unknownAttempt.workerId,
                            detectedAtEpochMillis = unknownAttempt.startedAt,
                            idempotencyKey = unknownAttempt.idempotencyKey,
                        ),
                    )
                    throw NonReplayableStepStateUnknownException(
                        runId = unknownAttempt.runId,
                        stepName = unknownAttempt.stepName,
                        priorWorkerId = unknownAttempt.workerId,
                        attemptTime = unknownAttempt.startedAt,
                    )
                }

                WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING -> {
                    fencedCheckpointStore.requireRecovery(
                        workflowName = checkpoint.workflowName,
                        workflowId = checkpoint.workflowId,
                        expectedRevision = checkpoint.revision,
                        expectedGeneration = checkpoint.checkpointGeneration,
                        record = WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING,
                            stepName = unknownAttempt.stepName,
                            attemptId = unknownAttempt.attemptId,
                            priorWorkerId = unknownAttempt.workerId,
                            detectedAtEpochMillis = unknownAttempt.startedAt,
                        ),
                    )
                    throw NonReplayableStepStateUnknownException(
                        runId = unknownAttempt.runId,
                        stepName = unknownAttempt.stepName,
                        priorWorkerId = unknownAttempt.workerId,
                        attemptTime = unknownAttempt.startedAt,
                        recoveryInstructions = "The prior attempt requires a stable idempotency key for replay, but no key was recorded. Investigate the external system before resuming.",
                    )
                }

                WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH -> {
                    val storedKey = unknownAttempt.idempotencyKey
                    val currentKey = tracker.currentStepReplayDescriptor()?.idempotencyKey
                    fencedCheckpointStore.requireRecovery(
                        workflowName = checkpoint.workflowName,
                        workflowId = checkpoint.workflowId,
                        expectedRevision = checkpoint.revision,
                        expectedGeneration = checkpoint.checkpointGeneration,
                        record = WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH,
                            stepName = unknownAttempt.stepName,
                            attemptId = unknownAttempt.attemptId,
                            priorWorkerId = unknownAttempt.workerId,
                            detectedAtEpochMillis = unknownAttempt.startedAt,
                            idempotencyKey = storedKey,
                        ),
                    )
                    throw NonReplayableStepStateUnknownException(
                        runId = unknownAttempt.runId,
                        stepName = unknownAttempt.stepName,
                        priorWorkerId = unknownAttempt.workerId,
                        attemptTime = unknownAttempt.startedAt,
                        recoveryInstructions = "The idempotency key computed for the current workflow definition (${currentKey ?: "<none>"}) differs from the key recorded by the prior attempt ($storedKey). Resolve the mismatch before resuming.",
                    )
                }
            }
        }
    }

    suspend fun consumeRetryApproval(
        checkpoint: WorkflowCheckpoint,
        expectedLease: WorkflowLease?,
        tracker: ExecutionTracker,
        fencedCheckpointStore: WorkflowCheckpointStore,
        attempt: StepAttemptRecord,
    ) {
        expectedLease
            ?: throw StaleWorkflowLeaseException(
                "Workflow '${checkpoint.workflowName}' and workflowId='${checkpoint.workflowId}' has no active lease for retry-approval consumption",
            )
        val currentLease = leaseStore.currentLease(checkpoint.workflowName, checkpoint.workflowId)
        if (currentLease?.leaseId != expectedLease.leaseId || currentLease.ownerId != expectedLease.ownerId) {
            throw StaleWorkflowLeaseException(
                "Workflow '${checkpoint.workflowName}' and workflowId='${checkpoint.workflowId}' lease was lost before retry-approval consumption",
            )
        }
        when {
            attempt.replayDescriptor.replayability == WorkflowStepReplayability.NON_REPLAYABLE ->
                tracker.consumeRetryApproval(attempt)

            attempt.replayDescriptor.repetitionSafety == WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT -> {
                val approvedKey = attempt.approvedIdempotencyKey
                val currentKey = tracker.currentStepReplayDescriptor()?.idempotencyKey
                if (approvedKey.isNullOrBlank() || currentKey != approvedKey) {
                    // Void the stale approval so the operator can issue a fresh key-bound
                    // approval matching the current definition. The attempt stays UNKNOWN
                    // (never consumed, never authorized execution); the resolution reason
                    // and timestamp are retained as the latest resolution context (not a
                    // durable history — a subsequent approval overwrites them).
                    val voided = attempt.copy(
                        resolutionAction = null,
                        approvedIdempotencyKey = null,
                    )
                    if (!stepAttemptStore.compareAndSetStepAttempt(expected = attempt, updated = voided)) {
                        throw WorkflowRecoveryStateException(
                            "Workflow '${checkpoint.workflowName}' and workflowId='${checkpoint.workflowId}': attempt '${attempt.attemptId}' changed during stale-approval voiding",
                        )
                    }
                    fencedCheckpointStore.requireRecovery(
                        workflowName = checkpoint.workflowName,
                        workflowId = checkpoint.workflowId,
                        expectedRevision = checkpoint.revision,
                        expectedGeneration = checkpoint.checkpointGeneration,
                        record = WorkflowRecoveryRecord(
                            reason = WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH,
                            stepName = attempt.stepName,
                            attemptId = attempt.attemptId,
                            priorWorkerId = attempt.workerId,
                            detectedAtEpochMillis = attempt.startedAt,
                            idempotencyKey = approvedKey,
                            instructions = "The workflow definition changed after operator approval: approved key " +
                                "${approvedKey ?: "<none>"}, current key ${currentKey ?: "<none>"}. The stale approval " +
                                "was voided; issue a new key-bound retryStep approval with a key matching the current " +
                                "definition, or use failWorkflow.",
                        ),
                    )
                    throw NonReplayableStepStateUnknownException(
                        runId = attempt.runId,
                        stepName = attempt.stepName,
                        priorWorkerId = attempt.workerId,
                        attemptTime = attempt.startedAt,
                        recoveryInstructions = "The operator-approved idempotency key (${approvedKey ?: "<none>"}) " +
                            "differs from the current workflow definition (${currentKey ?: "<none>"}). The stale " +
                            "approval was voided; obtain a new key-bound approval matching the current definition, " +
                            "or use failWorkflow.",
                    )
                }
                tracker.consumeRetryApproval(attempt)
            }

            else -> throw WorkflowRecoveryStateException(
                "Retry approval on attempt '${attempt.attemptId}' has unsupported replay semantics " +
                    "(${attempt.replayDescriptor.replayability} + ${attempt.replayDescriptor.repetitionSafety})",
            )
        }
    }
}
