package dev.tramai.orchestration

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
 * Prevents state-influenced command changes after the DSL is parsed.
 */
data class McpToolCallDefinition(
    val serverCommand: List<String>,
    val envKeys: Set<String> = emptySet(),
    val toolName: String,
    val argumentKeys: Set<String> = emptySet(),
) {
    init {
        require(serverCommand.isNotEmpty()) { "McpToolCallDefinition.serverCommand must not be empty" }
        require(serverCommand.none { it.isBlank() }) { "McpToolCallDefinition.serverCommand must not contain blank arguments" }
        require(toolName.isNotBlank()) { "McpToolCallDefinition.toolName must not be blank" }
        require(envKeys.none { it.isBlank() }) { "McpToolCallDefinition.envKeys must not contain blank values" }
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
    val allowedCommands: Set<String>? = null,
    val deniedCommands: Set<String> = emptySet(),
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
        require(allowedCommands?.none { it.isBlank() } != false) {
            "McpStepConfig.allowedCommands must not contain blank values"
        }
        require(deniedCommands.none { it.isBlank() }) {
            "McpStepConfig.deniedCommands must not contain blank values"
        }
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
interface McpTransportProvider {
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
            throw WorkflowMcpException(
                stepName = "<transport>",
                message = "failed to start MCP server: ${error.message ?: error::class.java.simpleName}",
                cause = error,
            )
        }

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
                withContext(NonCancellable + Dispatchers.IO) {
                    if (process.isAlive) {
                        process.destroyForcibly()
                    }
                    waitForUninterruptibly(process)
                }
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

    private fun waitForUninterruptibly(process: Process) {
        var interrupted = false
        while (true) {
            try {
                process.waitFor()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
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
            validateCommandPolicy(toolCall)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw wrapMcpError(error)
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
            throw wrapMcpError(error)
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
                if (attempt < maxAttempts) {
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
                }
            }
        }

        throw wrapMcpError(lastError!!)
    }

    private suspend fun executeMcpCall(toolCall: McpToolCall): McpToolResult {
        return try {
            withTimeout(config.timeoutSeconds.seconds) {
                val connection = transportProvider.connect(toolCall)

                try {
                    val client = Client(
                        clientInfo = Implementation(
                            name = "tramai-mcp-step",
                            version = "1.0.0",
                        ),
                    )
                    try {
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
                    } finally {
                        client.close()
                    }
                } finally {
                    runCatching { connection.cleanup?.invoke() }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw WorkflowMcpException(
                stepName = name,
                message = "timed out after ${config.timeoutSeconds}s",
            )
        }
    }

    private fun toMcpToolResult(result: CallToolResult): McpToolResult {
        val textContent = result.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
            .takeIf { it.isNotEmpty() }

        val structured = result.structuredContent?.let {
            val raw = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), it)
            if (raw.length > config.maxOutputBytes) {
                raw.take(config.maxOutputBytes.toInt()) + "\n\n[truncated at ${config.maxOutputBytes} bytes]"
            } else {
                raw
            }
        }

        val text = textContent?.let {
            if (it.length > config.maxOutputBytes) {
                it.take(config.maxOutputBytes.toInt()) + "\n\n[truncated at ${config.maxOutputBytes} bytes]"
            } else {
                it
            }
        }

        return McpToolResult(
            content = text,
            structuredContent = structured,
            isError = result.isError ?: false,
        )
    }

    private fun validateDefinition(toolCall: McpToolCall) {
        require(toolCall.serverCommand == definition.serverCommand) {
            "Workflow MCP step '$name' runtime server command does not match the canonical definition"
        }
        require(toolCall.serverEnv.keys == definition.envKeys) {
            "Workflow MCP step '$name' runtime env keys do not match the canonical definition"
        }
        require(toolCall.toolName == definition.toolName) {
            "Workflow MCP step '$name' runtime tool name does not match the canonical definition"
        }
        require(toolCall.arguments.keys == definition.argumentKeys) {
            "Workflow MCP step '$name' runtime argument keys do not match the canonical definition"
        }
    }

    private fun validateToolAllowlist(toolName: String) {
        val allowlist = config.toolAllowlist ?: return
        require(toolName in allowlist) {
            "Workflow MCP step '$name' tool '$toolName' is not in the allowlist"
        }
    }

    private fun validateCommandPolicy(toolCall: McpToolCall) {
        val commandIdentifiers = toolCall.serverCommand.commandIdentifiers()
        val deniedCommands = config.deniedCommands
        require(deniedCommands.none(commandIdentifiers::contains)) {
            "Workflow MCP step '$name' command is blocked by the denylist"
        }
        val allowedCommands = config.allowedCommands
        if (allowedCommands != null) {
            require(allowedCommands.any(commandIdentifiers::contains)) {
                "Workflow MCP step '$name' command is not permitted by the allowlist"
            }
        }
    }

    private fun wrapMcpError(
        error: Throwable,
    ): WorkflowMcpException = when (error) {
        is WorkflowMcpException -> error
        else -> WorkflowMcpException(
            stepName = name,
            message = "failed: ${error.message ?: error::class.java.simpleName}",
            cause = error,
        )
    }
}

private fun List<String>.commandIdentifiers(): Set<String> {
    val executable = first()
    val fileName = java.io.File(executable).name
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
