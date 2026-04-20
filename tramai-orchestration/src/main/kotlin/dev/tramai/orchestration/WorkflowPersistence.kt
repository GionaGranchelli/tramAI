package dev.tramai.orchestration

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
    )
}

/**
 * Persistence configuration for a typed workflow.
 */
data class WorkflowPersistence<S>(
    val checkpointStore: WorkflowCheckpointStore,
    val stateCodec: WorkflowStateCodec<S>,
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
) : RuntimeException(message)

/**
 * Raised when a checkpoint write or delete is attempted with stale revision state.
 */
class WorkflowCheckpointConflictException(
    message: String,
) : RuntimeException(message)

/**
 * Simple in-memory checkpoint store for tests and lightweight local use.
 */
class InMemoryWorkflowCheckpointStore : WorkflowCheckpointStore {
    private val checkpoints = linkedMapOf<CheckpointKey, WorkflowCheckpoint>()
    private val monitor = Any()

    override suspend fun load(
        workflowName: String,
        workflowId: String,
    ): WorkflowCheckpoint? = synchronized(monitor) {
        checkpoints[CheckpointKey(workflowName, workflowId)]
    }

    override suspend fun save(
        checkpoint: WorkflowCheckpoint,
        expectedRevision: Long?,
    ): WorkflowCheckpoint = synchronized(monitor) {
        val key = CheckpointKey(checkpoint.workflowName, checkpoint.workflowId)
        val existing = checkpoints[key]

        if (expectedRevision == null && existing != null) {
            throw WorkflowCheckpointConflictException(
                "Checkpoint for workflow '${checkpoint.workflowName}' and workflowId='${checkpoint.workflowId}' already exists at revision ${existing.revision}",
            )
        }

        if (expectedRevision != null && existing == null) {
            throw WorkflowCheckpointConflictException(
                "Checkpoint for workflow '${checkpoint.workflowName}' and workflowId='${checkpoint.workflowId}' does not exist for expected revision $expectedRevision",
            )
        }

        if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
            throw WorkflowCheckpointConflictException(
                "Checkpoint for workflow '${checkpoint.workflowName}' and workflowId='${checkpoint.workflowId}' is at revision ${existing.revision}, not expected revision $expectedRevision",
            )
        }

        val persisted = checkpoint.copy(
            revision = (existing?.revision ?: 0) + 1,
        )
        checkpoints[key] = persisted
        persisted
    }

    override suspend fun delete(
        workflowName: String,
        workflowId: String,
        expectedRevision: Long?,
    ) {
        synchronized(monitor) {
            val key = CheckpointKey(workflowName, workflowId)
            val existing = checkpoints[key]

            if (expectedRevision != null && existing == null) {
                throw WorkflowCheckpointConflictException(
                    "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' does not exist for expected revision $expectedRevision",
                )
            }

            if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
                throw WorkflowCheckpointConflictException(
                    "Checkpoint for workflow '$workflowName' and workflowId='$workflowId' is at revision ${existing.revision}, not expected revision $expectedRevision",
                )
            }

            checkpoints.remove(key)
        }
    }
}

private data class CheckpointKey(
    val workflowName: String,
    val workflowId: String,
)
