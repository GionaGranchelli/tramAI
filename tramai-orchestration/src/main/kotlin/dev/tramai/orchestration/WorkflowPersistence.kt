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

    data class Required(
        val record: WorkflowRecoveryRecord,
    ) : WorkflowRecoveryState
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
    /**
     * Wall-clock instant (epoch millis) at which this checkpoint revision was
     * saved. Framework-owned workflow checkpoint saves explicitly supply the
     * workflow's injected Clock reading. When decoding legacy file records
     * that lack the field, 0L represents UNAVAILABLE historical save time —
     * never "1970-01-01".
     */
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
        val current =
            persistenceBoundary(
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
                checkpoint =
                    current.copy(
                        recoveryState = WorkflowRecoveryState.Required(record),
                        checkpointGeneration = expectedGeneration,
                    ),
                expectedRevision = expectedRevision,
            )
        }
    }

    /**
     * Durable terminal cancellation state for this run, or null when the run was never cancelled.
     *
     * Reads only lifecycle columns: a fence must be able to ask this question without decoding the state
     * payload. The default refuses: a store that cannot answer authoritatively must not answer at all.
     */
    suspend fun lifecycle(
        workflowName: String,
        workflowId: String,
    ): WorkflowLifecycleState? = throw refuseCancellation(this)

    /**
     * Record terminal cancellation for this run, conditionally on the caller's [WorkflowCancellationRequest],
     * which supplies both the expected revision and the expected generation.
     *
     * The write is the run's linearization point: it moves the row to the next revision, and the generation it
     * commits is the one the caller observed. It does not take or steal a `WorkflowLease` — a worker holding a
     * lease has no authority to overwrite terminal cancellation, and cancelling must not require revoking the
     * lease that ordinary progress writes depend on.
     *
     * Idempotent by construction: once cancelled, the recorded state is returned unchanged and the revision is
     * not advanced again. A repeated command therefore reports the same [WorkflowLifecycleState.revision],
     * which is what proves which call owns the linearization point.
     *
     * The default refuses: see [WorkflowCancellationUnsupportedException].
     */
    suspend fun cancel(request: WorkflowCancellationRequest): WorkflowLifecycleState = throw refuseCancellation(this)

    /**
     * Decide whether one unit of authoritative execution may be admitted for this run.
     *
     * The default refuses, for the same reason cancellation refuses: a profile that cannot decide this with a
     * conditional write cannot fence execution, and refusing is the only honest answer.
     */
    suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission = throw refuseAdmission(this)

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
        val current =
            persistenceBoundary(
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
                checkpoint =
                    current.copy(
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
internal fun encodeRecoveryState(state: WorkflowRecoveryState): String? =
    when (state) {
        WorkflowRecoveryState.Normal -> {
            null
        }

        is WorkflowRecoveryState.Required -> {
            encodeMetadata(
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
    }

internal fun decodeRecoveryState(payload: String?): WorkflowRecoveryState {
    if (payload.isNullOrBlank()) return WorkflowRecoveryState.Normal
    val map =
        try {
            decodeMetadata(payload)
        } catch (error: CorruptCheckpointException) {
            throw error
        } catch (error: Throwable) {
            throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
        }
    val reason =
        map["reason"]?.let { name ->
            WorkflowRecoveryReason.entries.firstOrNull { it.name == name }
        } ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    val stepName =
        map["stepName"]
            ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    val attemptId =
        map["attemptId"]
            ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    val priorWorkerId =
        map["priorWorkerId"]
            ?: throw CorruptCheckpointException("Persisted workflow checkpoint is invalid", payload)
    val detectedAt =
        map["detectedAtEpochMillis"]?.toLongOrNull()
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
class InMemoryWorkflowCheckpointStore(
    observer: PersistenceFailureDiagnosticObserver = NoOpPersistenceFailureDiagnosticObserver,
) : WorkflowCheckpointStore,
    WorkflowCheckpointCatalog,
    StepAttemptRecordStore by InMemoryStepAttemptIndex() {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver = observer
        internal set

    private val checkpoints = linkedMapOf<CheckpointKey, WorkflowCheckpoint>()
    private val cancellations = linkedMapOf<CheckpointKey, WorkflowLifecycleState>()
    private val admissions = linkedMapOf<CheckpointKey, Long>()
    private val stepAttempts = linkedMapOf<AttemptKey, StepAttemptRecord>()
    internal val monitor = Any()

    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LOAD,
            persistenceFailureDiagnosticObserver,
        ) { synchronized(monitor) { checkpoints[CheckpointKey(workflowName, workflowId)] } }

    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.SAVE,
            persistenceFailureDiagnosticObserver,
        ) {
            synchronized(monitor) {
                val key = CheckpointKey(checkpoint.workflowName, checkpoint.workflowId)
                val existing = checkpoints[key]
                // A cancelled run is not a run whose checkpoint moves: the terminal state is authority over the
                // row, not something a writer holding a matching revision may overwrite.
                if (cancellations.containsKey(key)) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.SAVE,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                if (expectedRevision == null && existing != null) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.SAVE,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                if (expectedRevision != null && existing == null) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.SAVE,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.SAVE,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                val generationChanged =
                    expectedRevision != null && existing != null &&
                        existing.checkpointGeneration != checkpoint.checkpointGeneration
                if (generationChanged) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.SAVE,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                val persisted =
                    checkpoint.copy(
                        revision = (existing?.revision ?: 0) + 1,
                        checkpointGeneration = existing?.checkpointGeneration ?: newCheckpointGeneration(),
                    )
                checkpoints[key] = persisted
                persisted
            }
        }

    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
        expectedGeneration: String?,
    ) {
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.DELETE,
            persistenceFailureDiagnosticObserver,
        ) {
            synchronized(monitor) {
                val key = CheckpointKey(workflowName, workflowId)
                val existing = checkpoints[key]
                // Retention: a cancelled run's row is the evidence of the cancellation, not a completed run's
                // leftover. Deleting it would let the run be recreated as if it had never been cancelled.
                if (existing != null && cancellations.containsKey(key)) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.DELETE,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                if (expectedRevision != null && existing == null) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.DELETE,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.DELETE,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                if (expectedRevision != null && existing != null && existing.checkpointGeneration != expectedGeneration) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.DELETE,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                checkpoints.remove(key)
            }
        }
    }

    override suspend fun lifecycle(
        workflowName: String,
        workflowId: String,
    ): WorkflowLifecycleState? =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LOAD,
            persistenceFailureDiagnosticObserver,
        ) { synchronized(monitor) { cancellations[CheckpointKey(workflowName, workflowId)] } }

    override suspend fun cancel(request: WorkflowCancellationRequest): WorkflowLifecycleState =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.COMPARE_AND_SET,
            persistenceFailureDiagnosticObserver,
        ) {
            synchronized(monitor) {
                val key = CheckpointKey(request.workflowName, request.workflowId)
                val existing =
                    checkpoints[key] ?: throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.COMPARE_AND_SET,
                        PersistenceFailureCode.CONFLICT,
                    )
                cancellations[key]?.let { return@synchronized it }
                val preconditionHolds =
                    existing.revision == request.expectedRevision &&
                        existing.checkpointGeneration == request.expectedGeneration
                if (!preconditionHolds) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.COMPARE_AND_SET,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                // Derived, not claimed: work was already admitted, so an external effect may have landed and
                // this cancellation can never be reported as clean.
                val someoneWasAdmitted = (admissions[key] ?: 0L) > 0L
                val status =
                    if (someoneWasAdmitted || request.uncertain) {
                        WorkflowLifecycleStatus.CANCELLED_UNCERTAIN
                    } else {
                        WorkflowLifecycleStatus.CANCELLED
                    }
                val cancelled =
                    WorkflowLifecycleState(
                        status = status,
                        revision = existing.revision + 1,
                        checkpointGeneration = existing.checkpointGeneration,
                        cancelledAtEpochMillis = request.cancelledAtEpochMillis,
                        cancelledBy = request.actor,
                        reason = request.reason,
                    )
                checkpoints[key] = existing.copy(revision = cancelled.revision)
                cancellations[key] = cancelled
                cancelled
            }
        }

    override suspend fun admit(request: WorkflowAdmissionRequest): WorkflowAdmission =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.COMPARE_AND_SET,
            persistenceFailureDiagnosticObserver,
        ) {
            synchronized(monitor) {
                val key = CheckpointKey(request.workflowName, request.workflowId)
                val existing =
                    checkpoints[key] ?: throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.COMPARE_AND_SET,
                        PersistenceFailureCode.CONFLICT,
                    )
                cancellations[key]?.let { cancelled ->
                    return@synchronized WorkflowAdmission(false, admissions[key] ?: 0L, cancelled)
                }
                val preconditionHolds =
                    existing.revision == request.expectedRevision &&
                        existing.checkpointGeneration == request.expectedGeneration
                if (!preconditionHolds) {
                    throw safePersistenceFailure(
                        PersistenceResourceKind.CHECKPOINT,
                        PersistenceOperation.COMPARE_AND_SET,
                        PersistenceFailureCode.CONFLICT,
                    )
                }
                val sequence = (admissions[key] ?: 0L) + 1L
                admissions[key] = sequence
                WorkflowAdmission(true, sequence)
            }
        }

    override suspend fun listCheckpoints(): List<WorkflowCheckpoint> =
        persistenceBoundary(
            PersistenceResourceKind.CHECKPOINT,
            PersistenceOperation.LIST,
            persistenceFailureDiagnosticObserver,
        ) { synchronized(monitor) { checkpoints.values.toList() } }

    // Step-attempt persistence is delegated to InMemoryStepAttemptIndex (see below): it is a separate
    // responsibility, and keeping it here pushed the class past the function-count ceiling.
}

