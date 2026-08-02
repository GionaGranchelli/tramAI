package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Source
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.time.Duration.Companion.seconds

data class McpToolCall(
    val serverCommand: List<String>,
    val serverEnv: Map<String, String> = emptyMap(),
    val toolName: String,
    val arguments: Map<String, String> = emptyMap(),
) {
    init {
        require(serverCommand.isNotEmpty()) { "McpToolCall.serverCommand must not be empty" }
        require(serverCommand.none { it.isBlank() }) { "McpToolCall.serverCommand must not contain blank arguments" }
        require(toolName.isNotBlank()) { "McpToolCall.toolName must not be blank" }
    }
}

/**
 * Canonical metadata mirroring what a workflow declares at build time.
 * Freezes command, env (keys and values), tool name, and argument keys
 * so state-influenced workflows cannot change what executes at runtime.
 */
data class McpToolCallDefinition(
    val serverCommand: List<String>,
    val serverEnv: Map<String, String> = emptyMap(),
    val toolName: String,
    val argumentKeys: Set<String> = emptySet(),
) {
    init {
        require(serverCommand.isNotEmpty()) { "McpToolCallDefinition.serverCommand must not be empty" }
        require(serverCommand.none { it.isBlank() }) { "McpToolCallDefinition.serverCommand must not contain blank arguments" }
        require(toolName.isNotBlank()) { "McpToolCallDefinition.toolName must not be blank" }
        require(serverEnv.none { it.key.isBlank() }) { "McpToolCallDefinition.serverEnv must not contain blank keys" }
        require(argumentKeys.none { it.isBlank() }) { "McpToolCallDefinition.argumentKeys must not contain blank values" }
    }
}

data class McpToolResult(
    val content: String?,
    val structuredContent: String?,
    val isError: Boolean = false,
)

data class McpStepConfig(
    val timeoutSeconds: Long = 30,
    val maxOutputBytes: Long = 1_048_576,
    val reconnect: Boolean = true,
    val toolAllowlist: Set<String>? = null,
    val allowedCommands: Set<String> = emptySet(),
    val deniedCommands: Set<String> = emptySet(),
    internal val enforceCommandAllowlist: Boolean = true,
) {
    init {
        require(timeoutSeconds > 0) { "McpStepConfig.timeoutSeconds must be greater than zero" }
        require(maxOutputBytes >= 0) { "McpStepConfig.maxOutputBytes must be zero or greater" }
        require(maxOutputBytes <= Int.MAX_VALUE.toLong()) {
            "McpStepConfig.maxOutputBytes must be less than or equal to ${Int.MAX_VALUE}"
        }
        require(toolAllowlist?.none { it.isBlank() } != false) {
            "McpStepConfig.toolAllowlist must not contain blank values"
        }
        require(allowedCommands.none { it.isBlank() }) {
            "McpStepConfig.allowedCommands must not contain blank values"
        }
        require(deniedCommands.none { it.isBlank() }) {
            "McpStepConfig.deniedCommands must not contain blank values"
        }
    }

    companion object {
        /**
         * Disables command allowlist enforcement for compatibility with older workflows.
         * The denylist still applies when configured.
         */
        fun unrestricted(): McpStepConfig = McpStepConfig(enforceCommandAllowlist = false)
    }
}

class WorkflowMcpException(
    val stepName: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("Workflow MCP step '$stepName' $message", cause)

/**
 * Pluggable transport provider for MCP steps.
 * Default implementation uses subprocess-based stdio transport.
 * Tests can inject piped-stream transports without spawning subprocesses.
 */
fun interface McpTransportProvider {
    suspend fun connect(toolCall: McpToolCall): McpTransportConnection
}

data class McpTransportConnection(
    val input: Source,
    val output: Sink,
    val cleanup: (suspend () -> Unit)? = null,
)

/**
 * Default transport provider that starts the MCP server as a subprocess.
 * Concurrently drains stderr to prevent pipe-buffer deadlock.
 */
internal class SubprocessMcpTransportProvider : McpTransportProvider {
    override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
        val process = try {
            ProcessBuilder(toolCall.serverCommand)
                .apply {
                    environment().putAll(toolCall.serverEnv)
                    redirectErrorStream(false)
                }
                .start()
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw WorkflowMcpException(
                stepName = "<transport>",
                message = "failed to start MCP server: ${error.message ?: error::class.java.simpleName}",
                cause = error,
            )
        }

        val lifecycle = CancellableProcessLifecycle(process)
        // Attach-then-active-check: parent cancellation (or the withTimeout firing)
        // requests server-tree termination immediately — closing the process pipes
        // unblocks the stdio client, so cleanup can never be delayed indefinitely
        // behind client.close(). The handle is disposed when the connection cleans up.
        val registration = lifecycle.attachTo(currentCoroutineContext().job)

        // Fire-and-forget stderr drain so OS pipe buffer never blocks the subprocess.
        // Must not use coroutineScope — it would wait for the drain to complete (never, until EOF).
        val drainScope = CoroutineScope(Dispatchers.IO)
        drainScope.launch {
            drainStream(process.errorStream)
        }

        return McpTransportConnection(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            cleanup = {
                drainScope.cancel()
                registration.dispose()
                val result = lifecycle.terminateAndAwait()
                // Surface failures/survivors exactly once; no primary failure here (the
                // caller decides suppression when a client-operation failure exists).
                surfaceProcessCleanup(primary = null, cleanup = result)
            },
        )
    }

    private fun drainStream(inputStream: java.io.InputStream) {
        try {
            val buffer = ByteArray(8192)
            while (inputStream.read(buffer) >= 0) {
                // discard stderr bytes; only needed to prevent pipe blockage
            }
        } catch (_: Exception) {
            // stream closed or interrupted — expected during shutdown
        }
    }
}

