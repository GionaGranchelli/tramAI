package dev.tramai.orchestration

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.time.Duration.Companion.seconds

internal const val MCP_SERVER_START_FAILED_DIAGNOSTIC = "mcp_server_start_failed"
internal const val MCP_INITIALIZATION_FAILED_DIAGNOSTIC = "mcp_initialization_failed"
internal const val MCP_TOOL_EXECUTION_FAILED_DIAGNOSTIC = "mcp_tool_execution_failed"
internal const val MCP_TIMEOUT_DIAGNOSTIC = "mcp_timeout"
internal const val MCP_CANCELLED_DIAGNOSTIC = "mcp_cancelled"
internal const val MCP_CLEANUP_FAILED_DIAGNOSTIC = "mcp_cleanup_failed"

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

class WorkflowMcpException : RuntimeException {
    val stepName: String
    constructor(stepName: String, message: String, cause: Throwable? = null) :
        super("Workflow MCP step '$stepName' $message", cause) {
        this.stepName = stepName
    }

    var failureCode: WorkflowStepFailureCode? = null
        internal set
    internal var safeFactoryTrusted: Boolean = false
    internal constructor(stepName: String, safeMessage: String, safe: Boolean) : super(safeMessage) {
        this.stepName = stepName
    }
}

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
) {
    /**
     * Whether the transport has begun terminating the server process (step
     * timeout or cancellation). When true, transport errors surfacing from the
     * client are a CONSEQUENCE of that termination, not an independent
     * failure — the step classifies them as the timeout, never as a separate
     * TRANSPORT_FAILED (deterministic descendant-cleanup timeout reporting).
     *
     * Deliberately a class-body member, not a primary-constructor parameter:
     * it is internal process-lifecycle state, and adding it to the primary
     * constructor would break the published JVM descriptors of this public
     * data class (constructor, synthetic default ctor, copy(), componentN()).
     */
    internal var terminationRequested: () -> Boolean = { false }
}

/**
 * Default transport provider that starts the MCP server as a subprocess.
 * Concurrently drains stderr to prevent pipe-buffer deadlock.
 */
internal class SubprocessMcpTransportProvider : McpTransportProvider {
    override suspend fun connect(toolCall: McpToolCall): McpTransportConnection {
        val process =
            try {
                ProcessBuilder(toolCall.serverCommand)
                    .apply {
                        environment().putAll(toolCall.serverEnv)
                        redirectErrorStream(false)
                    }.start()
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                // No authoritative-refusal guard here on purpose: the E4 grant is taken by the caller before connect()
                // is invoked, so this catch only ever sees transport errors. A guard no path can reach would read as
                // coverage it does not provide.
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
        ).apply {
            // Class-body signal (kept off the public constructor for ABI stability):
            // the step classifies a transport error surfacing after termination as the
            // timeout, never as an independent TRANSPORT_FAILED.
            terminationRequested = { lifecycle.isTerminationRequested() }
        }
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
    override suspend fun execute(request: WorkflowStepExecutionRequest<S>): WorkflowStepExecutionResult<S> =
        WorkflowStepExecutionResult.Completed(
            execute(
                workflowName = request.workflowName,
                state = request.state,
                context = request.context,
                observer = request.observer,
                failureDiagnosticObserver = request.services.failureDiagnosticObserver,
            ),
        )

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
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver = NoOpWorkflowStepFailureDiagnosticObserver,
    ): S {
        val toolCall =
            try {
                toolCallBuilder(state, context)
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                throw failure(workflowName, error, WorkflowStepFailureCode.PREPARATION_FAILED, 1, false, failureDiagnosticObserver)
            }

        try {
            validateDefinition(toolCall)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw failure(workflowName, error, WorkflowStepFailureCode.VALIDATION_FAILED, 1, false, failureDiagnosticObserver)
        }
        try {
            validateToolAllowlist(toolCall.toolName)
            try {
                validateCommandPolicy(toolCall)
            } catch (error: WorkflowMcpException) {
                val policyType = if (error.message?.contains("allowlist") == true) "allowlist" else "deny-list"
                observer.emitWorkflowEvent(
                    workflowName = workflowName,
                    event =
                        RuntimeEvent.of(RuntimeEvents.WORKFLOW_SECURITY_COMMAND_DENIED) {
                            set(RuntimeAttributes.STEP_NAME, name)
                            set(
                                RuntimeAttributes.COMMAND_DIGEST,
                                File(toolCall.serverCommand.first()).name.sha256Digest(),
                            )
                            set(RuntimeAttributes.POLICY_TYPE, policyType)
                            set(RuntimeAttributes.STEP_FAMILY, "mcp")
                        },
                    context = context,
                )
                throw error
            }
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw failure(workflowName, error, WorkflowStepFailureCode.POLICY_REJECTED, 1, false, failureDiagnosticObserver)
        }

        observer.emitWorkflowEvent(
            workflowName = workflowName,
            event =
                RuntimeEvent.of(RuntimeEvents.WORKFLOW_MCP_STARTED) {
                    set(RuntimeAttributes.STEP_NAME, name)
                    set(RuntimeAttributes.TOOL_NAME_DIGEST, toolCall.toolName.sha256Digest())
                },
            context = context,
        )

        val result =
            runMcpCall(
                toolCall = toolCall,
                workflowName = workflowName,
                context = context,
                observer = observer,
                failureDiagnosticObserver = failureDiagnosticObserver,
            )

        observer.emitWorkflowEvent(
            workflowName = workflowName,
            event =
                RuntimeEvent.of(RuntimeEvents.WORKFLOW_MCP_COMPLETED) {
                    set(RuntimeAttributes.STEP_NAME, name)
                    set(RuntimeAttributes.TOOL_NAME_DIGEST, toolCall.toolName.sha256Digest())
                    set(RuntimeAttributes.IS_ERROR, result.isError)
                    set(RuntimeAttributes.CONTENT_SIZE_BYTES, (result.content?.length ?: 0).toLong())
                },
            context = context,
        )

        return try {
            merge(state, result, context)
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            throw failure(workflowName, error, WorkflowStepFailureCode.RESULT_HANDLING_FAILED, 1, false, failureDiagnosticObserver)
        }
    }

