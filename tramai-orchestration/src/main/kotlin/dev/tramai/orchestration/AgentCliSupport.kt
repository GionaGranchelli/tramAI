package dev.tramai.orchestration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
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

internal suspend fun executeAgentCli(
    workflowName: String,
    stepName: String,
    eventPrefix: String,
    agentType: String,
    processBuilder: ProcessBuilder,
    timeoutSeconds: Long,
    maxOutputBytes: Long,
    promptLength: Int,
    context: WorkflowContext,
    observer: WorkflowObserver,
): AgentCliExecution {
    val process = withContext(Dispatchers.IO) {
        processBuilder
            .redirectErrorStream(false)
            .start()
    }
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

    val startedAtNanos = System.nanoTime()
    try {
        return coroutineScope {
            val stdoutDeferred = async(Dispatchers.IO) { process.inputStream.captureAgentOutput(maxOutputBytes) }
            val stderrDeferred = async(Dispatchers.IO) { process.errorStream.captureAgentOutput(maxOutputBytes) }

            try {
                withTimeout(timeoutSeconds.seconds) {
                    runInterruptible(Dispatchers.IO) {
                        process.waitFor()
                    }
                }
            } catch (_: TimeoutCancellationException) {
                terminateAgentProcessTree(process)
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
    } finally {
        terminateAgentProcessTree(process)
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

private suspend fun terminateAgentProcessTree(process: Process) {
    withContext(NonCancellable + Dispatchers.IO) {
        terminateProcessTree(
            process = process,
            gracePeriodMillis = agentCliTerminationGracePeriodMillis,
            forceKillWaitMillis = agentCliTerminationKillWaitMillis,
        )
    }
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
    if (this is CancellationException) {
        throw this
    }
}

private const val agentCliStreamChunkSize = 8_192
private const val agentCliMinimumBufferSize = 16
private const val agentCliFailureMessageMaxChars = 4_096
private const val agentCliTerminationGracePeriodMillis = 1_000L
private const val agentCliTerminationKillWaitMillis = 1_000L
