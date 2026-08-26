package dev.tramai.orchestration
import java.time.Instant
import java.util.UUID

/**
 * Machine-readable reason why a workflow entered recovery-required state.
 */
enum class WorkflowRecoveryReason {
    /** A non-replayable step completed with an unknown outcome — manual confirmation required. */
    NON_REPLAYABLE_OUTCOME_UNKNOWN,
    /** An externally idempotent step is missing its idempotency key — cannot safely retry. */
    EXTERNAL_IDEMPOTENCY_KEY_MISSING,
    /** The idempotency key computed for the current workflow definition differs from the key recorded by the prior attempt. */
    IDEMPOTENCY_KEY_MISMATCH,
}

/**
 * Record explaining why a checkpoint is in recovery-required state and the context
 * of the unresolved step attempt.
 */
data class WorkflowRecoveryRecord(
    val reason: WorkflowRecoveryReason,
    val stepName: String,
    val attemptId: String,
    val priorWorkerId: String,
    val detectedAtEpochMillis: Long,
    val idempotencyKey: String? = null,
    val instructions: String? = null,
)

/**
 * Durable recovery state for a workflow checkpoint.
 *
 * Default is [Normal]. Workflows with [Required] state are skipped by workers
 * until an operator resolves them via [WorkflowRecoveryController].
 */
sealed interface WorkflowRecoveryState {
    data object Normal : WorkflowRecoveryState
    data class Required(val record: WorkflowRecoveryRecord) : WorkflowRecoveryState
}

/**
 * Serialized checkpoint for one workflow run.
 *
 * The first persistence milestone checkpoints only at top-level workflow step boundaries.
 */
data class WorkflowCheckpoint(
    val workflowName: String,
    val workflowId: String,
    val nextStepIndex: Int,
    val stepExecutions: Int,
    val lastCompletedStepName: String?,
    val statePayload: String,
    val revision: Long = 0,
    val metadata: Map<String, String> = emptyMap(),
    val savedAtEpochMillis: Long = System.currentTimeMillis(),
    val recoveryState: WorkflowRecoveryState = WorkflowRecoveryState.Normal,
    val checkpointGeneration: String? = null,
)
/**
 * SPI used to encode and decode typed workflow state for checkpoint storage.
 */
interface WorkflowStateCodec<S> {
    fun encode(state: S): String
    fun decode(payload: String): S
}
/**
 * Store for persisted workflow checkpoints.
 *
 * Implementations may use optimistic concurrency to reject stale writers via [expectedRevision].
 */
interface WorkflowCheckpointStore {
    suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint?
    suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long? = null,
    ): WorkflowCheckpoint
    suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long? = null,
        expectedGeneration: String? = null,
    )

    /**
     * Atomically persist [record] as the recovery reason for this checkpoint.
     *
     * Uses the default implementation: loads the current checkpoint, sets
     * [WorkflowRecoveryState.Required] on [WorkflowCheckpoint.recoveryState],
     * and saves with the given [expectedRevision].
     * Override for a genuinely atomic store-level operation.
     */
    suspend fun requireRecovery(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        record: WorkflowRecoveryRecord,
        expectedGeneration: String? = null,
    ): WorkflowCheckpoint {
        // Phase-aware boundaries: a load failure is READ_FAILED, a save failure
        // is WRITE_FAILED — the outer operation (SAVE) must not mislabel the
        // load phase (the Copilot finding).
        val current = persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LOAD,
            checkpointDiagnosticObserver(this),
        ) { load(workflowName, workflowId) }
            ?: throw safePersistenceFailure(
                PersistenceResourceKind.CHECKPOINT,
                PersistenceOperation.SAVE,
                PersistenceFailureCode.CONFLICT,
            )
        return persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.SAVE,
            checkpointDiagnosticObserver(this),
        ) {
            save(
                checkpoint = current.copy(
                    recoveryState = WorkflowRecoveryState.Required(record),
                    checkpointGeneration = expectedGeneration,
                ),
                expectedRevision = expectedRevision,
            )
        }
    }

    /**
     * Atomically clear the recovery state on this checkpoint, returning it to [Normal].
     *
     * Uses the default implementation: loads the current checkpoint, sets
     * [WorkflowRecoveryState.Normal] on [WorkflowCheckpoint.recoveryState],
     * and saves with the given [expectedRevision].
     * Override for a genuinely atomic store-level operation.
     */
    suspend fun clearRecovery(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long,
        expectedGeneration: String? = null,
    ): WorkflowCheckpoint {
        // Phase-aware boundaries: a load failure is READ_FAILED, a save failure
        // is WRITE_FAILED (same split as requireRecovery).
        val current = persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LOAD,
            checkpointDiagnosticObserver(this),
        ) { load(workflowName, workflowId) }
            ?: throw safePersistenceFailure(
                PersistenceResourceKind.CHECKPOINT,
                PersistenceOperation.SAVE,
                PersistenceFailureCode.CONFLICT,
            )
        return persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.SAVE,
            checkpointDiagnosticObserver(this),
        ) {
            save(
                checkpoint = current.copy(
                    recoveryState = WorkflowRecoveryState.Normal,
                    checkpointGeneration = expectedGeneration,
                ),
                expectedRevision = expectedRevision,
            )
        }
    }
}

