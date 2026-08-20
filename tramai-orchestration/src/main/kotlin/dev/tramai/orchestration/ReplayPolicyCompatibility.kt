package dev.tramai.orchestration

/**
 * The ONLY bridge between the two-dimensional runtime replay model
 * ([WorkflowStepReplayDescriptor]) and the legacy single-axis [ReplayPolicy]
 * persisted in step-attempt records (schema version 1, fingerprinted).
 *
 * Business logic must NOT switch on [ReplayPolicy] directly (architecture-
 * guarded); it decodes persisted attempts through [StepAttemptRecord.replayDescriptor]
 * and encodes runtime descriptors through [WorkflowStepReplayDescriptor.toPersistedReplayPolicy].
 *
 * Mapping is conservative: a step that is reconstructable but not repeat-safe
 * persists as [ReplayPolicy.NON_REPLAYABLE] (manual recovery), and a legacy
 * [ReplayPolicy.NON_REPLAYABLE] decodes as NON_REPLAYABLE + UNSAFE.
 */
internal fun WorkflowStepReplayDescriptor.toPersistedReplayPolicy(): ReplayPolicy = when {
    replayability == WorkflowStepReplayability.NON_REPLAYABLE -> ReplayPolicy.NON_REPLAYABLE
    else -> when (repetitionSafety) {
        WorkflowStepRepetitionSafety.PURE -> ReplayPolicy.PURE
        WorkflowStepRepetitionSafety.IDEMPOTENT -> ReplayPolicy.IDEMPOTENT
        WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT -> ReplayPolicy.EXTERNALLY_IDEMPOTENT
        WorkflowStepRepetitionSafety.UNSAFE -> ReplayPolicy.NON_REPLAYABLE
    }
}

internal fun ReplayPolicy.toReplayDescriptor(): WorkflowStepReplayDescriptor = when (this) {
    ReplayPolicy.PURE -> WorkflowStepReplayDescriptor(
        WorkflowStepReplayability.REPLAYABLE,
        WorkflowStepRepetitionSafety.PURE,
    )
    ReplayPolicy.IDEMPOTENT -> WorkflowStepReplayDescriptor(
        WorkflowStepReplayability.REPLAYABLE,
        WorkflowStepRepetitionSafety.IDEMPOTENT,
    )
    ReplayPolicy.EXTERNALLY_IDEMPOTENT -> WorkflowStepReplayDescriptor(
        WorkflowStepReplayability.REPLAYABLE,
        WorkflowStepRepetitionSafety.EXTERNALLY_IDEMPOTENT,
    )
    ReplayPolicy.NON_REPLAYABLE -> WorkflowStepReplayDescriptor(
        WorkflowStepReplayability.NON_REPLAYABLE,
        WorkflowStepRepetitionSafety.UNSAFE,
    )
}

/** Decoded runtime model of a persisted attempt, including its stored key. */
internal val StepAttemptRecord.replayDescriptor: WorkflowStepReplayDescriptor
    get() = replayPolicy.toReplayDescriptor().copy(idempotencyKey = idempotencyKey)
