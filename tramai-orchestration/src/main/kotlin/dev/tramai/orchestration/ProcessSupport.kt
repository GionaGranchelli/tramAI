package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.streams.toList

internal const val processTerminationGracePeriodMillis = 1_000L
internal const val processTerminationKillWaitMillis = 1_000L
private const val processTerminationPollIntervalMillis = 25L

/**
 * Raised when a process tree survives [CancellableProcessLifecycle.terminateAndAwait]
 * beyond the bounded grace and force-kill waits. Carries the surviving PIDs as a
 * diagnostic; it never replaces a primary [CancellationException] or domain exception
 * (consumers suppress it onto the primary failure instead).
 */
internal class ProcessTreeSurvivorException(
    val survivorPids: List<Long>,
) : RuntimeException(
    "Process tree did not terminate within the bounded cleanup window; surviving PIDs: ${survivorPids.joinToString()}",
)

/**
 * Result of a bounded process-tree cleanup.
 *
 * @param survivors PIDs still alive after the force-kill deadline (diagnostic only).
 * @param failures cleanup failures recorded during termination (never thrown by the
 *   lifecycle itself — consumers decide how to surface them).
 */
internal class ProcessCleanupResult(
    val survivors: List<Long>,
    val failures: List<Pair<String, Throwable>>,
)

/**
 * Internal, reusable, bounded lifecycle for a spawned process tree.
 *
 * Contract:
 * 1. [attachTo] registers a cancellation handler on the owning [Job] with an
 *    attach-then-active-check, so cancellation between process creation and handler
 *    registration is impossible.
 * 2. [requestTermination] is non-suspending, idempotent and never throws. It snapshots
 *    the tree, closes stdin/stdout/stderr (which unblocks any blocking readers), and
 *    requests graceful termination of every descendant plus the root.
 * 3. [awaitExit] is cancellable: cancellation invokes [requestTermination] immediately,
 *    so termination begins before structured concurrency can wait on reader jobs.
 * 4. [terminateAndAwait] runs under `NonCancellable + IO`, escalates graceful → forced
 *    termination with bounded waits, and reports survivors instead of waiting forever.
 *    It never calls an unbounded `Process.waitFor()`.
 * 5. Cleanup is idempotent — repeated calls from cancellation handlers, timeout
 *    handling and outer `finally` blocks are safe (atomic lifecycle state).
 *
 * The guarantee is deliberately bounded: TramAI promptly closes pipes, requests tree
 * termination, escalates to forced termination, waits within configured bounds, and
 * reports any surviving process. It does not claim every OS process can always be killed.
 */
