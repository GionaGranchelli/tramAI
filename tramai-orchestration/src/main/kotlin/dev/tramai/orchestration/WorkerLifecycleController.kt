package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
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
    /**
     * Atomic lifecycle-ownership primitive: exactly one generation owns the
     * root at a time. The claim is a CAS so eight racing starts cannot both
     * pass a plain null-guard; shutdown clears ownership only when the root
     * is still the one it captured, so a completed shutdown never erases a
     * root created by a start that ran mid-drain.
     */
    private val lifecycleOwner = java.util.concurrent.atomic.AtomicReference<Job?>(null)
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
        // Idempotency guard first (master parity): while a generation owns the
        // lifecycle, start is a no-op — and a duplicate start must NEVER reset
        // the shutdown state of a generation that is mid-shutdown.
        if (lifecycleOwner.get() != null) {
            return
        }
        // Reset shutdown state before claiming ownership, so a concurrent
        // shutdown during STARTING/registration is accepted, never rejected
        // by stale CAS from a previous completed lifecycle.
        shutdownCoordinator.prepareLifecycleStart()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(supervisor + Dispatchers.Default)
        // Atomic ownership claim: exactly one concurrent start wins; the rest
        // observe the existing generation (RUNNING/STARTING/CRASHED
        // idempotent) and return.
        if (!lifecycleOwner.compareAndSet(null, supervisor)) {
            return
        }
        workerScope = scope
        startedAt = System.currentTimeMillis()
        try {
            heartbeatPublisher.registerWorker()
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            // Registration failed after the ownership claim: roll the lifecycle
            // back to STOPPED so a later start() can retry normally — no
            // started event, no hook, no accepting work, root discarded.
            lifecycleOwner.compareAndSet(supervisor, null)
            workerScope = null
            supervisor.cancel()
            try {
                workerRegistryStore?.unregisterWorker(config.workerId)
            } catch (cleanupError: Throwable) {
                cleanupError.rethrowIfCancellation()
            }
            throw error
        }
        // Revalidate the ownership claim: a concurrent shutdown may have owned
        // (or be owning) this generation while registration was suspended. An
        // aborted startup must never emit onWorkerStarted, install a JVM hook,
        // accept work, or launch heartbeat/poll loops for a generation that has
        // already been — or is being — shut down.
        if (lifecycleOwner.get() !== supervisor || shutdownCoordinator.isShuttingDownGracefully()) {
            lifecycleOwner.compareAndSet(supervisor, null)
            workerScope = null
            supervisor.cancel()
            // If the registration committed after the shutdown's unregister,
            // remove the zombie row.
            try {
                workerRegistryStore?.unregisterWorker(config.workerId)
            } catch (cleanupError: Throwable) {
                cleanupError.rethrowIfCancellation()
            }
            return
        }
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
        lifecycleOwner.get()?.cancel(cause)
    }

    suspend fun shutdown() {
        val supervisor = lifecycleOwner.get() ?: return
        // Clear lifecycle ownership only when THIS call owned the shutdown
        // (CAS winner) and only if the root is still the one we captured. A
        // concurrent shutdown that loses the CAS must not null the owner
        // while the winner is still draining, and a completed shutdown must
        // not erase a root created by a start() that ran mid-drain.
        if (shutdownCoordinator.shutdown(supervisor)) {
            if (lifecycleOwner.get() === supervisor) {
                lifecycleOwner.compareAndSet(supervisor, null)
                workerScope = null
            }
        }
    }

    fun latestFailure(workflowId: String): Throwable? = executionSupervisor.latestFailure(workflowId)
}