fun interface WorkflowCheckpointCatalog {
    suspend fun listCheckpoints(): List<WorkflowCheckpoint>
}
/**
 * Optional scheduler bridge used by delay workflow steps to persist wakeups
 * without making the orchestration module depend on a scheduler backend.
 */
fun interface WorkflowDelayWakeupScheduler {
    suspend fun scheduleDelayWakeup(
        runId: String,
        stepId: String,
        resumeAt: Instant,
    )
}
/**
 * Persistence configuration for a typed workflow.
 */
data class WorkflowPersistence<S>(
    val checkpointStore: WorkflowCheckpointStore,
    val stateCodec: WorkflowStateCodec<S>,
    val delayWakeupScheduler: WorkflowDelayWakeupScheduler? = null,
    val leaseStore: WorkflowLeaseStore? = null,
    val leasePolicy: WorkflowLeasePolicy? = null,
    val deleteCheckpointOnCompletion: Boolean = true,
) {
    init {
        require((leaseStore == null) == (leasePolicy == null)) {
            "WorkflowPersistence.leaseStore and WorkflowPersistence.leasePolicy must either both be set or both be null"
        }
    }
}
/**
 * Raised when a workflow resume attempt cannot be satisfied from the checkpoint store.
 */
class WorkflowResumeException(
    message: String,
) : RuntimeException(message) {
    var failureCode: PersistenceFailureCode? = null
        internal set
    var safeFactoryTrusted: Boolean = false
        internal set
}
/**
 * Raised when a checkpoint write or delete is attempted with stale revision state.
 */
class WorkflowCheckpointConflictException(
    message: String,
) : RuntimeException(message) {
    var failureCode: PersistenceFailureCode? = null
        internal set
    var safeFactoryTrusted: Boolean = false
        internal set
}

/**
 * Raised when persisted checkpoint data is present but malformed or corrupted.
 *
 * Only a null/blank recovery payload (an old normal checkpoint) decodes as
 * [WorkflowRecoveryState.Normal]. Any non-blank payload with missing or invalid
 * fields throws this instead of silently unblocking a workflow that was
 * deliberately stopped.
 */
class WorkflowCheckpointCorruptionException(
    message: String,
) : RuntimeException(message) {
    var failureCode: PersistenceFailureCode? = null
        internal set
    var safeFactoryTrusted: Boolean = false
        internal set
}