/**
 * In-memory step-attempt persistence for [InMemoryWorkflowCheckpointStore].
 *
 * Extracted so the checkpoint store keeps one responsibility per type and stays under the class function
 * ceiling; behaviour and failure semantics are unchanged.
 */
internal class InMemoryStepAttemptIndex : StepAttemptRecordStore {
    var persistenceFailureDiagnosticObserver: PersistenceFailureDiagnosticObserver =
        NoOpPersistenceFailureDiagnosticObserver
        internal set

    private val stepAttempts = linkedMapOf<AttemptKey, StepAttemptRecord>()
    private val monitor = Any()

    override suspend fun recordStepAttempt(record: StepAttemptRecord): StepAttemptRecord =
        persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT,
            PersistenceOperation.SAVE,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            synchronized(monitor) {
                stepAttempts[AttemptKey(record.runId, record.stepName, record.attemptId)] = record
                record
            }
        }

    override suspend fun updateStepAttempt(record: StepAttemptRecord): StepAttemptRecord =
        persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT,
            PersistenceOperation.SAVE,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            synchronized(monitor) {
                val key = AttemptKey(record.runId, record.stepName, record.attemptId)
                require(stepAttempts.containsKey(key)) { "Step attempt does not exist" }
                stepAttempts[key] = record
                record
            }
        }

    override suspend fun compareAndSetStepAttempt(
        expected: StepAttemptRecord,
        updated: StepAttemptRecord,
    ): Boolean =
        persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT,
            PersistenceOperation.COMPARE_AND_SET,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            synchronized(monitor) {
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
            }
        }

    override suspend fun latestStepAttempt(
        runId: String,
        stepName: String,
    ): StepAttemptRecord? =
        persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT,
            PersistenceOperation.LOAD,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            synchronized(monitor) {
                stepAttempts.values
                    .asSequence()
                    .filter { it.runId == runId && it.stepName == stepName }
                    .fold(null as StepAttemptRecord?) { acc, record ->
                        if (acc == null || record.startedAt >= acc.startedAt) record else acc
                    }
            }
        }

    override suspend fun listStepAttempts(runId: String): List<StepAttemptRecord> =
        persistenceBoundary(
            PersistenceResourceKind.STEP_ATTEMPT,
            PersistenceOperation.LIST,
            persistenceFailureDiagnosticObserver,
            classify = ::classifyStepAttemptFailure,
        ) {
            synchronized(monitor) {
                stepAttempts.values
                    .filter { it.runId == runId }
                    .sortedWith(compareBy<StepAttemptRecord>({ it.startedAt }, { it.stepName }))
            }
        }
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
