package dev.tramai.orchestration

import java.util.concurrent.TimeUnit
import kotlin.streams.toList

internal const val processTerminationGracePeriodMillis = 1_000L
internal const val processTerminationKillWaitMillis = 1_000L
private const val processTerminationPollIntervalMillis = 25L

internal suspend fun terminateProcessTree(
    process: Process,
    gracePeriodMillis: Long = processTerminationGracePeriodMillis,
    forceKillWaitMillis: Long = processTerminationKillWaitMillis,
) {
    val handles = process.processTreeHandles()
    if (handles.isEmpty()) {
        process.waitForUninterruptibly()
        return
    }

    handles.forEach { handle ->
        if (handle.isAlive) {
            handle.destroy()
        }
    }
    waitForHandlesToExitUninterruptibly(handles, gracePeriodMillis)

    handles.forEach { handle ->
        if (handle.isAlive) {
            handle.destroyForcibly()
        }
    }
    waitForHandlesToExitUninterruptibly(handles, forceKillWaitMillis)
    process.waitForUninterruptibly()
}

internal fun Process.processTreeHandles(): List<ProcessHandle> {
    val handle = toHandle()
    val descendants = handle.descendants().use { stream -> stream.toList() }
    return descendants + handle
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

internal fun Process.waitForUninterruptibly() {
    var interrupted = false
    while (true) {
        try {
            waitFor()
            break
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) {
        Thread.currentThread().interrupt()
    }
}