    private suspend fun runMcpCall(
        toolCall: McpToolCall,
        workflowName: String,
        context: WorkflowContext,
        observer: WorkflowObserver,
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
    ): McpToolResult {
        var lastError: Throwable? = null
        val maxAttempts = if (config.reconnect) 2 else 1

        for (attempt in 1..maxAttempts) {
            try {
                return executeMcpCall(
                    toolCall = toolCall,
                    workflowName = workflowName,
                    context = context,
                )
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                // A refusal is not a reconnectable transport failure and has no step failure code.
                error.rethrowIfAuthoritativeRefusal()
                lastError = error
                val code = mcpFailureCode(error)
                val willReconnect = attempt < maxAttempts && error.isTransientForReconnect()
                val safeFailure =
                    failure(
                        workflowName,
                        error,
                        code,
                        attempt,
                        willReconnect,
                        failureDiagnosticObserver,
                    )
                if (willReconnect) {
                    observer.emitWorkflowEvent(
                        workflowName = workflowName,
                        event =
                            RuntimeEvent.of(RuntimeEvents.WORKFLOW_MCP_RECONNECTING) {
                                set(RuntimeAttributes.STEP_NAME, name)
                                set(RuntimeAttributes.ATTEMPT, attempt.toLong())
                                set(RuntimeAttributes.FAILURE_CODE, code.value)
                            },
                        context = context,
                    )
                } else {
                    throw safeFailure
                }
            }
        }

        throw checkNotNull(lastError)
    }

    private suspend fun executeMcpCall(
        toolCall: McpToolCall,
        workflowName: String,
        context: WorkflowContext,
    ): McpToolResult =
        McpCallSession(
            step = this,
            toolCall = toolCall,
            workflowName = workflowName,
            context = context,
        ).run()

    internal fun toMcpToolResult(result: CallToolResult): McpToolResult {
        val textContent =
            result.content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text }
                .takeIf { it.isNotEmpty() }