/**
 * Shared recovery-state codec for store implementations.
 *
 * [encodeRecoveryState] returns null for [WorkflowRecoveryState.Normal] so stores
 * can omit the field entirely (backward compatible with checkpoints written before
 * recovery state existed).
 *
 * [decodeRecoveryState] fails closed: only null/blank payloads decode to Normal.
 * Any non-blank malformed payload throws [WorkflowCheckpointCorruptionException].
 */
internal fun encodeRecoveryState(state: WorkflowRecoveryState): String? = when (state) {
    WorkflowRecoveryState.Normal -> null
    is WorkflowRecoveryState.Required -> encodeMetadata(
        mapOf(
            "reason" to state.record.reason.name,
            "stepName" to state.record.stepName,
            "attemptId" to state.record.attemptId,
            "priorWorkerId" to state.record.priorWorkerId,
            "detectedAtEpochMillis" to state.record.detectedAtEpochMillis.toString(),
            "idempotencyKey" to (state.record.idempotencyKey ?: ""),
            "instructions" to (state.record.instructions ?: ""),
        ),
    )
}

internal fun decodeRecoveryState(payload: String?): WorkflowRecoveryState {
    if (payload.isNullOrBlank()) return WorkflowRecoveryState.Normal
    val map = try {
        decodeMetadata(payload)
    } catch (error: CorruptCheckpointException) {
        throw error
    } catch (error: Throwable) {
        throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    }
    val reason = map["reason"]?.let { name ->
        WorkflowRecoveryReason.entries.firstOrNull { it.name == name }
    } ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    val stepName = map["stepName"]
        ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    val attemptId = map["attemptId"]
        ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    val priorWorkerId = map["priorWorkerId"]
        ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    val detectedAt = map["detectedAtEpochMillis"]?.toLongOrNull()
        ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    return WorkflowRecoveryState.Required(
        WorkflowRecoveryRecord(
            reason = reason,
            stepName = stepName,
            attemptId = attemptId,
            priorWorkerId = priorWorkerId,
            detectedAtEpochMillis = detectedAt,
            idempotencyKey = map["idempotencyKey"]?.ifBlank { null },
            instructions = map["instructions"]?.ifBlank { null },
        ),
    )
}
/**
 * Simple in-memory checkpoint store for tests and lightweight local use.
 */
