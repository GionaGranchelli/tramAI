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
)

interface StepAttemptRecordStore {
    suspend fun recordStepAttempt(record: StepAttemptRecord): StepAttemptRecord

    suspend fun updateStepAttempt(record: StepAttemptRecord): StepAttemptRecord

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
