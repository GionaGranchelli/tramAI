package dev.tramai.orchestration

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Source
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.InputStream
import java.io.OutputStream
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

        return McpTransportConnection(
            input = process.inputStream.asSource().buffered(),
            output = process.outputStream.asSink().buffered(),
            cleanup = {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (process.isAlive) {
                        process.destroyForcibly()
                    }
                    waitForUninterruptibly(process)
                }
            },
        )
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
            validateToolAllowlist(toolCall.toolName)
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
        val connection = transportProvider.connect(toolCall)

        return try {
            withTimeout(config.timeoutSeconds.seconds) {
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

                    val json = Json { ignoreUnknownKeys = true }
                    val argumentsJson: Map<String, JsonElement> = toolCall.arguments.mapValues { (_, value) ->
                        runCatching { json.parseToJsonElement(value) }
                            .getOrElse { json.parseToJsonElement("\"$value\"") }
                    }

                    val callResult: CallToolResult = client.callTool(toolCall.toolName, argumentsJson)
                    toMcpToolResult(callResult)
                } finally {
                    client.close()
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw WorkflowMcpException(
                stepName = name,
                message = "timed out after ${config.timeoutSeconds}s",
            )
        } finally {
            runCatching { connection.cleanup?.invoke() }
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

    private fun validateToolAllowlist(toolName: String) {
        val allowlist = config.toolAllowlist ?: return
        require(toolName in allowlist) {
            "Workflow MCP step '$name' tool '$toolName' is not in the allowlist"
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

private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}
