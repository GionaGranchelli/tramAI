package dev.tramai.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * 8.3c — lifecycle authority for scheduler polling.
 *
 * Exactly one owner per [ScheduledWorkflowTimer]: it owns the polling child
 * job's lifecycle, distinguishes the timer-owned default root scope from a
 * borrowed caller scope, and makes start/stop/close transitions race-safe.
 *
 * State machine: STOPPED → RUNNING → STOPPING → STOPPED; any state → CLOSED
 * (terminal). A rejected start creates zero child jobs. stop() claims STOPPING
 * before joining, so a concurrent start can never overlap the old generation.
 * close() is terminal and idempotent: it cancels the active child and, only
 * when the root scope was default-created (owned), cancels that root.
 *
 * Generation-safe completion: a child completing (external cancellation of the
 * returned Job, or natural termination) only clears ownership when it is the
 * CURRENT child in RUNNING state — a stale generation's completion can never
 * clear a newer child's ownership or rewrite CLOSED.
 */
internal enum class SchedulerLifecycleState {
    STOPPED,
    RUNNING,
    STOPPING,
    CLOSED,
}

/**
 * Marker for the timer-created default root scope. The public constructor
 * parameter stays [CoroutineScope] (ABI unchanged); the default argument value
 * is this internal type so ownership is classified via `scope is OwnedSchedulerScope`.
 */
internal class OwnedSchedulerScope(
    override val coroutineContext: CoroutineContext,
) : CoroutineScope

internal class SchedulerLoopOwner(
    private val parentScope: CoroutineScope,
    private val ownsParentScope: Boolean,
) {
    private val monitor = Any()
    private var state = SchedulerLifecycleState.STOPPED
    private var currentChild: Job? = null
    private var generation = 0L

    /** Claims the next polling generation. Rejected starts create ZERO children. */
    fun start(loopBlock: suspend () -> Unit): Job {
        val child: Job
        val childGeneration: Long
        synchronized(monitor) {
            when (state) {
                SchedulerLifecycleState.STOPPED -> {
                    child = parentScope.launch(start = CoroutineStart.LAZY) { loopBlock() }
                    state = SchedulerLifecycleState.RUNNING
                    currentChild = child
                    generation += 1
                    childGeneration = generation
                }
                SchedulerLifecycleState.RUNNING ->
                    throw IllegalStateException("ScheduledWorkflowTimer is already started")
                SchedulerLifecycleState.STOPPING ->
                    throw IllegalStateException("ScheduledWorkflowTimer is stopping; wait for the previous polling loop to finish")
                SchedulerLifecycleState.CLOSED ->
                    throw IllegalStateException("ScheduledWorkflowTimer is closed")
            }
        }
        child.invokeOnCompletion { onChildCompleted(childGeneration) }
        child.start()
        return child
    }

    /** Stops the current generation: claim, cancel the exact child, join fully, then restartable. */
    suspend fun stop() {
        val child = synchronized(monitor) {
            when (state) {
                SchedulerLifecycleState.RUNNING -> {
                    state = SchedulerLifecycleState.STOPPING
                    currentChild
                }
                // Concurrent stop: join the same terminating child so every
                // caller observes full termination before returning.
                SchedulerLifecycleState.STOPPING -> currentChild
                else -> null
            }
        } ?: return
        // cancel() is non-suspending and always executes even when this caller
        // is cancelled; only the join may be interrupted. If that happens, the
        // child is already cancelled and onChildCompleted (STOPPING path)
        // restores STOPPED when it finishes — the owner is never wedged.
        child.cancel()
        try {
            child.join()
        } finally {
            synchronized(monitor) {
                if (state == SchedulerLifecycleState.STOPPING && child.isCompleted) {
                    state = SchedulerLifecycleState.STOPPED
                    currentChild = null
                }
            }
        }
    }

    /** Terminal and idempotent. Never cancels a borrowed caller scope. */
    fun close() {
        val childToCancel: Job?
        val rootToCancel: CoroutineScope?
        synchronized(monitor) {
            if (state == SchedulerLifecycleState.CLOSED) return
            state = SchedulerLifecycleState.CLOSED
            childToCancel = currentChild.also { currentChild = null }
            rootToCancel = if (ownsParentScope) parentScope else null
        }
        childToCancel?.cancel()
        rootToCancel?.cancel()
    }

    private fun onChildCompleted(childGeneration: Long) {
        synchronized(monitor) {
            when {
                state == SchedulerLifecycleState.RUNNING && childGeneration == generation -> {
                    // The current polling child completed without stop()/close() —
                    // e.g. externally cancelled returned Job. Release ownership so
                    // the timer is restartable.
                    currentChild = null
                    state = SchedulerLifecycleState.STOPPED
                }
                state == SchedulerLifecycleState.STOPPING && childGeneration == generation -> {
                    // A stop() caller was cancelled mid-join: the child was already
                    // cancelled, and its completion finishes the stop transition.
                    // Stale generations never clear a newer child, and CLOSED is
                    // never rewritten.
                    currentChild = null
                    state = SchedulerLifecycleState.STOPPED
                }
            }
        }
    }
}
