package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the graceful-shutdown state and sequence for the worker.
 *
 * State owned here: the shutdown-start CAS (idempotency), the accept-work and
 * graceful-shutdown flags (read by the poller and the execution supervisor),
 * the poll/heartbeat jobs, and the JVM shutdown hook. The lifecycle
 * controller owns the root job/scope; this coordinator owns everything that
 * mutates during shutdown.
 *
 * The frozen sequence is preserved verbatim:
 * `acceptingWork = false → onShutdownStarted → stop poller → drain up to
 * drainTimeoutMillis → cancel residuals → bounded final join →
 * onDrainProgress → remove JVM shutdown hook → stop heartbeat → best-effort
 * unregister → onShutdownComplete → onWorkerStopped → cancel root supervisor`.
 */
internal class WorkerShutdownCoordinator(
    private val config: WorkerConfig,
    private val observability: TramaiWorkerObserver,
    private val executionSupervisor: WorkflowExecutionSupervisor,
    private val workerRegistryStore: WorkerRegistryStore?,
) {
    /**
     * Per-root shutdown idempotency. The CONTROLLER's atomic lifecycle state
     * is the authority for "who owns a shutdown" (STARTING/RUNNING/CRASHED →
     * SHUTTING_DOWN CAS); this reference only prevents the frozen sequence
     * from running twice for the SAME generation root. It is keyed by root
     * identity — not a lifecycle-global boolean — so a new generation's
     * shutdown can never be rejected by stale state from a previous completed
     * lifecycle (P1a: a shutdown arriving in the claim→prepare gap must be
     * accepted, not lost).
     */
    private val shutdownRoot = AtomicReference<Job?>(null)
    private var pollJob: Job? = null
    private var heartbeatJob: Job? = null

    @Volatile
    private var shutdownHook: Thread? = null

    @Volatile
    private var acceptingWork: Boolean = false

    @Volatile
    private var shuttingDownGracefully: Boolean = false

    fun isAcceptingWork(): Boolean = acceptingWork

    fun isShuttingDownGracefully(): Boolean = shuttingDownGracefully

    internal fun hasShutdownHook(): Boolean = shutdownHook != null

    /**
     * Resets shutdown state for a new lifecycle. Must run at the very start
     * of start(), after the ownership claim, so a concurrent shutdown during
     * registration is never rejected by stale state from a previous completed
     * lifecycle. The per-root [shutdownRoot] is cleared so the new
     * generation's root can be recorded.
     */
    fun prepareLifecycleStart() {
        shuttingDownGracefully = false
        shutdownRoot.set(null)
    }

    /**
     * Starts accepting work. Must run before the poll/heartbeat jobs are
     * launched so their first iteration sees [isAcceptingWork] true.
     */
    fun beginAcceptingWork() {
        acceptingWork = true
    }

    /** Registers the JVM shutdown hook immediately after it is added. */
    fun onShutdownHook(shutdownHook: Thread) {
        this.shutdownHook = shutdownHook
    }

    /** Registers the heartbeat job immediately after it is launched. */
    fun onHeartbeatJob(heartbeatJob: Job) {
        this.heartbeatJob = heartbeatJob
    }

    /** Registers the poll job immediately after it is launched. */
    fun onPollJob(pollJob: Job) {
        this.pollJob = pollJob
    }

    /**
     * Performs the frozen graceful-shutdown sequence.
     *
     * @return true if this invocation ran the sequence for [rootSupervisor];
     *   false if the same root was already shut down (idempotent repeat —
     *   direct coordinator callers, never the controller, which owns the
     *   transition authority). Callers must clear lifecycle state only when
     *   true and only for the same root they captured.
     */
    suspend fun shutdown(rootSupervisor: Job): Boolean {
        val current = shutdownRoot.get()
        if (current === rootSupervisor) {
            return false
        }
        if (!shutdownRoot.compareAndSet(current, rootSupervisor)) {
            // Lost a concurrent claim for a different root; the winner owns
            // the sequence for its generation.
            return false
        }
        shuttingDownGracefully = true
        acceptingWork = false
        observability.onShutdownStarted(config.workerId)
        pollJob?.cancelAndJoin()
        val executions = executionSupervisor.activeExecutionsSnapshot()
        val drainStartedAt = System.currentTimeMillis()
        val drainTimeoutMillis = config.drainTimeoutMillis
        val drained = withTimeoutOrNull(drainTimeoutMillis) {
            executions.mapNotNull { it.executionJob }.joinAll()
            true
        } ?: false
        if (!drained) {
            executions.forEach { execution ->
                execution.executionJob?.cancel(CancellationException("Worker drain timeout exceeded"))
            }
            val residualTimeoutMillis = (drainTimeoutMillis - (System.currentTimeMillis() - drainStartedAt)).coerceAtLeast(1L)
            withTimeoutOrNull(residualTimeoutMillis) {
                executions.mapNotNull { it.executionJob }.joinAll()
            }
        }
        val executionCount = executions.size - executionSupervisor.activeExecutionCount()
        val executionsLeft = executionSupervisor.activeExecutionCount()
        observability.onDrainProgress(config.workerId, done = executionCount, pending = executionsLeft)
        shutdownHook?.let { hook ->
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // JVM is already shutting down - removal is not allowed during shutdown
            }
            shutdownHook = null
        }
        heartbeatJob?.cancelAndJoin()
        withTimeoutOrNull(config.drainTimeoutMillis) {
            runCatching { workerRegistryStore?.unregisterWorker(config.workerId) }
        }
        observability.onShutdownComplete(config.workerId)
        observability.onWorkerStopped(config.workerId)
        rootSupervisor.cancel()
        // Clear transient handles of this lifecycle (master nulled pollJob and
        // heartbeatJob too), so a shutdown during the NEXT startup can never
        // operate on previous-generation job handles.
        pollJob = null
        heartbeatJob = null
        return true
    }
}
