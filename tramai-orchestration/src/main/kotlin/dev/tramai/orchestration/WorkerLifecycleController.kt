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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Generation-aware lifecycle state of a worker. A single atomic reference
 * holds the complete lifecycle decision — root ownership, startup phase,
 * shutdown ownership, and generation identity together — so no two operations
 * can independently decide a transition and race each other through separate
 * atomics/booleans.
 *
 * Transitions (all CAS on the one reference, none suspending):
 * ```
 * STOPPED --start claim--> STARTING(g) --commit--> RUNNING(g)
 * STARTING(g) / RUNNING(g) --shutdown claim--> SHUTTING_DOWN(g) --drain done--> STOPPED
 * RUNNING(g) --crash--> CRASHED(g)
 * CRASHED(g) --shutdown claim--> SHUTTING_DOWN(g) --drain done--> STOPPED
 * STOPPED --cleanup reservation--> RECONCILING(g) --cleanup done--> STOPPED
 * ```
 * Suspend operations (register, drain, unregister) happen OUTSIDE the
 * transition primitive; the state itself never suspends. The RUNNING commit
 * and the activation epilogue are additionally linearized against shutdown()
 * and crash() claims by a non-suspending activation lock, so no operation can
 * ever observe a half-activated worker (see [activationLock]).
 */
