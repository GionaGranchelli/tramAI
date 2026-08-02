package dev.tramai.orchestration

import java.time.Instant

enum class StepAttemptStatus {
    STARTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNKNOWN,
}

enum class ReplayPolicy {
    PURE,
    IDEMPOTENT,
    EXTERNALLY_IDEMPOTENT,
    NON_REPLAYABLE,
}

enum class StepAttemptResolutionAction {
    RETRY_APPROVED,
    WORKFLOW_FAILED,
}

data class StepAttemptRecord(
    val runId: String,
    val stepName: String,
    val attemptId: String,
    val workerId: String,
    val leaseToken: String,
    val status: StepAttemptStatus,
    val startedAt: Long,
    val completedAt: Long? = null,
    val idempotencyKey: String? = null,
    val replayPolicy: ReplayPolicy,
    val inputFingerprint: String? = null,
    val outputSummary: String? = null,
    val resolutionReason: String? = null,
    val resolutionAtEpochMillis: Long? = null,
    val resolutionAction: StepAttemptResolutionAction? = null,
    val approvedIdempotencyKey: String? = null,
)

internal fun decodeResolutionAction(name: String?): StepAttemptResolutionAction? =
    if (name.isNullOrBlank()) {
        null
    } else {
        StepAttemptResolutionAction.entries.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown StepAttemptResolutionAction: '$name'")
    }

interface StepAttemptRecordStore {
    suspend fun recordStepAttempt(record: StepAttemptRecord): StepAttemptRecord

    suspend fun updateStepAttempt(record: StepAttemptRecord): StepAttemptRecord

    /**
     * Atomically replace the persisted attempt from [expected] to [updated] only when the
     * persisted record is still exactly [expected].
     *
     * Used for authorization-critical transitions (retry approval, approval consumption,
     * stale-approval voiding) where a checkpoint-revision fence cannot protect the attempt
     * record: the attempt store and the checkpoint store are independent, so a stale operator
     * or worker must not overwrite a concurrent successful authorization.
     *
     * @return true when the record was replaced; false when the persisted record differs
     * from [expected] (caller must reload and re-validate before deciding).
     */
    suspend fun compareAndSetStepAttempt(
        expected: StepAttemptRecord,
        updated: StepAttemptRecord,
    ): Boolean

    suspend fun latestStepAttempt(
        runId: String,
        stepName: String,
    ): StepAttemptRecord?

    suspend fun listStepAttempts(runId: String): List<StepAttemptRecord>
}

class NonReplayableStepStateUnknownException(
    val runId: String,
    val stepName: String,
    val priorWorkerId: String,
    val attemptTime: Long,
    val recoveryInstructions: String = "Inspect the external side effect for this step, mark the run safe to resume manually, or roll it back before retrying.",
) : RuntimeException(
    buildString {
        append("Workflow run '")
        append(runId)
        append("' cannot safely resume step '")
        append(stepName)
        append("' because worker '")
        append(priorWorkerId)
        append("' started it at ")
        append(Instant.ofEpochMilli(attemptTime))
        append(" and the final state is unknown. ")
        append(recoveryInstructions)
    },
)

internal data class WorkflowStepReplayDescriptor(
    val replayPolicy: ReplayPolicy,
    val idempotencyKey: String? = null,
)
