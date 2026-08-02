package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

internal data class AgentCliExecution(
    val exitCode: Int,
    val output: String,
    val stderr: String,
    val truncated: Boolean,
    val actualSizeBytes: Long,
    val durationMillis: Long,
)

internal class AgentCliTimeoutException(
    val timeoutSeconds: Long,
) : RuntimeException("timed out after ${timeoutSeconds}s")

internal data class AgentCliRequest(
    val workflowName: String,
    val stepName: String,
    val eventPrefix: String,
    val agentType: String,
    val processBuilder: ProcessBuilder,
    val timeoutSeconds: Long,
    val maxOutputBytes: Long,
    val promptLength: Int,
    val context: WorkflowContext,
    val observer: WorkflowObserver,
)

internal suspend fun executeAgentCli(
    request: AgentCliRequest,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): AgentCliExecution {
    val workflowName = request.workflowName
    val stepName = request.stepName
    val eventPrefix = request.eventPrefix
    val agentType = request.agentType
    val processBuilder = request.processBuilder
    val timeoutSeconds = request.timeoutSeconds
    val maxOutputBytes = request.maxOutputBytes
    val promptLength = request.promptLength
    val context = request.context
    val observer = request.observer
    val process = withContext(ioDispatcher) {
        processBuilder
            .redirectErrorStream(false)
            .start()
    }
    val lifecycle = CancellableProcessLifecycle(process)
    // Attach-then-active-check: parent cancellation requests process-tree termination
    // immediately, closing the pipes so the blocking readers below cannot delay
    // cancellation completion. The handle is disposed in the finally so a completed
    // step does not leave a handler attached to a long-lived workflow job.
    val registration = lifecycle.attachTo(currentCoroutineContext().job)

    val startedAtNanos = System.nanoTime()
    var primaryFailure: Throwable? = null
    try {
        process.outputStream.close()
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "$eventPrefix.started",
            attributes = mapOf(
                "step_name" to stepName,
                "agent_type" to agentType,
                "prompt_length" to promptLength,
            ),
            context = context,
        )
        return coroutineScope {
            val stdoutDeferred = async(ioDispatcher) { process.inputStream.captureAgentOutput(maxOutputBytes) }
            val stderrDeferred = async(ioDispatcher) { process.errorStream.captureAgentOutput(maxOutputBytes) }

            try {
                withTimeout(timeoutSeconds.seconds) {
                    lifecycle.awaitExit()
                }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                // Only request termination here: closing the pipes lets the reader
                // coroutines finish. The bounded graceful→forced cleanup happens exactly
                // once in the outer finally and attaches its diagnostics to this exception.
                lifecycle.requestTermination()
                throw AgentCliTimeoutException(timeoutSeconds)
            }

            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()
            val renderedOutput = stdout.asTextWithFooter(maxOutputBytes)
            val renderedStderr = stderr.asTextWithFooter(maxOutputBytes)
            val durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

            observer.onWorkflowEvent(
                workflowName = workflowName,
                name = "$eventPrefix.completed",
                attributes = mapOf(
                    "step_name" to stepName,
                    "agent_type" to agentType,
                    "prompt_length" to promptLength,
                    "response_length" to renderedOutput.length,
                    "duration_ms" to durationMillis,
                    "exit_code" to process.exitValue(),
                ),
                context = context,
            )

            AgentCliExecution(
                exitCode = process.exitValue(),
                output = renderedOutput,
                stderr = renderedStderr,
                truncated = stdout.truncated,
                actualSizeBytes = stdout.actualSizeBytes,
                durationMillis = durationMillis,
            )
        }
    } catch (error: CancellationException) {
        primaryFailure = error
        throw error
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        primaryFailure = error
        throw error
    } finally {
        registration.dispose()
        val cleanup = withContext(NonCancellable + ioDispatcher) {
            lifecycle.terminateAndAwait()
        }
        surfaceProcessCleanup(primaryFailure, cleanup)
    }
}

private data class AgentStreamCapture(
    val bytes: ByteArray,
    val actualSizeBytes: Long,
    val truncated: Boolean,
) {
    fun asTextWithFooter(
        maxOutputBytes: Long,
        charset: Charset = Charsets.UTF_8,
    ): String {
        val text = bytes.toString(charset)
        if (!truncated) {
            return text
        }
        val footer = buildString {
            appendLine()
            append("[truncated output: captured ")
            append(maxOutputBytes)
            append(" bytes of ")
            append(actualSizeBytes)
            append(" total bytes]")
        }
        return text + footer
    }
}

private fun InputStream.captureAgentOutput(
    maxOutputBytes: Long,
): AgentStreamCapture = use { stream ->
    val maxCapturedBytes = maxOutputBytes.toInt()
    val capturedBytes = ByteArrayOutputStream(
        min(maxCapturedBytes.coerceAtLeast(agentCliMinimumBufferSize), agentCliStreamChunkSize),
    )
    val buffer = ByteArray(agentCliStreamChunkSize)
    var actualSizeBytes = 0L
    var truncated = false
    while (true) {
        val bytesRead = stream.read(buffer)
        if (bytesRead < 0) {
            break
        }
        actualSizeBytes += bytesRead
        val remaining = maxCapturedBytes - capturedBytes.size()
        if (remaining > 0) {
            val bytesToWrite = min(bytesRead, remaining)
            capturedBytes.write(buffer, 0, bytesToWrite)
            if (bytesToWrite < bytesRead) {
                truncated = true
            }
        } else {
            truncated = true
        }
    }
    AgentStreamCapture(
        bytes = capturedBytes.toByteArray(),
        actualSizeBytes = actualSizeBytes,
        truncated = truncated,
    )
}

internal fun AgentCliExecution.describeNonZeroExit(): String {
    val stderrSummary = stderr.trim()
    if (stderrSummary.isEmpty()) {
        return "failed with exit code $exitCode"
    }
    return buildString {
        append("failed with exit code ")
        append(exitCode)
        append("; stderr: ")
        if (stderrSummary.length <= agentCliFailureMessageMaxChars) {
            append(stderrSummary)
        } else {
            append(stderrSummary.take(agentCliFailureMessageMaxChars))
            append("... [truncated stderr]")
        }
    }
}

internal fun Throwable.rethrowAgentCancellation() {
    rethrowIfCancellation()
}

private const val agentCliStreamChunkSize = 8_192
private const val agentCliMinimumBufferSize = 16
private const val agentCliFailureMessageMaxChars = 4_096
