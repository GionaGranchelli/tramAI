package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Owns checkpoint enumeration, candidate filtering, and the poll cadence.
 *
 * Polling semantics preserved verbatim: deterministic ordering by workflow
 * name then workflow id; skip when the worker is not accepting work, the
 * workflow is already active, the partition is not owned, a lease already
 * exists, or the checkpoint is in [WorkflowRecoveryState.Required]; claims via
 * [LeaseCoordinator] and hands successful claims to
 * [WorkflowExecutionSupervisor.launch]. Poll failures flow through the safe
 * persistence-failure boundary and cancel immediately on cancellation.
 */
internal class CheckpointPoller(
    private val config: WorkerConfig,
    private val checkpointCatalog: WorkflowCheckpointCatalog,
    private val workerRegistryStore: WorkerRegistryStore?,
    private val partitionStrategy: PartitionAssignmentStrategy,
    private val leaseCoordinator: LeaseCoordinator,
    private val executionSupervisor: WorkflowExecutionSupervisor,
    private val observability: TramaiWorkerObserver,
    private val acceptingWork: () -> Boolean,
) {
    suspend fun pollLoop() {
        while (currentCoroutineContext().isActive && acceptingWork()) {
            try {
                pollOnce()
                delay(config.pollIntervalMillis)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                observability.onPollFailed(
                    config.workerId,
                    safeWorkerObservableFailure(PersistenceResourceKind.CHECKPOINT, PersistenceOperation.LIST, error),
                )
                delay(maxOf(100L, config.pollIntervalMillis))
            }
        }
    }

    /** One poll iteration: list, order, and process every checkpoint. */
    internal suspend fun pollOnce() {
        val checkpoints = checkpointCatalog.listCheckpoints()
            .sortedWith(compareBy<WorkflowCheckpoint>({ it.workflowName }, { it.workflowId }))
        checkpoints.forEach { checkpoint -> processCheckpoint(checkpoint) }
    }

    internal suspend fun processCheckpoint(checkpoint: WorkflowCheckpoint) {
        if (!acceptingWork() || executionSupervisor.isRunning(checkpoint.workflowId)) return
        if (!ownsPartition(checkpoint.workflowId)) return
        if (leaseCoordinator.currentLease(checkpoint.workflowName, checkpoint.workflowId) != null) return
        if (checkpoint.recoveryState is WorkflowRecoveryState.Required) {
            // Blocked workflow awaiting operator resolution — skip silently. The
            // unknown-attempt event is already emitted once when the attempt is first
            // detected (recoverAttemptIfNeeded), not on every poll cycle.
            return
        }

        val lease = leaseCoordinator.claim(checkpoint) ?: return
        executionSupervisor.launch(checkpoint, lease)
    }

    private suspend fun ownsPartition(workflowId: String): Boolean {
        if (!config.partitionEnabled) {
            return true
        }
        val activeWorkers = workerRegistryStore?.listActiveWorkers()
            ?.filter { it.poolName == config.poolName }
            ?.sortedBy { it.workerId }
            ?.map { it.workerId }
            .orEmpty()
        return partitionStrategy.ownsPartition(workflowId, config.workerId, activeWorkers)
    }
}
