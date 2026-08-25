package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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

    /**
     * Test seam for the stale-contender property: invoked between the
     * idempotency guard and the ownership claim so a test can deterministically
     * suspend a starter that already observed STOPPED. Production never sets it.
     */
    internal var onStartClaimBoundary: (suspend () -> Unit)? = null

    suspend fun start() {
        // Idempotency guard first (master parity): while a generation owns the
        // lifecycle, start is a no-op — and a duplicate start must NEVER reset
        // the shutdown state of a generation that is mid-shutdown.
        if (lifecycleOwner.get() != null) {
            return
        }
        onStartClaimBoundary?.invoke()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(supervisor + Dispatchers.Default)
        // Atomic ownership claim: exactly one concurrent start wins; the rest
        // observe the existing generation (RUNNING/STARTING/CRASHED
        // idempotent) and return. A lost contender has ZERO authority: it must
        // not mutate any generation-level state, so the per-generation
        // shutdown-state reset happens only after the claim (below).
        if (!lifecycleOwner.compareAndSet(null, supervisor)) {
            supervisor.cancel()
            return
        }
        workerScope = scope
        startedAt = System.currentTimeMillis()
        // Reset shutdown state only after winning the ownership claim: a stale
        // contender that observed STOPPED but lost the CAS must never reset the
        // winning generation's shutdown state (that would let a second shutdown
        // become owner of an active shutdown). The winner resets so a shutdown
        // during STARTING/registration is accepted, never rejected by stale CAS
        // from a previous completed lifecycle.
        shutdownCoordinator.prepareLifecycleStart()
        try {
            heartbeatPublisher.registerWorker()
        } catch (cancel: CancellationException) {
            // Caller cancellation must still roll the lifecycle back — it must
            // not leave a half-owned STARTING generation (the root is
            // independent of the caller's coroutine, so cancellation does not
            // clean it up for us). Preserve the SAME cancellation instance.
            rollbackOwnership(supervisor)
            throw cancel
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            // Registration failed after the ownership claim: roll the lifecycle
            // back to STOPPED so a later start() can retry normally — no
            // started event, no hook, no accepting work, root discarded.
            rollbackOwnership(supervisor)
            throw error
        }
        // Revalidate the ownership claim: a concurrent shutdown may have owned
        // (or be owning) this generation while registration was suspended. An
        // aborted startup must never emit onWorkerStarted, install a JVM hook,
        // accept work, or launch heartbeat/poll loops for a generation that has
        // already been — or is being — shut down.
        if (lifecycleOwner.get() !== supervisor) {
            // Ownership was released while we were suspended (the shutdown
            // owner completed and cleared it, or a newer generation claimed).
            // Remove a zombie registry row only when nothing newer owns the
            // lifecycle; the shutdown owner already cancelled our root.
            reconcileRegistration(supervisor)
            supervisor.cancel()
            return
        }
        if (shutdownCoordinator.isShuttingDownGracefully()) {
            // A shutdown currently OWNS this generation and is still draining
            // it. The shutdown owner remains responsible for releasing
            // ownership and cancelling the root; the aborted startup must not
            // release ownership out from under the drain, or a new generation
            // could start mid-drain. Only remove a zombie row committed after
            // the shutdown's unregister.
            reconcileRegistration(supervisor)
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

    /**
     * Rolls a failed/cancelled startup back to STOPPED. Releases lifecycle
     * ownership ONLY if this generation still owns it and no shutdown owns it —
     * an in-progress shutdown keeps ownership (and root cancellation) for
     * itself, since it is still draining this root. Best-effort zombie-row
     * reconciliation follows.
     */
    private suspend fun rollbackOwnership(supervisor: Job) {
        if (lifecycleOwner.get() === supervisor && !shutdownCoordinator.isShuttingDownGracefully()) {
            lifecycleOwner.compareAndSet(supervisor, null)
            workerScope = null
            supervisor.cancel()
        }
        reconcileRegistration(supervisor)
    }

    /**
     * Removes a registry row this startup may have committed after the shutdown
     * owner's unregister. Never touches the registry when a NEWER generation
     * owns the lifecycle (its own startup registers). Suspending cleanup runs
     * in [NonCancellable] so caller cancellation cannot skip it.
     */
    private suspend fun reconcileRegistration(supervisor: Job) {
        val owner = lifecycleOwner.get()
        if (owner != null && owner !== supervisor) {
            return
        }
        withContext(NonCancellable) {
            try {
                workerRegistryStore?.unregisterWorker(config.workerId)
            } catch (cleanupError: Throwable) {
                cleanupError.rethrowIfCancellation()
            }
        }
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
