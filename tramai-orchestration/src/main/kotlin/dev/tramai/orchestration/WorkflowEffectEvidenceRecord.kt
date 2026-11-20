package dev.tramai.orchestration

/**
 * Observed lifecycle state of an instrumented workflow effect execution.
 *
 * Strict monotonic transitions:
 * - ADMITTED -> DISPATCHED -> COMPLETED
 * - Any state -> UNCERTAIN (on cancellation, crash, transport error, or unresolved outcome)
 */
internal enum class WorkflowEffectEvidenceState {
    /** Durable admission sequence obtained from authority; child evidence record written. */
    ADMITTED,

    /** Transport handoff was observed and returned a response stream before completion/body processing. */
    DISPATCHED,

    /** The instrumented invocation and response processing completed normally. */
    COMPLETED,

    /** Execution state cannot be proven (cancellation, crash, or transport error). */
    UNCERTAIN,
}

/**
 * Compound primary key for a per-effect child evidence record.
 *
 * Lineage is anchored in the owning workflow run, the checkpoint generation, and the exact durable
 * admission sequence granted by [WorkflowCheckpointStore.admitEffect].
 */
internal data class WorkflowEffectEvidenceKey(
    val workflowName: String,
    val workflowId: String,
    val checkpointGeneration: String,
    val admissionSeq: Long,
) {
    init {
        require(workflowName.isNotBlank()) { "workflowName must not be blank" }
        require(workflowId.isNotBlank()) { "workflowId must not be blank" }
        require(admissionSeq > 0) { "admissionSeq must be positive: $admissionSeq" }
    }
}

/**
 * Authoritative parent lineage for an effect execution.
 *
 * Strict separation between step-attempt lineage (E1–E8, E11) and approval-continuation lineage (E9).
 */
internal sealed interface ParentProvenance {
    data class StepAttempt(
        val stepName: String,
        val stepAttemptId: String,
    ) : ParentProvenance {
        init {
            require(stepName.isNotBlank()) { "stepName must not be blank" }
            require(stepAttemptId.isNotBlank()) { "stepAttemptId must not be blank" }
        }
    }

    data class ApprovalContinuation(
        val approvalId: String,
        val continuationVersion: Long,
        val toolName: String,
    ) : ParentProvenance {
        init {
            require(approvalId.isNotBlank()) { "approvalId must not be blank" }
            require(continuationVersion > 0L) { "continuationVersion must be positive: $continuationVersion" }
            require(toolName.isNotBlank()) { "toolName must not be blank" }
        }
    }
}

/**
 * Authoritative per-effect child record preserving run identity, parent lineage,
 * effect kind, and durable admission sequence.
 */
internal data class WorkflowEffectEvidenceRecord(
    val key: WorkflowEffectEvidenceKey,
    val parent: ParentProvenance,
    val effectKind: String,
    val state: WorkflowEffectEvidenceState,
    val createdAtEpochMillis: Long,
    val completedAtEpochMillis: Long? = null,
    val diagnosticSummary: String? = null,
) {
    // Retained legacy constructor for compiling callers
    constructor(
        key: WorkflowEffectEvidenceKey,
        stepName: String,
        stepAttemptId: String,
        effectKind: String,
        state: WorkflowEffectEvidenceState,
        createdAtEpochMillis: Long,
        completedAtEpochMillis: Long? = null,
        diagnosticSummary: String? = null,
    ) : this(
        key = key,
        parent = ParentProvenance.StepAttempt(stepName, stepAttemptId),
        effectKind = effectKind,
        state = state,
        createdAtEpochMillis = createdAtEpochMillis,
        completedAtEpochMillis = completedAtEpochMillis,
        diagnosticSummary = diagnosticSummary,
    )

    val stepName: String get() =
        when (parent) {
            is ParentProvenance.StepAttempt -> parent.stepName
            is ParentProvenance.ApprovalContinuation -> parent.toolName
        }

    val stepAttemptId: String? get() =
        when (parent) {
            is ParentProvenance.StepAttempt -> parent.stepAttemptId
            is ParentProvenance.ApprovalContinuation -> null
        }

    init {
        require(effectKind.isNotBlank()) { "effectKind must not be blank" }
        require(createdAtEpochMillis > 0) { "createdAtEpochMillis must be positive" }
        if (state == WorkflowEffectEvidenceState.COMPLETED) {
            require(completedAtEpochMillis != null && completedAtEpochMillis >= createdAtEpochMillis) {
                "COMPLETED state requires completedAtEpochMillis >= createdAtEpochMillis"
            }
        }
    }
}

/**
 * Conservative classification verdict for an admission sequence.
 */
internal sealed interface WorkflowEffectClassification {
    data class Classified(
        val record: WorkflowEffectEvidenceRecord,
    ) : WorkflowEffectClassification

    data object Unclassified : WorkflowEffectClassification
}

/**
 * Storage SPI for per-effect evidence records.
 */
internal interface WorkflowEffectEvidenceRecordStore {
    /**
     * Persist the initial ADMITTED record. Fails if a record with the same key already exists.
     */
    suspend fun recordEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord

    /**
     * Atomically update the state/timestamps of an existing record with matching key.
     * Fails if no record with the key exists, or if the state transition is invalid.
     */
    suspend fun updateEvidence(record: WorkflowEffectEvidenceRecord): WorkflowEffectEvidenceRecord

    /**
     * Retrieve an exact record by compound key.
     */
    suspend fun getEvidence(key: WorkflowEffectEvidenceKey): WorkflowEffectEvidenceRecord?

    /**
     * List all evidence records for a workflow run, ordered monotonically by admission sequence.
     */
    suspend fun listEvidence(
        workflowName: String,
        workflowId: String,
    ): List<WorkflowEffectEvidenceRecord>

    /**
     * Classify an admission sequence. Never guesses from gaps: if no matching record exists,
     * returns [WorkflowEffectClassification.Unclassified].
     */
    suspend fun classifyAdmission(
        workflowName: String,
        workflowId: String,
        checkpointGeneration: String,
        admissionSeq: Long,
    ): WorkflowEffectClassification {
        val key =
            WorkflowEffectEvidenceKey(
                workflowName = workflowName,
                workflowId = workflowId,
                checkpointGeneration = checkpointGeneration,
                admissionSeq = admissionSeq,
            )
        val record = getEvidence(key)
        return if (record != null) {
            WorkflowEffectClassification.Classified(record)
        } else {
            WorkflowEffectClassification.Unclassified
        }
    }
}

/**
 * Thrown when a governed effect requires an active evidence store and step-attempt lineage,
 * but one or both are absent from the execution scope (failing closed).
 */
internal class WorkflowEvidenceResolutionException(
    message: String,
) : RuntimeException(message)
