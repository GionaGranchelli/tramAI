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
 * ```
 * Suspend operations (register, drain, unregister) happen OUTSIDE the
 * transition primitive; the state itself never suspends.
 */
internal sealed interface WorkerLifecycleState {
    data object Stopped : WorkerLifecycleState
    data class Starting(val generation: Long, val root: Job) : WorkerLifecycleState
    data class Running(val generation: Long, val root: Job) : WorkerLifecycleState
    data class ShuttingDown(val generation: Long, val root: Job) : WorkerLifecycleState
    data class Crashed(val generation: Long, val root: Job) : WorkerLifecycleState
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
     * Test seam for the post-commit epilogue (M27): invoked immediately after
     * the atomic STARTING→RUNNING commit and BEFORE the post-commit re-check,
     * so a test can deterministically suspend the winner between "committed
     * RUNNING" and "emitted workerStarted/installed hook/accepted work". A
     * shutdown completing in this window must abort the epilogue — no
     * workerStarted after workerStopped, no hook, no acceptingWork.
     */
    internal var onRunCommitted: (suspend () -> Unit)? = null

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
        // Atomic RUNNING commit: the final revalidation and the commit are the
        // SAME CAS. If a shutdown already transitioned to SHUTTING_DOWN (or a
        // rollback released to STOPPED), this fails and the startup aborts —
        // it can never resurrect after a completed shutdown.
        if (!lifecycleState.compareAndSet(claimed, WorkerLifecycleState.Running(generation, supervisor))) {
            supervisor.cancel()
            reconcileRegistration(supervisor, generation)
            workerScope = null
            return
        }
        // Test seam: suspend the winner between the RUNNING commit and the
        // epilogue (M27). A shutdown completing here must abort the epilogue.
        onRunCommitted?.invoke()
        // Post-commit re-check: a shutdown may have transitioned us out of
        // RUNNING between the commit and here. The epilogue must never emit
        // workerStarted, install a JVM hook, or begin accepting work for a
        // generation whose shutdown already completed — that would violate the
        // shutdownComplete(B) → workerStarted(B) event-order invariant.
        val committed = lifecycleState.get()
        if (committed !is WorkerLifecycleState.Running ||
            committed.generation != generation ||
            committed.root !== supervisor
        ) {
            supervisor.cancel()
            reconcileRegistration(supervisor, generation)
            workerScope = null
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
     *   shutdown's unregister. We remove it so a failed startup never leaks a
     *   registry row. A newer generation can only register by claiming
     *   STOPPED→STARTING, and once it does the state below no longer matches;
     *   the narrow check-to-unregister window is acknowledged in the docs.
     * - A NEWER generation owns STARTING/RUNNING/CRASHED: never touch the row.
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
            is WorkerLifecycleState.Running,
            is WorkerLifecycleState.Crashed,
            -> false
        }
        if (!ours) {
            return
        }
        withContext(NonCancellable) {
            // Re-verify immediately before the unregister: in the STOPPED
            // branch a newer generation may have claimed (STOPPED→STARTING)
            // and registered between the snapshot above and here. If it did,
            // never touch its row — the workerId-keyed registry cannot tell
            // the rows apart. While we still hold STARTING/SHUTTING_DOWN no
            // newer generation can register, so this only guards STOPPED.
            val now = lifecycleState.get()
            val stillOurs = when (now) {
                is WorkerLifecycleState.Starting -> now.root === root && now.generation == generation
                is WorkerLifecycleState.ShuttingDown -> now.root === root && now.generation == generation
                WorkerLifecycleState.Stopped -> true
                is WorkerLifecycleState.Running,
                is WorkerLifecycleState.Crashed,
                -> false
            }
            if (!stillOurs) {
                return@withContext
            }
            try {
                workerRegistryStore?.unregisterWorker(config.workerId)
            } catch (cleanupError: Throwable) {
                cleanupError.rethrowIfCancellation()
            }
        }
    }

    fun crash(cause: CancellationException = CancellationException("Worker '${config.workerId}' crashed")) {
        val current = lifecycleState.get()
        if (current is WorkerLifecycleState.Running) {
            // Transition FIRST, then cancel: if a concurrent shutdown wins
            // RUNNING→SHUTTING_DOWN between the read and the CAS, the CAS
            // fails and we do not cancel the root — the shutdown owner's
            // drain owns root cancellation and still emits graceful events.
            // Cancelling first would abort that drain under a graceful report.
            if (lifecycleState.compareAndSet(current, WorkerLifecycleState.Crashed(current.generation, current.root))) {
                current.root.cancel(cause)
            }
        }
    }

    suspend fun shutdown() {
        val current = lifecycleState.get()
        val target = when (current) {
            is WorkerLifecycleState.Starting -> WorkerLifecycleState.ShuttingDown(current.generation, current.root)
            is WorkerLifecycleState.Running -> WorkerLifecycleState.ShuttingDown(current.generation, current.root)
            is WorkerLifecycleState.Crashed -> WorkerLifecycleState.ShuttingDown(current.generation, current.root)
            WorkerLifecycleState.Stopped,
            is WorkerLifecycleState.ShuttingDown,
            -> return
        }
        if (!lifecycleState.compareAndSet(current, target)) {
            // Lost the transition to a concurrent caller; it owns the
            // shutdown. Observers return without acting.
            return
        }
        shutdownCoordinator.shutdown(target.root)
        // Release to STOPPED only after the drain completes; the shutdown
        // owner is the one who captured the generation.
        if (lifecycleState.compareAndSet(target, WorkerLifecycleState.Stopped)) {
            workerScope = null
        }
    }

    fun latestFailure(workflowId: String): Throwable? = executionSupervisor.latestFailure(workflowId)
}
