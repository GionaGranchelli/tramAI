package dev.tramai.orchestration

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
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
    val executable: String,
    val hasWorkdir: Boolean = false,
    val envKeys: Set<String> = emptySet(),
) {
    init {
        require(executable.isNotBlank()) { "ShellCommandDefinition.executable must not be blank" }
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

class WorkflowShellException : RuntimeException {
    val stepName: String
    constructor(stepName: String, message: String, cause: Throwable? = null) :
        super("Workflow shell step '$stepName' $message", cause) { this.stepName = stepName }
    var failureCode: WorkflowStepFailureCode? = null
        internal set
    internal var safeFactoryTrusted: Boolean = false
    internal constructor(stepName: String, safeMessage: String, safe: Boolean) : super(safeMessage) {
        this.stepName = stepName
    }
}

internal data class ShellWorkflowStep<S>(
    override val name: String,
    val definition: ShellCommandDefinition,
    val commandBuilder: suspend (S, WorkflowContext) -> ShellCommand,
    val merge: suspend (S, ShellResult, WorkflowContext) -> S,
    val config: ShellStepConfig = ShellStepConfig(),
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : InternalWorkflowStep<S> {
    override suspend fun execute(
        request: WorkflowStepExecutionRequest<S>,
    ): WorkflowStepExecutionResult<S> = WorkflowStepExecutionResult.Completed(
        execute(
            workflowName = request.workflowName,
            state = request.state,
            context = request.context,
            observer = request.observer,
            failureDiagnosticObserver = request.services.failureDiagnosticObserver,
        ),
    )

    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver = NoOpWorkflowStepFailureDiagnosticObserver,
    ): S {
        val shellCommand = try {
            commandBuilder(state, context)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw failure(workflowName, error, WorkflowStepFailureCode.PREPARATION_FAILED, null, failureDiagnosticObserver)
        }

        observer.emitWorkflowEvent(
            workflowName = workflowName,
            event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SHELL_STARTED) {
                set(RuntimeAttributes.STEP_NAME, name)
            },
            context = context,
        )

        val result = try {
            executeCommand(
                shellCommand = shellCommand,
                workflowName = workflowName,
                context = context,
                observer = observer,
                failureDiagnosticObserver = failureDiagnosticObserver,
            )
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            val code = workflowFailureCode(error) ?: WorkflowStepFailureCode.EXECUTION_FAILED
            if (workflowFailureTrusted(error)) throw error
            throw failure(workflowName, error, code, null, failureDiagnosticObserver)
        }

        // Cancellation can terminate the child process: the process exit may
        // have been observed before cancellation reached a checkpoint. Re-check
        // BEFORE interpreting exit codes / stderr as ordinary shell outcomes, so
        // a parent cancellation never surfaces as NON_ZERO_EXIT etc.
        currentCoroutineContext().ensureActive()

        observer.emitWorkflowEvent(
            workflowName = workflowName,
            event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SHELL_COMPLETED) {
                set(RuntimeAttributes.STEP_NAME, name)
                set(RuntimeAttributes.EXIT_CODE, result.exitCode.toLong())
                set(RuntimeAttributes.STDOUT_BYTES, result.stdoutSizeBytes)
                set(RuntimeAttributes.STDERR_BYTES, result.stderrSizeBytes)
            },
            context = context,
        )

        if (config.failOnNonZeroExit && result.exitCode != 0) {
            throw failure(
                workflowName, IllegalStateException("non-zero exit"), WorkflowStepFailureCode.NON_ZERO_EXIT,
                result, failureDiagnosticObserver,
            )
        }
        if (config.failOnStderr && result.stderrSizeBytes > 0) {
            throw failure(
                workflowName, IllegalStateException("stderr was not empty"), WorkflowStepFailureCode.OUTPUT_REJECTED,
                result, failureDiagnosticObserver,
            )
        }

        return try {
            merge(state, result.toWorkflowResult(), context)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw failure(workflowName, error, WorkflowStepFailureCode.RESULT_HANDLING_FAILED, null, failureDiagnosticObserver)
        }
    }

    private suspend fun executeCommand(
        shellCommand: ShellCommand,
        workflowName: String,
        context: WorkflowContext,
        observer: WorkflowObserver,
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
    ): ExecutedShellResult {
        try {
            validateShellCommandDefinition(shellCommand)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw failure(workflowName, error, WorkflowStepFailureCode.VALIDATION_FAILED, null, failureDiagnosticObserver)
        }
        try {
            validateCommandPolicy(shellCommand)
        } catch (error: WorkflowShellException) {
            val policyType = if (error.message?.contains("allowlist") == true) "allowlist" else "deny-list"
            observer.emitWorkflowEvent(
                workflowName = workflowName,
                event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SECURITY_COMMAND_DENIED) {
                    set(RuntimeAttributes.STEP_NAME, name)
                    set(RuntimeAttributes.COMMAND_DIGEST, File(shellCommand.command.first()).name.sha256Digest())
                    set(RuntimeAttributes.POLICY_TYPE, policyType)
                    set(RuntimeAttributes.STEP_FAMILY, "shell")
                },
                context = context,
            )
            throw failure(workflowName, error, WorkflowStepFailureCode.POLICY_REJECTED, null, failureDiagnosticObserver)
        }
        val process = try {
            ProcessBuilder(shellCommand.command)
                .apply {
                    shellCommand.workdir?.let { directory(File(it)) }
                    environment().putAll(shellCommand.env)
                }
                .run { startOwnedProcess(ioDispatcher) { start() } }
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw failure(workflowName, error, WorkflowStepFailureCode.START_FAILED, null, failureDiagnosticObserver)
        }
        val lifecycle = CancellableProcessLifecycle(process)
        // Attach-then-active-check: cancellation requests process-tree termination
        // immediately (closing the pipes unblocks the readers below), so structured
        // concurrency never waits on blocking readers behind a live process. The
        // returned handle is disposed in the finally so a completed step does not leave
        // a handler (and its process reference) attached to a long-lived workflow job.
        val registration = lifecycle.attachTo(currentCoroutineContext().job)

        var primaryFailure: Throwable? = null
        var executedResult: ExecutedShellResult? = null
        try {
            process.outputStream.close()
            executedResult = coroutineScope {
                val stdoutDeferred = async(ioDispatcher) {
                    process.inputStream.captureStream(config.maxOutputBytes, lifecycle)
                }
                val stderrDeferred = async(ioDispatcher) {
                    process.errorStream.captureStream(config.maxOutputBytes, lifecycle)
                }

                // withTimeoutOrNull distinguishes its OWN timeout (null) from a
                // foreign TimeoutCancellationException (a parent withTimeout or
                // genuine cancellation), which propagates through untouched. A
                // broad catch(TimeoutCancellationException) below would convert a
                // PARENT timeout into an owned shell TIMEOUT + diagnostic.
                val timedOut = withTimeoutOrNull(config.timeoutSeconds.seconds) {
                    lifecycle.awaitExit()
                    // Cancellation can terminate the child process (e.g. exit
                    // 143 on SIGTERM): awaitExit may observe process death
                    // BEFORE the cancellation reaches this coroutine's next
                    // checkpoint. Re-check so a parent cancellation is never
                    // interpreted as an owned shell outcome below.
                    currentCoroutineContext().ensureActive()
                    false
                } ?: true
                if (timedOut) {
                    currentCoroutineContext().ensureActive()
                    // Request termination BEFORE the observer: closing the pipes lets
                    // the reader coroutines finish, and an observer failure can never
                    // skip the termination request. The bounded graceful→forced cleanup
                    // happens exactly once in the outer finally and attaches its
                    // diagnostics to this internal marker; the public safe failure is
                    // constructed only after cleanup below, so raw cleanup diagnostics
                    // never reach the public exception chain.
                    lifecycle.requestTermination()
                    observer.emitWorkflowEvent(
                        workflowName = workflowName,
                        event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SHELL_TIMEOUT) {
                            set(RuntimeAttributes.STEP_NAME, name)
                        },
                        context = context,
                    )
                    // Owned timeout becomes a non-cancellation internal marker: a
                    // genuine parent cancellation was already rethrown by ensureActive
                    // above and must stay a CancellationException all the way out.
                    throw ShellStepTimeoutException(
                        CancellationException("Shell step timed out after ${config.timeoutSeconds}s"),
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
        } catch (error: CancellationException) {
            primaryFailure = error
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            primaryFailure = error
        } finally {
            registration.dispose()
            // NonCancellable + IO nesting lives inside terminateAndAwait; calling it
            // directly here keeps the primary failure (and its diagnostics) intact.
            val cleanup = lifecycle.terminateAndAwait()
            try {
                surfaceProcessCleanup(primaryFailure, cleanup)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                if (primaryFailure == null) {
                    throw failure(
                        workflowName, error, WorkflowStepFailureCode.CLEANUP_FAILED, null, failureDiagnosticObserver,
                    )
                }
                throw error
            }
        }
        // The public safe failure is constructed only after process cleanup has
        // completed: cleanup diagnostics stay suppressed on the raw internal
        // primary (observer-only) and never reach the public exception chain.
        if (primaryFailure != null) {
            val code = if (primaryFailure is ShellStepTimeoutException) {
                WorkflowStepFailureCode.TIMEOUT
            } else {
                WorkflowStepFailureCode.EXECUTION_FAILED
            }
            throw failure(workflowName, primaryFailure!!, code, executedResult, failureDiagnosticObserver)
        }
        return executedResult!!
    }

    private fun validateShellCommandDefinition(shellCommand: ShellCommand) {
        require(shellCommand.command.first() == definition.executable) {
            "Workflow shell step '$name' canonical executable does not match the runtime command"
        }
        require((shellCommand.workdir != null) == definition.hasWorkdir) {
            "Workflow shell step '$name' canonical workdir metadata does not match the runtime command"
        }
        require(shellCommand.env.keys == definition.envKeys) {
            "Workflow shell step '$name' canonical env metadata does not match the runtime command"
        }
    }

    private fun validateCommandPolicy(shellCommand: ShellCommand) {
        val commandIdentifiers = shellCommand.commandIdentifiers()
        val fullCommand = shellCommand.command.first()
        val allowedCommands = config.allowedCommands
        if (allowedCommands.isEmpty() ||
            (allowedCommands.none(commandIdentifiers::contains) && fullCommand !in allowedCommands)
        ) {
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
        observer.emitWorkflowEvent(
            workflowName = workflowName,
            event = RuntimeEvent.of(RuntimeEvents.WORKFLOW_SHELL_TRUNCATED) {
                set(RuntimeAttributes.STEP_NAME, name)
                set(RuntimeAttributes.STREAM, stream)
                set(RuntimeAttributes.ACTUAL_SIZE, capture.actualSizeBytes)
                set(RuntimeAttributes.MAX_SIZE, config.maxOutputBytes)
            },
            context = context,
        )
    }

    private suspend fun failure(
        workflowName: String,
        error: Throwable,
        code: WorkflowStepFailureCode,
        result: ExecutedShellResult?,
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
    ): RuntimeException {
        val detail = result?.stderr?.let { capture ->
            val limit = 8193
            val prefix = if (capture.bytes.size > limit) capture.bytes.copyOfRange(0, limit) else capture.bytes
            boundedWorkflowDetailPreview(String(prefix, config.charset))
        } ?: boundedWorkflowDetailPreview(error.message ?: error::class.java.name)
        val metadata = result?.let { mapOf("exitCode" to it.exitCode.toLong()) } ?: emptyMap()
        deliverWorkflowStepFailure(
            failureDiagnosticObserver,
            WorkflowStepFailureDiagnosticEvent(
                workflowName, name, WorkflowStepKind.SHELL, code, 1, false, error,
                detail.text, detail.truncated, metadata,
            ),
        )
        return safeWorkflowStepFailure(
            WorkflowStepKind.SHELL, code, fixedWorkflowStepMessage(WorkflowStepKind.SHELL, code), name, 1,
        )
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
    lifecycle: CancellableProcessLifecycle,
): StreamCapture = use { stream ->
    val maxCapturedBytes = maxOutputBytes.toInt()
    val capturedBytes = ByteArrayOutputStream(
        min(maxCapturedBytes.coerceAtLeast(shellMinimumBufferSize), shellStreamChunkSize),
    )
    val buffer = ByteArray(shellStreamChunkSize)
    var actualSizeBytes = 0L
    var truncated = false
    while (true) {
        val bytesRead = lifecycle.readStreamChunk(stream, buffer) ?: break
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

/** Internal marker: the Shell step's own timeout (never a parent cancellation). */
private class ShellStepTimeoutException(
    cause: Throwable?,
) : RuntimeException("shell step timed out", cause)

private fun String.sha256Digest(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
