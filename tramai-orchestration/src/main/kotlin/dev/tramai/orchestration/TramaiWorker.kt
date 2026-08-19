package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

interface TramaiWorkerObserver {
    fun onWorkerStarted(workerId: String) = Unit

    fun onWorkerStopped(workerId: String) = Unit

    fun onLeaseAcquired(workflowId: String, workerId: String) = Unit

    fun onLeaseReleased(workflowId: String, workerId: String) = Unit

    fun onLeaseExpired(workflowId: String, workerId: String) = Unit

    fun onLeaseRenewalFailed(
        workflowId: String,
        workerId: String,
        error: Throwable,
    ) = Unit

    fun onLeaseReleaseFailed(
        workflowId: String,
        workerId: String,
        error: Throwable,
    ) = Unit

    fun onPollFailed(
        workerId: String,
        error: Throwable,
    ) = Unit

    fun onWorkTakenOver(
        workflowId: String,
        previousWorkerId: String,
        newWorkerId: String,
    ) = Unit

    fun onUnknownAttempt(
        runId: String,
        stepName: String,
        priorWorkerId: String,
        attemptTime: Long,
    ) = Unit

    fun onStepAttemptStarted(
        runId: String,
        stepName: String,
        attemptId: String,
        workerId: String,
    ) = Unit

    fun onStepAttemptCompleted(
        runId: String,
        stepName: String,
        attemptId: String,
        workerId: String,
    ) = Unit

    fun onStepAttemptFailed(
        runId: String,
        stepName: String,
        attemptId: String,
        workerId: String,
        error: Throwable,
    ) = Unit

    fun onShutdownStarted(workerId: String) = Unit

    fun onDrainProgress(workerId: String, done: Int, pending: Int) = Unit

    fun onShutdownComplete(workerId: String) = Unit

    fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) = Unit

    fun onLeaseRenewed(workflowId: String, workerId: String, newExpiry: Long) = Unit

    fun onLeaseContested(workflowId: String, claimantWorkerId: String, currentWorkerId: String) = Unit

    fun onWorkflowAbandoned(workflowId: String, workerId: String, lastStep: String?, timeoutMillis: Long) = Unit
}

object NoOpTramaiWorkerObserver : TramaiWorkerObserver

class StaleWorkflowLeaseException(
    message: String,
) : RuntimeException(message) {
    var failureCode: PersistenceFailureCode? = null
        internal set
    var safeFactoryTrusted: Boolean = false
        internal set
}

/**
 * Public worker façade.
 *
 * All worker behavior is owned by the decomposed subsystems behind
 * [WorkerLifecycleController]: polling, lease coordination, lease renewal,
 * execution supervision, heartbeat publishing, recovery, and shutdown
 * sequencing. This class keeps only the public API surface (constructors,
 * start/crash/shutdown/close, latestFailure) and the observer contract.
 */
class TramaiWorker(
    private val config: WorkerConfig,
    leaseStore: WorkflowLeaseStore,
    checkpointStore: WorkflowCheckpointStore,
    checkpointCatalog: WorkflowCheckpointCatalog,
    stepAttemptStore: StepAttemptRecordStore,
    workflowBindings: WorkflowBindingRegistry,
    observability: TramaiWorkerObserver = NoOpTramaiWorkerObserver,
    partitionStrategy: PartitionAssignmentStrategy = ModHashPartitionStrategy(),
) : AutoCloseable {
    private val lifecycle = WorkerLifecycleController(
        config = config,
        leaseStore = leaseStore,
        checkpointStore = checkpointStore,
        checkpointCatalog = checkpointCatalog,
        stepAttemptStore = stepAttemptStore,
        workflowBindings = workflowBindings,
        observability = observability,
        partitionStrategy = partitionStrategy,
    )

    constructor(
        config: WorkerConfig,
        leaseStore: WorkflowLeaseStore,
        checkpointStore: WorkflowCheckpointStore,
        workflowBindings: WorkflowBindingRegistry,
        observability: TramaiWorkerObserver = NoOpTramaiWorkerObserver,
        partitionStrategy: PartitionAssignmentStrategy = ModHashPartitionStrategy(),
    ) : this(
        config = config,
        leaseStore = leaseStore,
        checkpointStore = checkpointStore,
        checkpointCatalog = checkpointStore as? WorkflowCheckpointCatalog
            ?: throw IllegalArgumentException(
                "TramaiWorker requires a WorkflowCheckpointCatalog when checkpointStore does not implement it directly",
            ),
        stepAttemptStore = checkpointStore as? StepAttemptRecordStore
            ?: throw IllegalArgumentException(
                "TramaiWorker requires a StepAttemptRecordStore when checkpointStore does not implement it directly",
            ),
        workflowBindings = workflowBindings,
        observability = observability,
        partitionStrategy = partitionStrategy,
    )

    suspend fun start() {
        lifecycle.start()
    }

    fun crash(cause: CancellationException = CancellationException("Worker '${config.workerId}' crashed")) {
        lifecycle.crash(cause)
    }

    suspend fun shutdown() {
        lifecycle.shutdown()
    }

    override fun close() {
        runBlocking {
            shutdown()
        }
    }

    fun latestFailure(workflowId: String): Throwable? = lifecycle.latestFailure(workflowId)
}