internal data class McpWorkflowStep<S>(
    override val name: String,
    val definition: McpToolCallDefinition,
    val toolCallBuilder: suspend (S, WorkflowContext) -> McpToolCall,
    val merge: suspend (S, McpToolResult, WorkflowContext) -> S,
    val config: McpStepConfig = McpStepConfig(),
    val transportProvider: McpTransportProvider = SubprocessMcpTransportProvider(),
) : InternalWorkflowStep<S> {
    internal fun validateStaticCommandPolicy(workflowName: String) {
        val commandIdentifiers = definition.serverCommand.commandIdentifiers()
        val allowedCommands = config.allowedCommands
        require(
            !config.enforceCommandAllowlist ||
                (allowedCommands.isNotEmpty() && allowedCommands.any(commandIdentifiers::contains)),
        ) {
            "Workflow '$workflowName' MCP step '$name' command is not in allowlist"
        }
    }

    suspend fun execute(
        workflowName: String,
        state: S,
        context: WorkflowContext,
        observer: WorkflowObserver,
    ): S {
        val toolCall = try {
            toolCallBuilder(state, context)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw wrapMcpError(error)
        }

        try {
            validateDefinition(toolCall)
            validateToolAllowlist(toolCall.toolName)
            try {
                validateCommandPolicy(toolCall)
            } catch (error: WorkflowMcpException) {
                val policyType = if (error.message?.contains("allowlist") == true) "allowlist" else "deny-list"
                observer.onWorkflowEvent(
                    workflowName = workflowName,
                    name = SecurityEvents.COMMAND_DENIED,
                    attributes = mapOf(
                        "step_name" to name,
                        "command" to File(toolCall.serverCommand.first()).name,
                        "policy_type" to policyType,
                        "step_family" to "mcp",
                    ),
                    context = context,
                )
                throw error
            }
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw wrapMcpError(error, toolCall)
        }

        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.mcp.started",
            attributes = mapOf(
                "step_name" to name,
                "tool_name" to toolCall.toolName,
            ),
            context = context,
        )

        val result = runMcpCall(
            toolCall = toolCall,
            workflowName = workflowName,
            context = context,
            observer = observer,
        )

        observer.onWorkflowEvent(
            workflowName = workflowName,
            name = "tramai.workflow.mcp.completed",
            attributes = mapOf(
                "step_name" to name,
                "tool_name" to toolCall.toolName,
                "is_error" to result.isError,
                "content_size_bytes" to (result.content?.length ?: 0),
            ),
            context = context,
        )

        return try {
            merge(state, result, context)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw wrapMcpError(error, toolCall)
        }
    }

    private suspend fun runMcpCall(
        toolCall: McpToolCall,
        workflowName: String,
        context: WorkflowContext,
        observer: WorkflowObserver,
    ): McpToolResult {
        var lastError: Throwable? = null
        val maxAttempts = if (config.reconnect) 2 else 1

        for (attempt in 1..maxAttempts) {
            try {
                return executeMcpCall(toolCall)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                lastError = error
                if (attempt < maxAttempts && error.isTransientForReconnect()) {
                    observer.onWorkflowEvent(
                        workflowName = workflowName,
                        name = "tramai.workflow.mcp.reconnecting",
                        attributes = mapOf(
                            "step_name" to name,
                            "tool_name" to toolCall.toolName,
                            "attempt" to attempt,
                            "reason" to (error.message ?: error::class.java.simpleName),
                        ),
                        context = context,
                    )
                } else {
                    throw wrapMcpError(error, toolCall)
                }
            }
        }

        throw wrapMcpError(lastError!!, toolCall)
    }

    private suspend fun executeMcpCall(toolCall: McpToolCall): McpToolResult {
        return try {
            withTimeout(config.timeoutSeconds.seconds) {
                val connection = transportProvider.connect(toolCall)

                var primaryFailure: Throwable? = null
                val result: McpToolResult = try {
                    var client: Client? = null
                    try {
                        // Client construction is inside the tracked lifecycle: a setup
                        // failure here is reconnectable (no tool result was produced).
                        client = Client(
                            clientInfo = Implementation(
                                name = "tramai-mcp-step",
                                version = MCP_STEP_VERSION,
                            ),
                        )
                        client.connect(
                            StdioClientTransport(
                                input = connection.input,
                                output = connection.output,
                            ),
                        )

                        val argumentsJson: Map<String, JsonElement> = toolCall.arguments.mapValues { (_, value) ->
                            JsonPrimitive(value)
                        }

                        val callResult: CallToolResult = client.callTool(toolCall.toolName, argumentsJson)
                        toMcpToolResult(callResult)
                    } catch (error: Throwable) {
                        // Track the primary BEFORE rethrowing cancellation: the finally
                        // cleanup must see it so cleanup failures never replace it.
                        primaryFailure = error
                        if (error is CancellationException) throw error
                        throw error
                    } finally {
                        // client.close() must never replace a primary failure (especially a
                        // CancellationException): run it under NonCancellable and suppress any
                        // close failure onto the primary. If there is no primary failure and
                        // close itself fails, the tool may already have executed exactly once,
                        // so the failure is wrapped as non-reconnectable (never retry the tool).
                        try {
                            withContext(NonCancellable) { client?.close() }
                        } catch (closeError: Throwable) {
                            if (primaryFailure != null) {
                                // A primary failure (typically cancellation) wins: suppress
                                // the close failure onto it, never replace it.
                                primaryFailure!!.addSuppressedDistinct(closeError)
                            } else {
                                val cleanupException = McpPostCallCleanupException(closeError)
                                // Track it as primary so a subsequent transport cleanup
                                // failure is suppressed onto it instead of replacing it.
                                primaryFailure = cleanupException
                                if (closeError is CancellationException) throw closeError
                                throw cleanupException
                            }
                        }
                    }
                } finally {
                    // Exactly-once transport cleanup; suppression semantics live in
                    // cleanupConnection (primary failure wins over any cleanup throwable).
                    cleanupConnection(connection, primaryFailure)
                }
                result
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val mcpException = WorkflowMcpException(
                stepName = name,
                message = "timed out after ${config.timeoutSeconds}s",
            )
            error.suppressedExceptions.forEach { mcpException.addSuppressed(it) }
            throw mcpException
        }
    }

    /**
     * Invokes the transport cleanup exactly once per connection.
     *
     * - Runs under `NonCancellable` so a custom suspend cleanup always executes, even in
     *   an already-cancelled context.
     * - When a primary failure exists, every cleanup throwable — including a cleanup
     *   [CancellationException] — is suppressed onto the primary; the primary (typically
     *   cancellation) is preserved.
     * - When no primary failure exists, cancellation is preserved via
     *   [rethrowIfCancellation] and other cleanup errors are wrapped in
     *   [McpPostCallCleanupException] (never silently swallowed): the tool may
     *   already have executed, so the failure must never trigger a reconnect.
     */
    private suspend fun cleanupConnection(
        connection: McpTransportConnection,
        primary: Throwable?,
    ) {
        if (connection.cleanup == null) return
        val cleanupError: Throwable? = try {
            withContext(NonCancellable) { connection.cleanup.invoke() }
            null
        } catch (error: Throwable) {
            if (primary != null) {
                // A primary failure (typically cancellation) wins: suppress every cleanup
                // throwable — including a cleanup CancellationException — onto it.
                primary.addSuppressedDistinct(error)
                null
            } else {
                if (error is CancellationException) throw error
                // No primary: the tool call already completed (or never produced a
                // failure). Wrap so the reconnect classifier never retries the tool.
                McpPostCallCleanupException(error)
            }
        }
        if (cleanupError != null) {
            throw cleanupError
        }
    }

    private fun toMcpToolResult(result: CallToolResult): McpToolResult {
        val textContent = result.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
            .takeIf { it.isNotEmpty() }

        val structured = result.structuredContent?.let {
            val raw = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it)
            truncateToMaxBytes(raw)
        }

        val text = textContent?.let { truncateToMaxBytes(it) }

        return McpToolResult(
            content = text,
            structuredContent = structured,
            isError = result.isError ?: false,
        )
    }

    /**
     * Truncates the string so its UTF-8 encoded byte length does not exceed
     * [McpStepConfig.maxOutputBytes]. Appends a truncation footer if cut.
     */
    private fun truncateToMaxBytes(value: String): String {
        val bytes = value.encodeToByteArray()
        if (bytes.size <= config.maxOutputBytes) {
            return value
        }
        // Walk backwards from the byte limit to find a valid UTF-8 boundary.
        val limit = config.maxOutputBytes.toInt()
        var cutIndex = limit
        while (cutIndex > 0 && (bytes[cutIndex].toInt() and 0xC0) == 0x80) {
            cutIndex--
        }
        val truncatedBytes = bytes.copyOf(cutIndex)
        return truncatedBytes.toString(Charsets.UTF_8) + "\n\n[truncated at ${config.maxOutputBytes} bytes]"
    }

    private fun validateDefinition(toolCall: McpToolCall) {
        if (toolCall.serverCommand != definition.serverCommand) {
            throw WorkflowMcpException(
                stepName = name,
                message = "runtime server command does not match the canonical definition",
            )
        }
        if (toolCall.serverEnv != definition.serverEnv) {
            throw WorkflowMcpException(
                stepName = name,
                message = "runtime env does not match the canonical definition",
            )
        }
        if (toolCall.toolName != definition.toolName) {
            throw WorkflowMcpException(
                stepName = name,
                message = "runtime tool name does not match the canonical definition",
            )
        }
        if (toolCall.arguments.keys != definition.argumentKeys) {
            throw WorkflowMcpException(
                stepName = name,
                message = "runtime argument keys do not match the canonical definition",
            )
        }
    }

    private fun validateToolAllowlist(toolName: String) {
        val allowlist = config.toolAllowlist ?: return
        if (toolName !in allowlist) {
            throw WorkflowMcpException(
                stepName = name,
                message = "tool '$toolName' is not in the allowlist",
            )
        }
    }

    private fun validateCommandPolicy(toolCall: McpToolCall) {
        val commandIdentifiers = toolCall.serverCommand.commandIdentifiers()
        val allowedCommands = config.allowedCommands
        if (config.enforceCommandAllowlist &&
            (allowedCommands.isEmpty() || allowedCommands.none(commandIdentifiers::contains))
        ) {
            throw WorkflowMcpException(
                stepName = name,
                message = "command is not in allowlist",
            )
        }
        val deniedCommands = config.deniedCommands
        if (deniedCommands.any(commandIdentifiers::contains)) {
            throw WorkflowMcpException(
                stepName = name,
                message = "command is blocked by the denylist",
            )
        }
    }

    private fun wrapMcpError(
        error: Throwable,
        toolCall: McpToolCall? = null,
    ): WorkflowMcpException = when (error) {
        is WorkflowMcpException -> {
            if (toolCall == null) {
                error
            } else {
                WorkflowMcpException(
                    stepName = name,
                    message = sanitizeMcpErrorMessage(error.detailMessage(), toolCall),
                    cause = error.cause,
                )
            }
        }
        else -> WorkflowMcpException(
            stepName = name,
            message = "failed: ${sanitizeMcpErrorMessage(error.message ?: error::class.java.simpleName, toolCall)}",
            cause = error,
        )
    }

    private fun sanitizeMcpErrorMessage(
        message: String,
        toolCall: McpToolCall?,
    ): String {
        if (toolCall == null) {
            return message
        }
        var sanitized = message
        toolCall.serverCommand.forEach { part ->
            val identifier = File(part).name
            if (identifier.isNotEmpty() && identifier.length > 2) {
                sanitized = sanitized.replace(identifier, "[command]")
            }
        }
        return sanitized
    }

    private fun WorkflowMcpException.detailMessage(): String {
        val prefix = "Workflow MCP step '$stepName' "
        return message?.removePrefix(prefix) ?: this::class.java.simpleName
    }
}

/**
 * Failure of client.close() or transport cleanup AFTER the tool call already
 * completed (no earlier primary failure). The tool may have executed exactly
 * once, so this is never reconnectable — a retry would duplicate the call.
 */
private class McpPostCallCleanupException(cause: Throwable) :
    RuntimeException("MCP cleanup failed after tool completion", cause)

/**
 * Only retry on transport/setup failures, not timeouts or tool-level errors.
 * A non-idempotent MCP tool must not run twice after partial completion.
 */
private fun Throwable.isTransientForReconnect(): Boolean = when (this) {
    is TimeoutCancellationException -> false
    is WorkflowMcpException -> !message.orEmpty().contains("timed out")
    is McpPostCallCleanupException -> false
    else -> true
}

private fun List<String>.commandIdentifiers(): Set<String> {
    val executable = first()
    val fileName = java.io.File(executable).name
    return buildSet {
        add(executable)
        add(fileName)
    }
}

/** Version for MCP client identification within steps. */
private const val MCP_STEP_VERSION = "1.0.0"