internal class CancellableProcessLifecycle(
    private val process: Process,
    private val gracePeriodMillis: Long = processTerminationGracePeriodMillis,
    private val forceKillWaitMillis: Long = processTerminationKillWaitMillis,
    private val onFailure: (String, Throwable) -> Unit = { message, error ->
        System.err.println("[tramai-orchestration] $message: ${error.message ?: error}")
    },
) {
    private enum class State { RUNNING, TERMINATION_REQUESTED, CLEANED_UP }

    private val state = AtomicReference(State.RUNNING)
    private val cleanupFailures = CopyOnWriteArrayList<Pair<String, Throwable>>()

    /**
     * Attach the cancellation handler to [job]. If [job] is already cancelled at attach
     * time, termination is requested immediately (attach-then-active-check).
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    fun attachTo(job: Job) {
        job.invokeOnCompletion(onCancelling = true) {
            requestTermination()
        }
        if (job.isCancelled) {
            requestTermination()
        }
    }

    /** Whether [requestTermination] has already run (used for exactly-once cleanup). */
    fun isTerminationRequested(): Boolean = state.get() != State.RUNNING

    /**
     * Non-suspending, idempotent, never throws. Closes the process pipes (unblocking
     * stdout/stderr readers), snapshots the tree and requests graceful termination of
     * all descendants plus the root. Cleanup failures are recorded for later reporting.
     */
    fun requestTermination() {
        if (!state.compareAndSet(State.RUNNING, State.TERMINATION_REQUESTED)) {
            return
        }
        closeQuietly(process.outputStream, "stdin")
        closeQuietly(process.inputStream, "stdout")
        closeQuietly(process.errorStream, "stderr")
        processTreeHandles().forEach { handle ->
            if (handle.isAlive) {
                try {
                    handle.destroy()
                } catch (error: Throwable) {
                    recordFailure("Failed to request termination of process ${handle.pid()}", error)
                }
            }
        }
    }

    /**
     * Cancellable wait for the root process to exit. Cancellation of the awaiting
     * coroutine requests termination immediately (never suspends for cleanup).
     *
     * Races handled:
     * - process exits before callback registration → [onExit] is already done;
     * - cancellation occurs before callback registration → [attachTo] or the
     *   invokeOnCancellation handler already requested termination;
     * - process exits concurrently with cancellation → exactly one continuation
     *   outcome wins (the first `resume`).
     */
    suspend fun awaitExit(): Int {
        val exitFuture = process.onExit()
        return suspendCancellableCoroutine { continuation ->
            if (exitFuture.isDone) {
                continuation.resume(runCatching { process.exitValue() }.getOrDefault(-1))
            } else {
                exitFuture.thenRun {
                    continuation.resume(runCatching { process.exitValue() }.getOrDefault(-1))
                }
                continuation.invokeOnCancellation {
                    requestTermination()
                }
            }
        }
    }

    /**
     * Bounded, cancellation-safe final cleanup. Runs under `NonCancellable + IO`.
     *
     * 1. Graceful termination request (idempotent).
     * 2. Bounded grace-period wait.
     * 3. [Process.destroyForcibly] for surviving descendants and root.
     * 4. Bounded force-kill wait.
     * 5. Survivor inspection — survivors are reported, never waited on unboundedly.
     *
     * @return cleanup result; failures and survivors are recorded, not thrown here.
     */
    suspend fun terminateAndAwait(): ProcessCleanupResult = withContext(NonCancellable + Dispatchers.IO) {
        requestTermination()
        val handles = processTreeHandles()
        waitForHandlesToExitUninterruptibly(handles, gracePeriodMillis)
        handles.forEach { handle ->
            if (handle.isAlive) {
                try {
                    handle.destroyForcibly()
                } catch (error: Throwable) {
                    recordFailure("Failed to force-kill process ${handle.pid()}", error)
                }
            }
        }
        waitForHandlesToExitUninterruptibly(handles, forceKillWaitMillis)
        val survivors = handles.filter { it.isAlive }.map { it.pid() }
        if (survivors.isNotEmpty()) {
            recordFailure(
                "Process tree did not terminate within $forceKillWaitMillis ms after forced kill",
                ProcessTreeSurvivorException(survivors),
            )
        }
        ProcessCleanupResult(survivors = survivors, failures = cleanupFailures.toList())
    }

    /** Close process pipes without requesting termination (normal completion path). */
    fun dispose() {
        state.set(State.CLEANED_UP)
        closeQuietly(process.outputStream, "stdin")
    }

    private fun processTreeHandles(): List<ProcessHandle> {
        val handle = process.toHandle()
        val descendants = handle.descendants().use { stream -> stream.toList() }
        return descendants + handle
    }

    private fun closeQuietly(closeable: AutoCloseable?, label: String) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (error: Throwable) {
            recordFailure("Failed to close $label stream", error)
        }
    }

    private fun recordFailure(message: String, error: Throwable) {
        cleanupFailures += message to error
        onFailure(message, error)
    }
}

/**
 * Suppresses [result]'s failures (and survivor diagnostics) onto [primary] when present,
 * or returns them for the caller to surface. Used by consumers so cleanup failures never
 * replace the primary [CancellationException] or domain exception.
 */
internal fun Throwable.suppressCleanup(result: ProcessCleanupResult) {
    result.failures.forEach { (_, error) -> addSuppressed(error) }
    if (result.survivors.isNotEmpty()) {
        addSuppressed(ProcessTreeSurvivorException(result.survivors))
    }
}

internal fun waitForHandlesToExitUninterruptibly(
    handles: List<ProcessHandle>,
    timeoutMillis: Long,
) {
    var interrupted = false
    try {
        if (handles.none(ProcessHandle::isAlive)) {
            return
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadlineNanos) {
            if (handles.none(ProcessHandle::isAlive)) {
                return
            }
            try {
                TimeUnit.MILLISECONDS.sleep(processTerminationPollIntervalMillis)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }
}

/**
 * Bounded process exit wait (no unbounded `waitFor()`). Returns the exit value, or null
 * if the process is still alive after [timeoutMillis]. Never throws [InterruptedException].
 */
internal fun Process.waitForBounded(timeoutMillis: Long): Int? {
    var interrupted = false
    try {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadlineNanos) {
            if (!isAlive) {
                return exitValue()
            }
            try {
                TimeUnit.MILLISECONDS.sleep(processTerminationPollIntervalMillis)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return if (isAlive) null else exitValue()
    } finally {
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }
}
