package dev.tramai.orchestration

import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic harness for the worker-lifecycle property suite (Epic 8.2c).
 *
 * Wraps the real [TramaiWorker] with a controllable registry and checkpoint
 * catalog so tests can inject registration failures, block registration
 * (shutdown-during-registration), block heartbeat/poll loops
 * (cancellation ownership), and observe every lifecycle event with exact
 * counters. No sleeps: blocking is coordinated on deferreds/gates.
 */
internal class WorkerLifecyclePropertyHarness(
    workerId: String = "lifecycle-worker",
    pollIntervalMillis: Long = 20L,
    drainTimeoutMillis: Long = 60_000L,
) {
    private val workerId: String = workerId
    internal val registrations = AtomicInteger(0)
    internal val unregistrations = AtomicInteger(0)
    internal val heartbeats = AtomicInteger(0)

    internal val hooks = WorkerRegistryHooks()
    internal val catalogHooks = CheckpointCatalogHooks()

    val observer = RecordingWorkerObserver()

    private val leaseStore = InMemoryWorkflowLeaseStore()
    private val checkpointStore = InMemoryWorkflowCheckpointStore()
    private val registry = ControllableWorkerRegistry(leaseStore, hooks, registrations, unregistrations)
    private val catalog = ControllableCheckpointCatalog(checkpointStore, catalogHooks)

    val worker: TramaiWorker = TramaiWorker(
        config = WorkerConfig(
            workerId = workerId,
            poolName = "tests",
            pollIntervalMillis = pollIntervalMillis,
            leaseDurationMillis = 5_000,
            drainTimeoutMillis = drainTimeoutMillis,
        ),
        leaseStore = registry,
        checkpointStore = checkpointStore,
        checkpointCatalog = catalog,
        stepAttemptStore = checkpointStore,
        workflowBindings = WorkflowBindingRegistry { },
        observability = observer,
    )

    /** True when the registry currently holds an active row for the worker. */
    suspend fun isRegistered(): Boolean = leaseStore.listActiveWorkers().any { it.workerId == workerId }

    internal data class ObservedState(
        val workerStartedEvents: Int,
        val shutdownStartedEvents: Int,
        val shutdownCompleteEvents: Int,
        val workerStoppedEvents: Int,
        val registrations: Int,
        val unregistrations: Int,
        val registeredNow: Boolean,
        val phase: WorkerLifecyclePhase,
    )

    internal var observedPhase: WorkerLifecyclePhase = WorkerLifecyclePhase.STOPPED
        private set

    internal fun snapshot(registeredNow: Boolean) = ObservedState(
        workerStartedEvents = observer.workerStarted.size,
        shutdownStartedEvents = observer.shutdownStarted.size,
        shutdownCompleteEvents = observer.shutdownComplete.size,
        workerStoppedEvents = observer.workerStopped.size,
        registrations = registrations.get(),
        unregistrations = unregistrations.get(),
        registeredNow = registeredNow,
        phase = observedPhase,
    )

    /**
     * Executes one lifecycle action against the real worker and derives the
     * observed phase from the emitted events (start succeeded when
     * onWorkerStarted fired; shutdown completed when onShutdownComplete
     * fired; crash is fire-and-forget and terminal until a cleanup).
     */
    internal suspend fun runAction(action: WorkerLifecycleAction) {
        val startedBefore = observer.workerStarted.size
        val shutdownCompletedBefore = observer.shutdownComplete.size
        when (action) {
            WorkerLifecycleAction.START,
            WorkerLifecycleAction.START_AGAIN,
            -> {
                runCatching { worker.start() }
                if (observer.workerStarted.size > startedBefore) {
                    observedPhase = WorkerLifecyclePhase.RUNNING
                }
            }

            WorkerLifecycleAction.SHUTDOWN,
            WorkerLifecycleAction.SHUTDOWN_AGAIN,
            WorkerLifecycleAction.SHUTDOWN_AFTER_CRASH,
            WorkerLifecycleAction.CLOSE,
            -> {
                worker.shutdown()
                if (observer.shutdownComplete.size > shutdownCompletedBefore) {
                    observedPhase = WorkerLifecyclePhase.STOPPED
                }
            }

            WorkerLifecycleAction.CRASH -> {
                worker.crash()
                // Crash only transitions a RUNNING generation (model parity);
                // STOPPED/CRASHED crash is a no-op.
                if (observedPhase == WorkerLifecyclePhase.RUNNING) {
                    observedPhase = WorkerLifecyclePhase.CRASHED
                }
            }
        }
    }

    internal class WorkerRegistryHooks {
        var onRegister: (suspend () -> Unit)? = null
        var onHeartbeat: (suspend () -> Unit)? = null
        var failNextRegistration: Boolean = false
    }

    internal class CheckpointCatalogHooks {
        var onList: (suspend () -> Unit)? = null
    }

    internal class RecordingWorkerObserver : TramaiWorkerObserver {
        val workerStarted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val shutdownStarted = java.util.concurrent.CopyOnWriteArrayList<String>()
        val shutdownComplete = java.util.concurrent.CopyOnWriteArrayList<String>()
        val workerStopped = java.util.concurrent.CopyOnWriteArrayList<String>()
        val heartbeats = java.util.concurrent.CopyOnWriteArrayList<String>()
        var onWorkerStoppedHook: (() -> Unit)? = null

        override fun onWorkerStarted(workerId: String) {
            workerStarted += workerId
        }

        override fun onShutdownStarted(workerId: String) {
            shutdownStarted += workerId
        }

        override fun onShutdownComplete(workerId: String) {
            shutdownComplete += workerId
        }

        override fun onWorkerStopped(workerId: String) {
            workerStopped += workerId
            // Synchronous hook: fires inside the shutdown sequence at the exact
            // instant the stopped event is dispatched — the deterministic
            // observation point for loop-cancellation ownership.
            onWorkerStoppedHook?.invoke()
        }

        override fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) {
            heartbeats += workerId
        }
    }
}

