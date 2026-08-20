package dev.tramai.orchestration

/**
 * Typed outcome of the unknown-attempt recovery decision. [Replay] means the
 * step may be re-executed automatically; [RequireRecovery] means an operator
 * must resolve the run first, with the [reason] identifying the durable
 * recovery record to write.
 */
internal sealed interface WorkflowReplayDecision {
    data object Replay : WorkflowReplayDecision
    data class RequireRecovery(val reason: WorkflowRecoveryReason) : WorkflowReplayDecision
}

/**
 * Pure decision table for unknown-attempt recovery (Epic 5.1).
 *
 * Replayability and repetition safety are independent dimensions; neither can
 * be inferred from the other, and automatic replay requires BOTH:
 *
 * - the step is reconstructable ([WorkflowStepReplayability.REPLAYABLE]), and
 * - repeating its side effect is safe ([WorkflowStepRepetitionSafety.PURE] or
 *   [WorkflowStepRepetitionSafety.IDEMPOTENT], or
 *   [WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT] with a stable
 *   recorded key matching the current definition).
 *
 * Reproduces the #215-#218 recovery semantics exactly. Stateless: state
 * transitions and persistence stay in [WorkflowRecoveryCoordinator].
 */
internal class WorkflowReplayDecisionPolicy {
    fun decide(
        descriptor: WorkflowStepReplayDescriptor,
        storedIdempotencyKey: String?,
        currentIdempotencyKey: String?,
    ): WorkflowReplayDecision = when (descriptor.replayability) {
        WorkflowStepReplayability.NON_REPLAYABLE ->
            WorkflowReplayDecision.RequireRecovery(WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN)

        WorkflowStepReplayability.REPLAYABLE -> when (descriptor.repetitionSafety) {
            WorkflowStepRepetitionSafety.PURE,
            WorkflowStepRepetitionSafety.IDEMPOTENT,
            -> WorkflowReplayDecision.Replay

            WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT -> when {
                storedIdempotencyKey.isNullOrBlank() ->
                    WorkflowReplayDecision.RequireRecovery(WorkflowRecoveryReason.EXTERNAL_IDEMPOTENCY_KEY_MISSING)

                currentIdempotencyKey != storedIdempotencyKey ->
                    WorkflowReplayDecision.RequireRecovery(WorkflowRecoveryReason.IDEMPOTENCY_KEY_MISMATCH)

                else -> WorkflowReplayDecision.Replay
            }

            WorkflowStepRepetitionSafety.UNSAFE ->
                WorkflowReplayDecision.RequireRecovery(WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN)
        }
    }
}