class InMemoryWorkflowCheckpointStore :
    WorkflowCheckpointStore, WorkflowCheckpointCatalog, StepAttemptRecordStore {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    constructor()

    constructor(observer: PersistenceFailureDiagnosticObserver) : this() {
        persistenceFailureDiagnosticObserver = observer
    }
    private val checkpoints = linkedMapOf<CheckpointKey, WorkflowCheckpoint>()
    private val stepAttempts = linkedMapOf<AttemptKey, StepAttemptRecord>()
    private val monitor = Any()
    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? = persistenceBoundary(
        PersistenceResourceKind.CHECKPOINT, PersistenceOperation.LOAD, persistenceFailureDiagnosticObserver,
    ) { synchronized(monitor) { checkpoints[CheckpointKey(workflowName, workflowId)] } }
    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint = persistenceBoundary(
        PersistenceResourceKind.CHECKPOINT, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
    ) { synchronized(monitor) {
        val key = CheckpointKey(checkpoint.workflowName, checkpoint.workflowId)
        val existing = checkpoints[key]
        if (expectedRevision == null && existing != null) {
            throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.SAVE, PersistenceFailureCode.CONFLICT)
        }
        if (expectedRevision != null && existing == null) {
            throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.SAVE, PersistenceFailureCode.CONFLICT)
        }
        if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
            throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.SAVE, PersistenceFailureCode.CONFLICT)
        }
        if (expectedRevision != null && existing != null && existing.checkpointGeneration != checkpoint.checkpointGeneration) {
            throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.SAVE, PersistenceFailureCode.CONFLICT)
        }
        val persisted = checkpoint.copy(
            revision = (existing?.revision ?: 0) + 1,
            checkpointGeneration = existing?.checkpointGeneration ?: newCheckpointGeneration(),
        )
        checkpoints[key] = persisted
        persisted
    } }
    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedGeneration: String?,
    ) {
        persistenceBoundary(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.DELETE, persistenceFailureDiagnosticObserver) { synchronized(monitor) {
            val key = CheckpointKey(workflowName, workflowId)
            val existing = checkpoints[key]
            if (expectedRevision != null && existing == null) {
                throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.DELETE, PersistenceFailureCode.CONFLICT)
            }
            if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
                throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.DELETE, PersistenceFailureCode.CONFLICT)
            }
            if (expectedRevision != null && existing != null && existing.checkpointGeneration != expectedGeneration) {
                throw safePersistenceFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.DELETE, PersistenceFailureCode.CONFLICT)
            }
            checkpoints.remove(key)
        } }
    }

    override suspend fun listCheckpoints(): List<WorkflowCheckpoint> = persistenceBoundary(
        PersistenceResourceKind.CHECKPOINT, PersistenceOperation.LIST, persistenceFailureDiagnosticObserver,
    ) { synchronized(monitor) { checkpoints.values.toList() } }

    override suspend fun recordStepAttempt(record: StepAttemptRecord): StepAttemptRecord = persistenceBoundary(
        PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
        classify = ::classifyStepAttemptFailure,
    ) { synchronized(monitor) {
        val key = AttemptKey(record.runId, record.stepName, record.attemptId)
        stepAttempts[key] = record
        record
    } }

    override suspend fun updateStepAttempt(record: StepAttemptRecord): StepAttemptRecord = persistenceBoundary(
        PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.SAVE, persistenceFailureDiagnosticObserver,
        classify = ::classifyStepAttemptFailure,
    ) { synchronized(monitor) {
        val key = AttemptKey(record.runId, record.stepName, record.attemptId)
        require(stepAttempts.containsKey(key)) { "Step attempt does not exist" }
        stepAttempts[key] = record
        record
    } }

    override suspend fun compareAndSetStepAttempt(
        expected: StepAttemptRecord,
        updated: StepAttemptRecord,
    ): Boolean = persistenceBoundary(
        PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.COMPARE_AND_SET, persistenceFailureDiagnosticObserver,
        classify = ::classifyStepAttemptFailure,
    ) { synchronized(monitor) {
        val expectedKey = AttemptKey(expected.runId, expected.stepName, expected.attemptId)
        val updatedKey = AttemptKey(updated.runId, updated.stepName, updated.attemptId)
        if (expectedKey != updatedKey) {
            false
        } else if (stepAttempts[expectedKey] != expected) {
            false
        } else {
            stepAttempts[expectedKey] = updated
            true
        }
    } }

    override suspend fun latestStepAttempt(
        runId: String,
        stepName: String,
    ): StepAttemptRecord? = persistenceBoundary(
        PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.LOAD, persistenceFailureDiagnosticObserver,
        classify = ::classifyStepAttemptFailure,
    ) { synchronized(monitor) {
        stepAttempts.values
            .asSequence()
            .filter { it.runId == runId && it.stepName == stepName }
            .maxWithOrNull(compareBy<StepAttemptRecord>({ it.startedAt }, { it.attemptId }))
    } }

    override suspend fun listStepAttempts(runId: String): List<StepAttemptRecord> = persistenceBoundary(
        PersistenceResourceKind.STEP_ATTEMPT, PersistenceOperation.LIST, persistenceFailureDiagnosticObserver,
        classify = ::classifyStepAttemptFailure,
    ) { synchronized(monitor) {
        stepAttempts.values
            .filter { it.runId == runId }
            .sortedWith(compareBy<StepAttemptRecord>({ it.startedAt }, { it.stepName }, { it.attemptId }))
    } }
}
private data class CheckpointKey(
    val workflowName: String,
    val workflowId: String,
)

private data class AttemptKey(
    val runId: String,
    val stepName: String,
    val attemptId: String,
)

internal fun newCheckpointGeneration(): String = UUID.randomUUID().toString()
