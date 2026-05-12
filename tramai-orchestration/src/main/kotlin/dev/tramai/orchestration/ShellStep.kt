package dev.tramai.orchestration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

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

data class ShellCommandDefinition(
    val hasWorkdir: Boolean = false,
    val envKeys: Set<String> = emptySet(),
) {
    init {
        require(envKeys.none { it.isBlank() }) { "ShellCommandDefinition.envKeys must not contain blank values" }
    }
}

/**
 * Shell step output decoded with the configured charset. UTF-8 is used by default.
 */
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
    val allowedCommands: Set<String> = emptySet(),
    val deniedCommands: Set<String> = emptySet(),
    val charset: Charset = Charsets.UTF_8,
) {
    init {
        require(timeoutSeconds > 0) { "ShellStepConfig.timeoutSeconds must be greater than zero" }
        require(maxOutputBytes >= 0) { "ShellStepConfig.maxOutputBytes must be zero or greater" }
        require(maxOutputBytes <= Int.MAX_VALUE.toLong()) {
            "ShellStepConfig.maxOutputBytes must be less than or equal to ${Int.MAX_VALUE}"
        }
        require(allowedCommands.none { it.isBlank() }) {
            "ShellStepConfig.allowedCommands must not contain blank values"
        }
        require(deniedCommands.none { it.isBlank() }) {
            "ShellStepConfig.deniedCommands must not contain blank values"
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
    val definition: ShellCommandDefinition = ShellCommandDefinition(),
    val commandBuilder: suspend (S, WorkflowContext) -> ShellCommand,
    val merge: suspend (S, ShellResult, WorkflowContext) -> S,
    val config: ShellStepConfig = ShellStepConfig(),
) : InternalWorkflowStep<S> {
    internal fun validateStaticCommandPolicy(workflowName: String) {
        // Shell command policy cannot be validated at workflow build time.
        // The executable comes from the runtime command lambda and
        // ShellCommandDefinition intentionally does not carry a canonical command string.
    }

    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
    ): S {
        val shellCommand = try {
            commandBuilder(state, context)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw wrapShellError(error)
        }

        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.shell.started",
            attributes = mapOf(
                "step_name" to name,
            ),
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
            error.rethrowIfCancellation()
            throw wrapShellError(error, shellCommand)
        }

        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.shell.completed",
            attributes = mapOf(
                "step_name" to name,
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
            error.rethrowIfCancellation()
            throw wrapShellError(error, shellCommand)
        }
    }

    private suspend fun executeCommand(
        shellCommand: ShellCommand,
        workflowName: String,
        context: WorkflowContext,
        observer: WorkflowObserver,
    ): ExecutedShellResult {
        validateShellCommandDefinition(shellCommand)
        validateCommandPolicy(shellCommand)
        val process = ProcessBuilder(shellCommand.command)
            .apply {
                shellCommand.workdir?.let { directory(File(it)) }
                environment().putAll(shellCommand.env)
            }
            .run {
                withContext(Dispatchers.IO) { start() }
            }
        process.outputStream.close()

        try {
            return coroutineScope {
                val stdoutDeferred = async(Dispatchers.IO) { process.inputStream.captureStream(config.maxOutputBytes) }
                val stderrDeferred = async(Dispatchers.IO) { process.errorStream.captureStream(config.maxOutputBytes) }

                try {
                    withTimeout(config.timeoutSeconds.seconds) {
                        runInterruptible(Dispatchers.IO) {
                            process.waitFor()
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    observer.onWorkflowEvent(
                        workflowName = workflowName,
                        name = "tramai.workflow.shell.timeout",
                        attributes = mapOf(
                            "step_name" to name,
                        ),
                        context = context,
                    )
                    withContext(NonCancellable + Dispatchers.IO) {
                        terminateProcessTree(process)
                    }
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
                    charset = config.charset,
                )
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (process.toHandle().isAlive) {
                    terminateProcessTree(process)
                } else {
                    process.waitForUninterruptibly()
                }
            }
        }
    }

    private fun validateShellCommandDefinition(shellCommand: ShellCommand) {
        require((shellCommand.workdir != null) == definition.hasWorkdir) {
            "Workflow shell step '$name' canonical workdir metadata does not match the runtime command"
        }
        require(shellCommand.env.keys == definition.envKeys) {
            "Workflow shell step '$name' canonical env metadata does not match the runtime command"
        }
    }

    private fun validateCommandPolicy(shellCommand: ShellCommand) {
        val commandIdentifiers = shellCommand.commandIdentifiers()
        val allowedCommands = config.allowedCommands
        if (allowedCommands.isEmpty() || allowedCommands.none(commandIdentifiers::contains)) {
            throw WorkflowShellException(
                stepName = name,
                message = "command is not in allowlist",
            )
        }
        val deniedCommands = config.deniedCommands
        if (deniedCommands.any(commandIdentifiers::contains)) {
            throw WorkflowShellException(
                stepName = name,
                message = "command is blocked by the denylist",
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
                "step_name" to name,
                "stream" to stream,
                "actual_size" to capture.actualSizeBytes,
                "max_size" to config.maxOutputBytes,
            ),
            context = context,
        )
    }

    private fun wrapShellError(
        error: Throwable,
        shellCommand: ShellCommand? = null,
    ): WorkflowShellException = when (error) {
        is WorkflowShellException -> error
        else -> WorkflowShellException(
            stepName = name,
            message = "failed: ${sanitizeShellErrorMessage(error.message ?: error::class.java.simpleName, shellCommand)}",
            cause = error,
        )
    }

    private fun sanitizeShellErrorMessage(
        message: String,
        shellCommand: ShellCommand?,
    ): String {
        if (shellCommand == null) {
            return message
        }
        return shellCommand.commandIdentifiers()
            .sortedByDescending(String::length)
            .fold(message) { current, identifier ->
                current.replace(identifier, "[command]")
            }
    }
}

private data class ExecutedShellResult(
    val exitCode: Int,
    val stdout: StreamCapture,
    val stderr: StreamCapture,
    val charset: Charset,
) {
    val stdoutSizeBytes: Long
        get() = stdout.actualSizeBytes

    val stderrSizeBytes: Long
        get() = stderr.actualSizeBytes

    fun toWorkflowResult(): ShellResult = ShellResult(
        exitCode = exitCode,
        stdout = stdout.asText(charset),
        stderr = stderr.asText(charset),
        truncated = stdout.truncated || stderr.truncated,
    )
}

private data class StreamCapture(
    val bytes: ByteArray,
    val actualSizeBytes: Long,
    val truncated: Boolean,
) {
    fun asText(charset: Charset): String = bytes.toString(charset)
}

private fun InputStream.captureStream(
    maxOutputBytes: Long,
): StreamCapture = use { stream ->
    val maxCapturedBytes = maxOutputBytes.toInt()
    val capturedBytes = ByteArrayOutputStream(
        min(maxCapturedBytes.coerceAtLeast(shellMinimumBufferSize), shellStreamChunkSize),
    )
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
private const val shellMinimumBufferSize = 16

private fun ShellCommand.commandIdentifiers(): Set<String> {
    val executable = command.first()
    val fileName = File(executable).name
    return buildSet {
        add(executable)
        add(fileName)
    }
}

private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}
