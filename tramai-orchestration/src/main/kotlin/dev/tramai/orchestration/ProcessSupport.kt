package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * Spawns a process with acquisition-ownership guarantees. [ensureActive] rejects
 * pre-existing cancellation before any process is created, and [start] runs inside
 * [NonCancellable] (outer hop changes no dispatcher, inner hop runs [start] on
 * [dispatcher]) so a cancellation arriving DURING [start] cannot discard the returned
 * [Process] — the caller attaches the lifecycle immediately after, so the tree is
 * never orphaned between acquisition and ownership.
 */
internal suspend fun startOwnedProcess(dispatcher: CoroutineDispatcher, start: () -> Process): Process {
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable) {
        withContext(dispatcher) {
            start()
        }
    }
}

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
 * Raised by [surfaceProcessCleanup] when a cleanup produced failures or survivors but
 * there is no primary failure to attach them to. Cleanup problems are never silently
 * swallowed on an otherwise successful execution.
 */
internal class ProcessCleanupException : RuntimeException(
    "Process cleanup failed; see suppressed causes",
)

/**
 * Result of a bounded process-tree cleanup.
 *
 * @param survivors PIDs still alive after the force-kill deadline (diagnostic only).
 * @param failures cleanup failures recorded during termination (never thrown by the
 *   lifecycle itself — consumers decide how to surface them). Survivors are represented
 *   exactly once, via [survivors]; they are not also duplicated in [failures].
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
 *    registration is impossible. It returns the [DisposableHandle] so consumers can
 *    detach the handler once the process step completes (no leak on long-lived jobs).
 * 2. [requestTermination] is non-suspending, idempotent and never throws. It snapshots
 *    the tree BEFORE closing stdin/stdout/stderr (so closing a pipe that makes the root
 *    exit can never reparent descendants out of the retained snapshot), then closes the
 *    pipes (unblocking any blocking readers) and requests graceful termination of every
 *    descendant plus the root.
 * 3. [awaitExit] is cancellable: cancellation invokes [requestTermination] immediately,
 *    so termination begins before structured concurrency can wait on reader jobs.
 * 4. [terminateAndAwait] runs under nested contexts — [NonCancellable] outside,
 *    `Dispatchers.IO` inside — escalates graceful → forced
 *    termination with bounded waits over the union of retained and fresh tree handles,
 *    and reports survivors instead of waiting forever. It never calls an unbounded
 *    `Process.waitFor()`.
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
     * ProcessHandle identities captured by [requestTermination] BEFORE pipes were closed.
     * If closing a pipe makes the root exit, surviving descendants are reparented and
     * would be missed by a later snapshot; retaining the handles (not PIDs, which the OS
     * may reuse) keeps them in [terminateAndAwait]'s union.
     */
    private val retainedHandles = CopyOnWriteArrayList<ProcessHandle>()

    /**
     * Attach the cancellation handler to [job] and return its [DisposableHandle] so the
     * caller can detach it when the process step completes. If [job] is already cancelled
     * at attach time, termination is requested immediately (attach-then-active-check).
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    fun attachTo(job: Job): DisposableHandle {
        val registration = job.invokeOnCompletion(onCancelling = true) {
            requestTermination()
        }
        if (job.isCancelled) {
            requestTermination()
        }
        return registration
    }

    /** Whether [requestTermination] has already run (used for exactly-once cleanup). */
    fun isTerminationRequested(): Boolean = state.get() != State.RUNNING

    /**
     * Non-suspending, idempotent, never throws. Snapshots the tree FIRST (before any
     * stream is closed), retains the snapshot as ProcessHandle identities, closes the
     * process pipes (unblocking stdout/stderr readers), then requests graceful
     * termination of all descendants plus the root. Cleanup failures are recorded for
     * later reporting.
     */
    fun requestTermination() {
        if (!state.compareAndSet(State.RUNNING, State.TERMINATION_REQUESTED)) {
            return
        }
        val handles = processTreeHandles()
        retainedHandles.addAll(handles)
        closeQuietly(process.outputStream, "stdin")
        closeQuietly(process.inputStream, "stdout")
        closeQuietly(process.errorStream, "stderr")
        terminateHandles(handles, graceful = true)
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
                continuation.resume(safeExitValue())
            } else {
                exitFuture.thenRun {
                    continuation.resume(safeExitValue())
                }
                continuation.invokeOnCancellation {
                    requestTermination()
                }
            }
        }
    }

    /**
     * Bounded, cancellation-safe final cleanup. Runs under nested contexts:
     * [NonCancellable] outside, `Dispatchers.IO` inside. The outer call never changes
     * dispatchers, so a cancellation arriving during the IO hop cannot replace the
     * caller's primary cancellation with a fresh instance on dispatch-back.
     *
     * 1. Graceful termination request (idempotent; snapshots retained pre-pipe-close).
     * 2. Bounded grace-period wait over the union of retained + fresh tree handles.
     * 3. Forced termination ([Process.destroyForcibly]) of surviving descendants then
     *    root — descendants are destroyed before the root.
     * 4. Bounded force-kill wait.
     * 5. Survivor inspection — survivors are reported, never waited on unboundedly.
     *
     * @return cleanup result; survivors and failures are recorded, not thrown here.
     */
    suspend fun terminateAndAwait(): ProcessCleanupResult = withContext(NonCancellable) {
        withContext(Dispatchers.IO) {
            requestTermination()
            val handles = resolveCleanupHandles()
            waitForHandlesToExitUninterruptibly(handles, gracePeriodMillis)
            terminateHandles(handles, graceful = false)
            waitForHandlesToExitUninterruptibly(handles, forceKillWaitMillis)
            val survivors = handles.filter { it.isAlive }.map { it.pid() }
            if (survivors.isNotEmpty()) {
                // Log-only: survivor diagnostics are represented once via ProcessCleanupResult.survivors,
                // never duplicated into failures as well.
                onFailure(
                    "Process tree did not terminate within $forceKillWaitMillis ms after forced kill",
                    ProcessTreeSurvivorException(survivors),
                )
            }
            ProcessCleanupResult(survivors = survivors, failures = cleanupFailures.toList())
        }
    }

    /** Close process pipes without requesting termination (normal completion path). */
    fun dispose() {
        state.set(State.CLEANED_UP)
        closeQuietly(process.outputStream, "stdin")
    }

    /** Union of retained (pre-pipe-close) handles, a fresh snapshot and the root, deduplicated by handle identity. */
    private fun resolveCleanupHandles(): List<ProcessHandle> {
        val retained = retainedHandles.toList()
        val fresh = processTreeHandles()
        val root = process.toHandle()
        // Dedup by ProcessHandle equality, never by PID: a reused PID would resolve to an
        // unrelated process. PIDs appear only in diagnostics downstream (survivor lists).
        val byHandle = linkedSetOf<ProcessHandle>()
        byHandle.addAll(retained)
        byHandle.addAll(fresh)
        byHandle.add(root)
        val rootPid = process.pid()
        // Stable order: descendants first, root last (destroy descendants before root).
        return byHandle.sortedBy { if (it.pid() == rootPid) 1 else 0 }
    }

    /** Destroy/force-kill alive handles; descendants before the root. */
    private fun terminateHandles(handles: List<ProcessHandle>, graceful: Boolean) {
        val rootPid = process.pid()
        val ordered = handles.sortedBy { if (it.pid() == rootPid) 1 else 0 }
        ordered.forEach { handle ->
            if (handle.isAlive) {
                try {
                    if (graceful) {
                        handle.destroy()
                    } else {
                        handle.destroyForcibly()
                    }
                } catch (error: Throwable) {
                    error.rethrowIfCancellation()
                    val verb = if (graceful) "request termination of" else "force-kill"
                    recordFailure("Failed to $verb process ${handle.pid()}", error)
                }
            }
        }
    }

    private fun processTreeHandles(): List<ProcessHandle> {
        val handle = process.toHandle()
        val descendants = handle.descendants().use { stream -> stream.toList() }
        return descendants + handle
    }

    /**
     * Reads the exit value after [Process.onExit] completion. `exitValue()` throws
     * [IllegalThreadStateException] if the process is not yet terminated; because the
     * future is done the race is theoretical, but guard it with a narrow catch (not a
     * broad one) so no cancellation is ever swallowed.
     */
    private fun safeExitValue(): Int = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        -1
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
 * [Throwable.addSuppressed] that is safe against self-suppression: adding the
 * primary itself (or an already-suppressed diagnostic) throws
 * [IllegalArgumentException] on the JVM, which would replace the very exception
 * the suppression was meant to preserve. Only adds when distinct and not already
 * present.
 */
