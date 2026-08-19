package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

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
    private val shutdownStarted = AtomicBoolean(false)
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

    /**
     * Resets shutdown state and starts accepting work. Must run before the
     * poll/heartbeat jobs are launched so their first iteration sees
     * [isAcceptingWork] true.
     */
    fun prepareStart() {
        acceptingWork = true
        shuttingDownGracefully = false
        shutdownStarted.set(false)
    }

    /** Registers the started jobs and the JVM shutdown hook. */
    fun onStarted(
        pollJob: Job,
        heartbeatJob: Job,
        shutdownHook: Thread,
    ) {
        this.pollJob = pollJob
        this.heartbeatJob = heartbeatJob
        this.shutdownHook = shutdownHook
    }

    /**
     * Runs the frozen shutdown sequence. Idempotent: a second call returns
     * immediately.
     */
    suspend fun shutdown(rootSupervisor: Job) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return
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
    }
}