/** Registry wrapper: injectable registration failure/blocking + counters. */
private class ControllableWorkerRegistry(
    private val delegate: InMemoryWorkflowLeaseStore,
    private val hooks: WorkerLifecyclePropertyHarness.WorkerRegistryHooks,
    private val registrations: AtomicInteger,
    private val unregistrations: AtomicInteger,
) : WorkflowLeaseStore by delegate, WorkerRegistryStore by delegate {
    override suspend fun registerWorker(
        workerId: String,
        poolName: String,
        version: String,
        capabilityLabels: Set<String>,
        host: String,
    ) {
        hooks.onRegister?.invoke()
        if (hooks.failNextRegistration) {
            hooks.failNextRegistration = false
            throw IllegalStateException("injected worker registration failure")
        }
        registrations.incrementAndGet()
        delegate.registerWorker(workerId, poolName, version, capabilityLabels, host)
    }

    override suspend fun unregisterWorker(workerId: String) {
        unregistrations.incrementAndGet()
        delegate.unregisterWorker(workerId)
    }

    override suspend fun updateHeartbeat(workerId: String) {
        hooks.onHeartbeat?.invoke()
        delegate.updateHeartbeat(workerId)
    }
}

/** Catalog wrapper: injectable poll blocking. */
private class ControllableCheckpointCatalog(
    private val delegate: InMemoryWorkflowCheckpointStore,
    private val hooks: WorkerLifecyclePropertyHarness.CheckpointCatalogHooks,
) : WorkflowCheckpointCatalog by delegate {
    override suspend fun listCheckpoints(): List<WorkflowCheckpoint> {
        hooks.onList?.invoke()
        return delegate.listCheckpoints()
    }
}

/** Blocks the caller until cancelled, signalling [exited] on the way out. */
internal suspend fun blockUntilCancelled(entered: kotlinx.coroutines.CompletableDeferred<Unit>, exited: kotlinx.coroutines.CompletableDeferred<Unit>) {
    entered.complete(Unit)
    try {
        awaitCancellation()
    } finally {
        exited.complete(Unit)
    }
}