        val structured =
            result.structuredContent?.let {
                val raw =
                    Json.encodeToString(
                        kotlinx.serialization.json.JsonObject
                            .serializer(),
                        it,
                    )
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

    private suspend fun failure(
        workflowName: String,
        error: Throwable,
        code: WorkflowStepFailureCode,
        attempt: Int,
        willReconnect: Boolean,
        failureDiagnosticObserver: WorkflowStepFailureDiagnosticObserver,
    ): RuntimeException {
        val detail = boundedWorkflowDetailPreview(error.message ?: error::class.java.name)
        deliverWorkflowStepFailure(
            failureDiagnosticObserver,
            WorkflowStepFailureDiagnosticEvent(
                workflowName,
                name,
                WorkflowStepKind.MCP,
                code,
                attempt,
                willReconnect,
                error,
                detail.text,
                detail.truncated,
            ),
        )
        return safeWorkflowStepFailure(
            WorkflowStepKind.MCP,
            code,
            fixedWorkflowStepMessage(WorkflowStepKind.MCP, code),
            name,
            attempt,
        )
    }
}

private data class McpEvidenceContext(
    val workflowName: String,
    val workflowId: String,
    val stepName: String,
    val evidenceStore: WorkflowEffectEvidenceRecordStore?,
    val stepAttempt: PersistedStepAttempt?,
)

private class McpCallSession(
    private val step: McpWorkflowStep<*>,
    private val toolCall: McpToolCall,
    private val workflowName: String,
    private val context: WorkflowContext,
) {
    private var mcpAdmission: WorkflowMcpAdmission? = null
    private var evidenceStore: WorkflowEffectEvidenceRecordStore? = null
    private var stepAttempt: PersistedStepAttempt? = null
    private lateinit var evidenceContext: McpEvidenceContext

    private var activeEvidenceKey: WorkflowEffectEvidenceKey? = null
    private var activeFailureSummary: String = MCP_SERVER_START_FAILED_DIAGNOSTIC
    private var connection: McpTransportConnection? = null
    private var client: Client? = null
    private var primaryFailure: Throwable? = null

    suspend fun run(): McpToolResult {
        mcpAdmission = currentMcpAdmission()
        evidenceStore = currentEffectEvidenceStore()
        stepAttempt = currentStepAttempt()
        evidenceContext =
            McpEvidenceContext(workflowName, context.workflowId, step.name, evidenceStore, stepAttempt)

        return try {
            withTimeout(step.config.timeoutSeconds.seconds) {
                if (evidenceStore != null) {
                    requireEvidenceLineageIfEnabled(
                        workflowName = workflowName,
                        workflowId = context.workflowId,
                        stepName = step.name,
                        admissionAvailable = mcpAdmission != null,
                        stepAttempt = stepAttempt,
                    )
                }
                dispatchCall()
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw McpTimeoutException(error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun dispatchCall(): McpToolResult {
        val serverStartPermit = mcpAdmission?.admitServerStart(step.name)
        serverStartPermit?.tryConsume()
        val serverStartKey =
            McpEvidenceOps.recordAdmitted(
                permit = serverStartPermit,
                context = evidenceContext,
                effectKind = MCP_SERVER_START_EFFECT_KIND,
                checkpointGeneration = serverStartPermit?.checkpointGeneration ?: "",
            )
        activeEvidenceKey = serverStartKey
        try {
            return performConnectedCall(serverStartKey)
        } catch (cancelled: CancellationException) {
            primaryFailure = cancelled
            throw cancelled
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            primaryFailure = error
            McpEvidenceOps.handleCallFailure(error, connection)
        } finally {
            performCleanup()
        }
    }

    private suspend fun performConnectedCall(serverStartKey: WorkflowEffectEvidenceKey?): McpToolResult {
        val conn = step.transportProvider.connect(toolCall)
        connection = conn
        McpEvidenceOps.updateState(serverStartKey, evidenceStore, WorkflowEffectEvidenceState.DISPATCHED)

        activeFailureSummary = MCP_INITIALIZATION_FAILED_DIAGNOSTIC
        val mcpClient =
            Client(
                clientInfo = Implementation(name = "tramai-mcp-step", version = MCP_STEP_VERSION),
            )
        client = mcpClient
        mcpClient.connect(StdioClientTransport(input = conn.input, output = conn.output))
        McpEvidenceOps.updateState(serverStartKey, evidenceStore, WorkflowEffectEvidenceState.COMPLETED)
        activeEvidenceKey = null

        val toolPermit = mcpAdmission?.admitToolCall(step.name)
        toolPermit?.tryConsume()
        val toolKey =
            McpEvidenceOps.recordAdmitted(
                permit = toolPermit,
                context = evidenceContext,
                effectKind = MCP_TOOL_EFFECT_KIND,
                checkpointGeneration = toolPermit?.checkpointGeneration ?: "",
            )
        activeEvidenceKey = toolKey
        activeFailureSummary = MCP_TOOL_EXECUTION_FAILED_DIAGNOSTIC

        val callResult = McpEvidenceOps.invokeToolCall(mcpClient, toolCall)
        return recordCompletedResult(toolKey, callResult)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun recordCompletedResult(
        toolKey: WorkflowEffectEvidenceKey?,
        callResult: CallToolResult,
    ): McpToolResult =
        try {
            McpEvidenceOps.updateState(toolKey, evidenceStore, WorkflowEffectEvidenceState.DISPATCHED)
            val converted = step.toMcpToolResult(callResult)
            activeEvidenceKey = null
            McpEvidenceOps.updateState(toolKey, evidenceStore, WorkflowEffectEvidenceState.COMPLETED)
            converted
        } catch (postCallError: Throwable) {
            postCallError.rethrowIfCancellation()
            postCallError.rethrowIfAuthoritativeRefusal()
            throw McpToolExecutionException(postCallError)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun performCleanup() {
        var closeFailure: Throwable? = null
        try {
            withContext(NonCancellable) { client?.close() }
        } catch (closeError: Throwable) {
            closeError.rethrowIfCancellation()
            if (primaryFailure != null) {
                primaryFailure?.suppressCleanupDiagnostic(closeError)
            } else {
                closeFailure = closeError
            }
        }

        try {
            McpEvidenceOps.cleanupConnection(connection, primaryFailure ?: closeFailure)
        } catch (cleanupError: Throwable) {
            cleanupError.rethrowIfCancellation()
            if (primaryFailure == null && closeFailure == null) {
                closeFailure = cleanupError
            }
        }

        val terminalError = primaryFailure ?: closeFailure
        McpEvidenceOps.recordUncertainty(
            evidenceKey = activeEvidenceKey,
            evidenceStore = evidenceContext.evidenceStore,
            terminalError = terminalError,
            defaultDiagnostic = activeFailureSummary,
        )

        if (primaryFailure == null && closeFailure != null) {
            if (closeFailure is CancellationException) throw closeFailure
            throw McpPostCallCleanupException(closeFailure)
        }
    }
}

private object McpEvidenceOps {
    @Suppress("TooGenericExceptionCaught")
    suspend fun invokeToolCall(
        client: Client,
        toolCall: McpToolCall,
    ): CallToolResult {
        val argumentsJson: Map<String, JsonElement> =
            toolCall.arguments.mapValues { (_, value) -> JsonPrimitive(value) }
        return try {
            client.callTool(toolCall.toolName, argumentsJson)
        } catch (callError: Throwable) {
            callError.rethrowIfCancellation()
            callError.rethrowIfAuthoritativeRefusal()
            throw McpToolExecutionException(callError)
        }
    }

    suspend fun handleCallFailure(
        error: Throwable,
        connection: McpTransportConnection?,
    ): Nothing {
        if (error is CancellationException) throw error
        error.rethrowIfAuthoritativeRefusal()
        currentCoroutineContext().ensureActive()
        val terminal =
            if (connection?.terminationRequested?.invoke() == true) {
                McpTimeoutException(error)
            } else {
                error
            }
        throw terminal
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun cleanupConnection(
        connection: McpTransportConnection?,
        primary: Throwable?,
    ) {
        if (connection?.cleanup == null) return
        val cleanupError: Throwable? =
            try {
                withContext(NonCancellable) { connection.cleanup.invoke() }
                null
            } catch (error: CancellationException) {
                if (primary != null) {
                    primary.suppressCleanupDiagnostic(error)
                    null
                } else {
                    throw error
                }
            } catch (error: Exception) {
                if (primary != null) {
                    primary.suppressCleanupDiagnostic(error)
                    null
                } else {
                    McpPostCallCleanupException(error)
                }
            }
        if (cleanupError != null) throw cleanupError
    }

    suspend fun recordUncertainty(
        evidenceKey: WorkflowEffectEvidenceKey?,
        evidenceStore: WorkflowEffectEvidenceRecordStore?,
        terminalError: Throwable?,
        defaultDiagnostic: String,
    ) {
        if (terminalError != null && evidenceKey != null) {
            val diagnostic =
                when (terminalError) {
                    is McpTimeoutException, is TimeoutCancellationException -> MCP_TIMEOUT_DIAGNOSTIC
                    is CancellationException -> MCP_CANCELLED_DIAGNOSTIC
                    else -> defaultDiagnostic
                }
            recordUncertainFailure(
                evidenceKey = evidenceKey,
                evidenceStore = evidenceStore,
                error = terminalError,
                diagnosticSummary = diagnostic,
            )
        }
    }

    suspend fun recordAdmitted(
        permit: EffectPermit?,
        context: McpEvidenceContext,
        effectKind: String,
        checkpointGeneration: String,
    ): WorkflowEffectEvidenceKey? {
        if (permit == null || context.evidenceStore == null || context.stepAttempt == null) return null
        val key =
            WorkflowEffectEvidenceKey(
                workflowName = context.workflowName,
                workflowId = context.workflowId,
                checkpointGeneration = checkpointGeneration,
                admissionSeq = permit.admissionSequence,
            )
        context.evidenceStore.recordEvidence(
            WorkflowEffectEvidenceRecord(
                key = key,
                stepName = context.stepName,
                stepAttemptId = context.stepAttempt.attemptId,
                effectKind = effectKind,
                state = WorkflowEffectEvidenceState.ADMITTED,
                createdAtEpochMillis = currentEffectEvidenceClockMillis() ?: context.stepAttempt.startedAtEpochMillis,
            ),
        )
        return key
    }

    suspend fun updateState(
        evidenceKey: WorkflowEffectEvidenceKey?,
        evidenceStore: WorkflowEffectEvidenceRecordStore?,
        targetState: WorkflowEffectEvidenceState,
    ) {
        if (evidenceKey == null || evidenceStore == null) return
        val record =
            evidenceStore.getEvidence(evidenceKey)
                ?: throw WorkflowEvidenceResolutionException(
                    "Missing expected evidence record for key: $evidenceKey (target state: $targetState)",
                )
        val completedAt =
            if (targetState == WorkflowEffectEvidenceState.COMPLETED) {
                currentEffectEvidenceClockMillis() ?: record.createdAtEpochMillis
            } else {
                record.completedAtEpochMillis
            }
        evidenceStore.updateEvidence(
            record.copy(
                state = targetState,
                completedAtEpochMillis = completedAt,
            ),
        )
    }
}

/**
 * Failure of client.close() or transport cleanup AFTER the tool call already
 * completed (no earlier primary failure). The tool may have executed exactly
 * once, so this is never reconnectable — a retry would duplicate the call.
 */
internal class McpPostCallCleanupException(
    cause: Throwable,
) : RuntimeException("MCP cleanup failed after tool completion", cause)

internal class McpTimeoutException(
    cause: Throwable,
) : RuntimeException("MCP call timed out", cause)

/**
 * Failure during the tool call invocation phase. Because the tool may already have executed
 * on the remote server, this exception must never trigger an automatic reconnect.
 */
internal class McpToolExecutionException(
    cause: Throwable,
) : RuntimeException("MCP tool execution failed", cause)

/**
 * Only retry on transport/setup failures, not timeouts or tool-level errors.
 * A non-idempotent MCP tool must not run twice after partial completion.
 */
private fun Throwable.isTransientForReconnect(): Boolean =
    when (this) {
        is TimeoutCancellationException -> false
        is CancellationException -> false
        is McpTimeoutException, is McpPostCallCleanupException, is McpToolExecutionException -> false
        is WorkflowMcpException -> failureCode == WorkflowStepFailureCode.TRANSPORT_FAILED || failureCode == null
        else -> mcpFailureCode(this) == WorkflowStepFailureCode.TRANSPORT_FAILED
    }

private fun mcpFailureCode(error: Throwable): WorkflowStepFailureCode =
    when (error) {
        is McpTimeoutException, is TimeoutCancellationException -> WorkflowStepFailureCode.TIMEOUT
        is McpPostCallCleanupException -> WorkflowStepFailureCode.CLEANUP_FAILED
        is McpToolExecutionException -> mcpFailureCode(error.cause ?: error)
        is WorkflowMcpException -> error.failureCode ?: WorkflowStepFailureCode.TRANSPORT_FAILED
        else -> WorkflowStepFailureCode.TRANSPORT_FAILED
    }

private fun String.sha256Digest(): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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
