package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Owns the worker lifecycle: the single root [SupervisorJob]/[CoroutineScope],
 * the startup sequence, and child-component orchestration.
 *
 * This is the only component that constructs the worker root coroutine
 * lifecycle. Components receive the scope (execution supervisor) or run as
 * scope children (poller, heartbeat); none invents its own lifecycle scope.
 *
 * Startup sequence preserved verbatim: reset shutdown state → create
 * SupervisorJob + scope → record start time → register worker → emit
 * onWorkerStarted → install JVM shutdown hook → accept work → start heartbeat
 * and poll loops.
 */
internal class WorkerLifecycleController(
    private val config: WorkerConfig,
    leaseStore: WorkflowLeaseStore,
    checkpointStore: WorkflowCheckpointStore,
    private val checkpointCatalog: WorkflowCheckpointCatalog,
    stepAttemptStore: StepAttemptRecordStore,
    private val workflowBindings: WorkflowBindingRegistry,
    private val observability: TramaiWorkerObserver,
    partitionStrategy: PartitionAssignmentStrategy,
) {
    private val workerRegistryStore = leaseStore as? WorkerRegistryStore
    private var workerJob: Job? = null
    private var workerScope: CoroutineScope? = null
    private var startedAt: Long = 0L

    private val leaseCoordinator = LeaseCoordinator(config, leaseStore, observability)
    private val recoveryCoordinator = WorkflowRecoveryCoordinator(leaseStore, stepAttemptStore)
    private val leaseRenewalLoop = LeaseRenewalLoop(config, leaseStore, observability)

    // Assigned below: the shutdown coordinator needs the execution supervisor,
    // while the supervisor (and poller) read its flags through lambdas. The
    // lambdas are only invoked after start(), so lateinit is safe.
    private lateinit var shutdownCoordinator: WorkerShutdownCoordinator

    private val executionSupervisor = WorkflowExecutionSupervisor(
        config = config,
        leaseStore = leaseStore,
        checkpointStore = checkpointStore,
        stepAttemptStore = stepAttemptStore,
        workflowBindings = workflowBindings,
        observability = observability,
        leaseCoordinator = leaseCoordinator,
        recoveryCoordinator = recoveryCoordinator,
        leaseRenewalLoop = leaseRenewalLoop,
        shuttingDownGracefully = { shutdownCoordinator.isShuttingDownGracefully() },
    )
    private val poller = CheckpointPoller(
        config = config,
        checkpointCatalog = checkpointCatalog,
        workerRegistryStore = workerRegistryStore,
        partitionStrategy = partitionStrategy,
        leaseCoordinator = leaseCoordinator,
        executionSupervisor = executionSupervisor,
        observability = observability,
        acceptingWork = { shutdownCoordinator.isAcceptingWork() },
    )
    private val heartbeatPublisher = WorkerHeartbeatPublisher(
        config = config,
        workerRegistryStore = workerRegistryStore,
        observability = observability,
    )

    init {
        shutdownCoordinator = WorkerShutdownCoordinator(
            config = config,
            observability = observability,
            executionSupervisor = executionSupervisor,
            workerRegistryStore = workerRegistryStore,
        )
    }

    suspend fun start() {
        if (workerJob != null) {
            return
        }
        // Reset shutdown state BEFORE creating/exposing the new root or
        // suspending in registration (verbatim master ordering): a concurrent
        // shutdown during registration must be accepted, never rejected by
        // stale state from a previous completed lifecycle.
        shutdownCoordinator.prepareLifecycleStart()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(supervisor + Dispatchers.Default)
        workerScope = scope
        workerJob = supervisor
        startedAt = System.currentTimeMillis()
        heartbeatPublisher.registerWorker()
        observability.onWorkerStarted(config.workerId)
        val hook = Thread {
            runBlocking(Dispatchers.IO) {
                shutdown()
            }
        }
        Runtime.getRuntime().addShutdownHook(hook)
        shutdownCoordinator.onShutdownHook(hook)
        executionSupervisor.attachScope(scope)
        shutdownCoordinator.beginAcceptingWork()
        // Order preserved verbatim from the pre-decomposition worker: heartbeat
        // launches before poll, and each resource is handed to the shutdown
        // owner immediately so a concurrent shutdown never sees a null handle
        // for something that already exists.
        val heartbeatJob = scope.launch {
            heartbeatPublisher.heartbeatLoop(
                startedAtMillis = { startedAt },
                claimedCount = { executionSupervisor.activeExecutionCount() },
            )
        }
        shutdownCoordinator.onHeartbeatJob(heartbeatJob)
        val pollJob = scope.launch {
            poller.pollLoop()
        }
        shutdownCoordinator.onPollJob(pollJob)
    }

    fun crash(cause: CancellationException = CancellationException("Worker '${config.workerId}' crashed")) {
        workerJob?.cancel(cause)
    }

    suspend fun shutdown() {
        val supervisor = workerJob ?: return
        // Clear lifecycle ownership only when THIS call owned the shutdown
        // (CAS winner) and only if the root is still the one we captured. A
        // concurrent shutdown that loses the CAS must not null workerJob
        // while the winner is still draining, and a completed shutdown must
        // not erase a root created by a start() that ran mid-drain.
        if (shutdownCoordinator.shutdown(supervisor)) {
            if (workerJob === supervisor) {
                workerJob = null
                workerScope = null
            }
        }
    }

    fun latestFailure(workflowId: String): Throwable? = executionSupervisor.latestFailure(workflowId)
}