internal fun Throwable.addSuppressedDistinct(error: Throwable) {
    if (error !== this && suppressedExceptions.none { it === error }) {
        addSuppressed(error)
    }
}

/**
 * Central surfacing of cleanup diagnostics.
 *
 * - No failures and no survivors → nothing to do.
 * - [primary] present → every diagnostic is attached exactly once via [addSuppressedDistinct];
 *   the primary (cancellation or domain exception) is preserved.
 * - No primary → throws [ProcessCleanupException] with all diagnostics suppressed, so
 *   cleanup problems are never silently ignored on an otherwise successful execution.
 */
internal fun surfaceProcessCleanup(primary: Throwable?, cleanup: ProcessCleanupResult) {
    if (cleanup.failures.isEmpty() && cleanup.survivors.isEmpty()) {
        return
    }
    val diagnostics = buildList {
        cleanup.failures.forEach { (_, error) -> add(error) }
        if (cleanup.survivors.isNotEmpty()) {
            add(ProcessTreeSurvivorException(cleanup.survivors))
        }
    }
    if (primary != null) {
        diagnostics.forEach { primary.addSuppressedDistinct(it) }
    } else {
        val cleanupException = ProcessCleanupException()
        diagnostics.forEach { cleanupException.addSuppressedDistinct(it) }
        throw cleanupException
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
