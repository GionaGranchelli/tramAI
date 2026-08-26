package dev.tramai.testing.persistence.checkpoint

import dev.tramai.orchestration.WorkflowCheckpoint
import dev.tramai.orchestration.WorkflowRecoveryReason
import dev.tramai.orchestration.WorkflowRecoveryRecord
import dev.tramai.orchestration.WorkflowRecoveryState

/**
 * Epic 8.1f: fixtures for the shared
 * [dev.tramai.orchestration.WorkflowCheckpointStore] compatibility contract.
 *
 * Deterministic only — never the domain defaults (`savedAtEpochMillis =
 * System.currentTimeMillis()`): fixed timestamps, explicit names/IDs,
 * explicit payloads and metadata. No sleeps, no system clock.
 */
object WorkflowCheckpointFixtures {

    val SAVED_AT_EPOCH_MILLIS: Long = 1_800_000_000_000L

    val DETECTED_AT_EPOCH_MILLIS: Long = 1_800_000_000_042L

    fun checkpoint(
        workflowName: String,
        workflowId: String,
        nextStepIndex: Int = 3,
        stepExecutions: Int = 5,
        lastCompletedStepName: String? = "validate",
        statePayload: String = """{"state":"review"}""",
        revision: Long = 0,
        metadata: Map<String, String> = mapOf("region" to "eu-west", "tenant" to "acme"),
        savedAtEpochMillis: Long = SAVED_AT_EPOCH_MILLIS,
        recoveryState: WorkflowRecoveryState = WorkflowRecoveryState.Normal,
        checkpointGeneration: String? = null,
    ): WorkflowCheckpoint = WorkflowCheckpoint(
        workflowName = workflowName,
        workflowId = workflowId,
        nextStepIndex = nextStepIndex,
        stepExecutions = stepExecutions,
        lastCompletedStepName = lastCompletedStepName,
        statePayload = statePayload,
        revision = revision,
        metadata = metadata,
        savedAtEpochMillis = savedAtEpochMillis,
        recoveryState = recoveryState,
        checkpointGeneration = checkpointGeneration,
    )

    fun recoveryRecord(
        reason: WorkflowRecoveryReason = WorkflowRecoveryReason.NON_REPLAYABLE_OUTCOME_UNKNOWN,
        stepName: String = "sendInvoice",
        attemptId: String = "attempt-42",
        priorWorkerId: String = "worker-7",
        detectedAtEpochMillis: Long = DETECTED_AT_EPOCH_MILLIS,
        idempotencyKey: String? = "idem-xyz",
        instructions: String? = "manual confirmation required",
    ): WorkflowRecoveryRecord = WorkflowRecoveryRecord(
        reason = reason,
        stepName = stepName,
        attemptId = attemptId,
        priorWorkerId = priorWorkerId,
        detectedAtEpochMillis = detectedAtEpochMillis,
        idempotencyKey = idempotencyKey,
        instructions = instructions,
    )

    fun required(
        record: WorkflowRecoveryRecord = recoveryRecord(),
    ): WorkflowRecoveryState.Required = WorkflowRecoveryState.Required(record)
}