internal sealed interface WorkerLifecycleState {
    data object Stopped : WorkerLifecycleState
    data class Starting(val generation: Long, val root: Job) : WorkerLifecycleState
    data class Running(val generation: Long, val root: Job) : WorkerLifecycleState
    data class ShuttingDown(val generation: Long, val root: Job) : WorkerLifecycleState
    data class Crashed(val generation: Long, val root: Job) : WorkerLifecycleState
    data class Reconciling(val generation: Long, val root: Job) : WorkerLifecycleState
}

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
     * The single atomic lifecycle state. Every transition is a CAS on this
     * reference; generation identity is carried inside the state, so a stale
     * shutdown state from a previous generation can never reject (or accept)
     * a decision for the current generation.
     */
    private val lifecycleState = AtomicReference<WorkerLifecycleState>(WorkerLifecycleState.Stopped)

    /** Monotonic generation counter; each successful claim consumes one. */
    private val generationCounter = AtomicLong(0)

    /**
     * Serializes the non-suspending lifecycle handoff: the RUNNING commit +
     * the entire activation epilogue (workerStarted, JVM hook, scope attach,
     * acceptingWork, heartbeat/poll handoff) in start(), the lifecycle claim
     * (→SHUTTING_DOWN) in shutdown(), and the crash claim (→CRASHED). No
     * coroutine suspension ever happens while this lock is held — it guards
     * only the transition itself, never registration/drain/unregister. This
     * is what makes the activation epilogue atomic against a concurrent
     * shutdown: shutdown can never observe (or bisect) a half-activated
     * worker, because claiming RUNNING→SHUTTING_DOWN requires the same lock
     * the epilogue holds.
     */
    private val activationLock = Any()

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

    /**
     * Test seam for the claim-to-prepare gap (M24): invoked immediately after
     * the ownership claim and BEFORE the shutdown-state reset, so a test can
     * deterministically suspend the winner between "owns the root" and
     * "shutdown state initialized". A shutdown arriving in this window must
     * still be accepted — the claim itself is a state transition, not a
     * separate boolean that a stale previous generation can block.
     */
    internal var onLifecycleStateClaimed: (suspend () -> Unit)? = null

    /**
     * Test seam for the RUNNING commit boundary (M25): invoked immediately
     * before the atomic STARTING→RUNNING commit, so a test can deterministically
     * suspend the startup after every revalidation has passed but before the
     * generation is committed as running. A shutdown completing in this window
     * must prevent the commit — no workerStarted/hook/acceptingWork afterwards.
     */
    internal var onRunCommitBoundary: (suspend () -> Unit)? = null

    /**
     * Test seam for the activation-serialization property (M27): invoked
     * synchronously INSIDE the activation critical section, immediately before
     * onWorkerStarted, so a test can block the start thread while it holds
     * the activation lock. While blocked, a concurrent shutdown must NOT be
     * able to fire shutdownStarted — proof that the epilogue cannot be
     * bisected. Production never sets it.
     */
    internal var onActivationStart: (() -> Unit)? = null

    suspend fun start() {
        // Test seam: suspend a contender that already observed STOPPED before
        // it attempts the claim (stale-contender property).
        onStartClaimBoundary?.invoke()
        val supervisor = SupervisorJob()
        val scope = CoroutineScope(supervisor + Dispatchers.Default)
        val generation = generationCounter.incrementAndGet()
        val claimed = WorkerLifecycleState.Starting(generation, supervisor)
        // Atomic ownership claim: exactly one concurrent start wins; the rest
        // cancel their provisional root and return. A lost contender has ZERO
        // authority — it cannot reach the shutdown-state reset, the
        // registration, or the RUNNING commit.
        if (!lifecycleState.compareAndSet(WorkerLifecycleState.Stopped, claimed)) {
            supervisor.cancel()
            return
        }
        workerScope = scope
        startedAt = System.currentTimeMillis()
        // Test seam: suspend the winner between the ownership claim and the
        // shutdown-state reset (M24). A shutdown arriving here wins the
        // STARTING→SHUTTING_DOWN transition; we re-check below.
        onLifecycleStateClaimed?.invoke()
        // Re-check after the seam: a shutdown may have claimed the generation
        // while we were suspended. Abort WITHOUT touching the shutdown-state
        // reset — the shutdown owner owns that state now.
        if (lifecycleState.get() !== claimed) {
            supervisor.cancel()
            workerScope = null
            return
        }
        // Reset shutdown state only after winning the ownership claim: a stale
        // contender that observed STOPPED but lost the CAS must never reset the
        // winning generation's shutdown state. The winner resets so a shutdown
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
            rollbackStart(claimed)
            throw cancel
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            // Registration failed after the ownership claim: roll the lifecycle
            // back to STOPPED so a later start() can retry normally — no
            // started event, no hook, no accepting work, root discarded.
            rollbackStart(claimed)
            throw error
        }
        // Test seam: suspend immediately before the RUNNING commit (M25). A
        // shutdown completing in this window must prevent the commit.
        onRunCommitBoundary?.invoke()

        // Activation critical section: the STARTING verification, the RUNNING
        // commit, and the ENTIRE activation epilogue (workerStarted, JVM hook
        // install + handoff, scope attach, acceptingWork, heartbeat + poll
        // launch + handoff) are linearized against shutdown()'s and crash()'s
        // lifecycle claims by the shared activation lock. A concurrent
        // shutdown can therefore NEVER observe — let alone bisect — a
        // half-activated worker: claiming RUNNING→SHUTTING_DOWN requires the
        // same lock, so it either sees STARTING (its claim wins and we abort)
        // or RUNNING with the full epilogue already emitted. No check AFTER
        // the commit is needed, and none exists: the lock is the linearization.
        var activationLost = false
        synchronized(activationLock) {
            if (lifecycleState.get() !== claimed) {
                // A shutdown claimed our generation while we were outside the
                // lock (at the seam or in registration). It owns the drain; we
                // must not emit anything. Abort without touching the
                // shutdown-state reset — the shutdown owner owns that state.
                activationLost = true
            } else {
                lifecycleState.compareAndSet(claimed, WorkerLifecycleState.Running(generation, supervisor))
                // Test seam: block synchronously inside the critical section,
                // before workerStarted (activation-bisect property).
                onActivationStart?.invoke()
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
                // Order preserved verbatim from the pre-decomposition worker:
                // heartbeat launches before poll, and each resource is handed
                // to the shutdown owner immediately inside the critical
                // section so a concurrent shutdown never sees a null handle
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
        }
        if (activationLost) {
            supervisor.cancel()
            reconcileRegistration(supervisor, generation)
            workerScope = null
        }
    }

    /**
     * Rolls a failed/cancelled startup back to STOPPED.
     *
     * Order matters (P2/M26): cancel the root, reconcile the registry row in
     * [NonCancellable], and ONLY THEN release the ownership claim. Releasing
     * first opens the window where a newer generation claims and registers
     * between the release and the unregister — and the workerId-keyed registry
     * cannot tell the rows apart, so the old cleanup would delete the new
     * generation's row. While we still own STARTING, no newer generation can
     * register, so the unregister can only ever touch our own row.
     */
    private suspend fun rollbackStart(claimed: WorkerLifecycleState.Starting) {
        claimed.root.cancel()
        reconcileRegistration(claimed.root, claimed.generation)
        if (lifecycleState.compareAndSet(claimed, WorkerLifecycleState.Stopped)) {
            workerScope = null
        }
    }

    /**
     * Removes a registry row only when it cannot belong to a newer generation.
     *
     * - We still own STARTING(g)/SHUTTING_DOWN(g): no newer generation can
     *   register while we hold the state, so the row (if any) is ours — safe.
     * - State is STOPPED: the shutdown owner already released; the row is a
     *   zombie committed by our own suspended registration after the
     *   shutdown's unregister. Cleanup is NOT allowed to unregister while
     *   naked STOPPED — a newer generation could claim STOPPED→STARTING and
     *   register between our check and the (suspendable) unregister, and the
     *   workerId-keyed registry cannot tell the rows apart. Instead we reserve
     *   the cleanup atomically (STOPPED→RECONCILING): while the reservation is
     *   held, a new generation's claim fails and it retries, so the unregister
     *   can only ever touch our own zombie row.
     * - A NEWER generation owns STARTING/RUNNING/CRASHED (or another cleanup
     *   holds RECONCILING): never touch the row.
     *
     * Suspending cleanup runs in [NonCancellable] so caller cancellation
     * cannot skip it.
     */
    private suspend fun reconcileRegistration(root: Job, generation: Long) {
        val current = lifecycleState.get()
        val ours = when (current) {
            is WorkerLifecycleState.Starting -> current.root === root && current.generation == generation
            is WorkerLifecycleState.ShuttingDown -> current.root === root && current.generation == generation
            WorkerLifecycleState.Stopped -> true
            is WorkerLifecycleState.Reconciling,
            is WorkerLifecycleState.Running,
            is WorkerLifecycleState.Crashed,
            -> false
        }
        if (!ours) {
            return
        }
        withContext(NonCancellable) {
            val now = lifecycleState.get()
            if (now is WorkerLifecycleState.Stopped) {
                val reservation = WorkerLifecycleState.Reconciling(generation, root)
                // Reserve the STOPPED cleanup atomically. While we hold
                // RECONCILING, a newer generation cannot claim STOPPED→STARTING
                // and cannot register, so our unregister can never delete its
                // row. The reservation is released (back to STOPPED) in a
                // finally, so even a failed unregister cannot strand the
                // lifecycle in RECONCILING.
                if (!lifecycleState.compareAndSet(
                        WorkerLifecycleState.Stopped,
                        reservation,
                    )
                ) {
                    return@withContext
                }
                try {
                    workerRegistryStore?.unregisterWorker(config.workerId)
                } catch (cleanupError: Throwable) {
                    cleanupError.rethrowIfCancellation()
                } finally {
                    lifecycleState.compareAndSet(
                        reservation,
                        WorkerLifecycleState.Stopped,
                    )
                }
            } else {
                // Still ours (STARTING or SHUTTING_DOWN with this root): no
                // newer generation can have registered, so the unregister is
                // safe without a reservation.
                try {
                    workerRegistryStore?.unregisterWorker(config.workerId)
                } catch (cleanupError: Throwable) {
                    cleanupError.rethrowIfCancellation()
                }
            }
        }
    }

    fun crash(cause: CancellationException = CancellationException("Worker '${config.workerId}' crashed")) {
        synchronized(activationLock) {
            val current = lifecycleState.get()
            if (current is WorkerLifecycleState.Running) {
                // Transition FIRST, then cancel: if a concurrent shutdown wins
                // RUNNING→SHUTTING_DOWN between the read and the CAS, the CAS
                // fails and we do not cancel the root — the shutdown owner's
                // drain owns root cancellation and still emits graceful events.
                // Cancelling first would abort that drain under a graceful
                // report. The lock serializes this claim against the activation
                // epilogue: a crash can never bisect a half-activated worker.
                if (lifecycleState.compareAndSet(current, WorkerLifecycleState.Crashed(current.generation, current.root))) {
                    current.root.cancel(cause)
                }
            }
        }
    }

    suspend fun shutdown() {
        // The lifecycle claim (→SHUTTING_DOWN) is inside the activation lock,
        // serialized against the activation epilogue. A shutdown can therefore
        // never claim a worker whose epilogue is mid-flight: either it claims
        // before the epilogue (the startup's verify fails and it aborts) or
        // after (the full epilogue is already emitted). The drain below runs
        // OUTSIDE the lock — no lock spans suspendable drain/unregister.
        val target = synchronized(activationLock) {
            val current = lifecycleState.get()
            val t = when (current) {
                is WorkerLifecycleState.Starting -> WorkerLifecycleState.ShuttingDown(current.generation, current.root)
                is WorkerLifecycleState.Running -> WorkerLifecycleState.ShuttingDown(current.generation, current.root)
                is WorkerLifecycleState.Crashed -> WorkerLifecycleState.ShuttingDown(current.generation, current.root)
                WorkerLifecycleState.Stopped,
                is WorkerLifecycleState.Reconciling,
                is WorkerLifecycleState.ShuttingDown,
                -> null
            }
            if (t == null || !lifecycleState.compareAndSet(current, t)) {
                null
            } else {
                t
            }
        } ?: return

        shutdownCoordinator.shutdown(target.root)
        // Release to STOPPED only after the drain completes; the shutdown
        // owner is the one who captured the generation.
        if (lifecycleState.compareAndSet(target, WorkerLifecycleState.Stopped)) {
            workerScope = null
        }
    }

    fun latestFailure(workflowId: String): Throwable? = executionSupervisor.latestFailure(workflowId)
}
