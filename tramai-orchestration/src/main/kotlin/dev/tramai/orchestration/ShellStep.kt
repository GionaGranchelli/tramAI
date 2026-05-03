package dev.tramai.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

data class ShellCommand(
    val command: List<String>,
    val workdir: String? = null,
    val env: Map<String, String> = emptyMap(),
) {
    init {
        require(command.isNotEmpty()) { "ShellCommand.command must not be empty" }
        require(command.none { it.isBlank() }) { "ShellCommand.command must not contain blank arguments" }
        require(workdir == null || workdir.isNotBlank()) { "ShellCommand.workdir must not be blank" }
    }
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean = false,
)

data class ShellStepConfig(
    val timeoutSeconds: Long = 60,
    val maxOutputBytes: Long = 1_048_576,
    val failOnNonZeroExit: Boolean = true,
    val failOnStderr: Boolean = false,
) {
    init {
        require(timeoutSeconds > 0) { "ShellStepConfig.timeoutSeconds must be greater than zero" }
        require(maxOutputBytes >= 0) { "ShellStepConfig.maxOutputBytes must be zero or greater" }
        require(maxOutputBytes <= Int.MAX_VALUE.toLong()) {
            "ShellStepConfig.maxOutputBytes must be less than or equal to ${Int.MAX_VALUE}"
        }
    }
}

class WorkflowShellException(
    val stepName: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("Workflow shell step '$stepName' $message", cause)

internal data class ShellWorkflowStep<S>(
    override val name: String,
    val commandBuilder: suspend (S, WorkflowContext) -> ShellCommand,
    val merge: suspend (S, ShellResult, WorkflowContext) -> S,
    val config: ShellStepConfig = ShellStepConfig(),
) : InternalWorkflowStep<S> {
    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
    ): S {
        val shellCommand = try {
            commandBuilder(state, context)
        } catch (error: Throwable) {
            throw wrapShellError(error)
        }

        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.shell.started",
            context = context,
        )

        val result = try {
            executeCommand(
                shellCommand = shellCommand,
                workflowName = workflowName,
                context = context,
                observer = observer,
            )
        } catch (error: Throwable) {
            throw wrapShellError(error)
        }

        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.shell.completed",
            attributes = mapOf(
                "exit_code" to result.exitCode,
                "stdout_bytes" to result.stdoutSizeBytes,
                "stderr_bytes" to result.stderrSizeBytes,
            ),
            context = context,
        )

        if (config.failOnNonZeroExit && result.exitCode != 0) {
            throw WorkflowShellException(
                stepName = name,
                message = "failed with exit code ${result.exitCode}",
            )
        }
        if (config.failOnStderr && result.stderrSizeBytes > 0) {
            throw WorkflowShellException(
                stepName = name,
                message = "failed because stderr was not empty",
            )
        }

        return try {
            merge(state, result.toWorkflowResult(), context)
        } catch (error: Throwable) {
            throw wrapShellError(error)
        }
    }

    private suspend fun executeCommand(
        shellCommand: ShellCommand,
        workflowName: String,
        context: WorkflowContext,
        observer: WorkflowObserver,
    ): ExecutedShellResult = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(shellCommand.command)
            .apply {
                shellCommand.workdir?.let { directory(File(it)) }
                environment().putAll(shellCommand.env)
            }
            .start()
        process.outputStream.close()

        coroutineScope {
            val stdoutDeferred = async { process.inputStream.captureStream(config.maxOutputBytes) }
            val stderrDeferred = async { process.errorStream.captureStream(config.maxOutputBytes) }

            val finished = process.waitFor(config.timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                observer.onWorkflowEvent(
                    workflowName = workflowName,
                    name = "tramai.workflow.shell.timeout",
                    context = context,
                )
                process.destroyForcibly()
                process.waitFor()
                stdoutDeferred.await()
                stderrDeferred.await()
                throw WorkflowShellException(
                    stepName = name,
                    message = "timed out after ${config.timeoutSeconds}s",
                )
            }

            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()

            emitTruncationEventIfNeeded(
                workflowName = workflowName,
                context = context,
                observer = observer,
                stream = "stdout",
                capture = stdout,
            )
            emitTruncationEventIfNeeded(
                workflowName = workflowName,
                context = context,
                observer = observer,
                stream = "stderr",
                capture = stderr,
            )

            ExecutedShellResult(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr,
            )
        }
    }

    private fun emitTruncationEventIfNeeded(
        workflowName: String,
        context: WorkflowContext,
        observer: WorkflowObserver,
        stream: String,
        capture: StreamCapture,
    ) {
        if (!capture.truncated) {
            return
        }
        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.shell.truncated",
            attributes = mapOf(
                "stream" to stream,
                "actual_size" to capture.actualSizeBytes,
                "max_size" to config.maxOutputBytes,
            ),
            context = context,
        )
    }

    private fun wrapShellError(error: Throwable): WorkflowShellException = when (error) {
        is WorkflowShellException -> error
        else -> WorkflowShellException(
            stepName = name,
            message = "failed: ${error.message ?: error::class.java.simpleName}",
            cause = error,
        )
    }
}

private data class ExecutedShellResult(
    val exitCode: Int,
    val stdout: StreamCapture,
    val stderr: StreamCapture,
) {
    val stdoutSizeBytes: Long
        get() = stdout.actualSizeBytes

    val stderrSizeBytes: Long
        get() = stderr.actualSizeBytes

    fun toWorkflowResult(): ShellResult = ShellResult(
        exitCode = exitCode,
        stdout = stdout.asText(),
        stderr = stderr.asText(),
        truncated = stdout.truncated || stderr.truncated,
    )
}

private data class StreamCapture(
    val bytes: ByteArray,
    val actualSizeBytes: Long,
    val truncated: Boolean,
) {
    fun asText(): String = bytes.toString(Charsets.UTF_8)
}

private fun InputStream.captureStream(
    maxOutputBytes: Long,
): StreamCapture = use { stream ->
    val maxCapturedBytes = maxOutputBytes.toInt()
    val capturedBytes = ByteArrayOutputStream(min(maxCapturedBytes, shellStreamChunkSize))
    val buffer = ByteArray(shellStreamChunkSize)
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
    StreamCapture(
        bytes = capturedBytes.toByteArray(),
        actualSizeBytes = actualSizeBytes,
        truncated = truncated,
    )
}

private const val shellStreamChunkSize = 8_192
