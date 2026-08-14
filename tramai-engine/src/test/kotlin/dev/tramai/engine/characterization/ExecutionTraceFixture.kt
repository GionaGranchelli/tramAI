package dev.tramai.engine.characterization

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRegistry
import dev.tramai.core.model.ModelRegistrySettings
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.policy.EnforcementPoint
import dev.tramai.core.policy.PolicyContext
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpResult
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.core.structured.StructuredOutputContract
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.engine.CachedOperationResult
import dev.tramai.engine.CachedResponseProvenance
import dev.tramai.engine.CircuitBreakerSettings
import dev.tramai.engine.EngineEventObserver
import dev.tramai.engine.OperationCacheKey
import dev.tramai.engine.OperationResponseCache
import dev.tramai.engine.ToolRegistry
import dev.tramai.engine.TramaiEngine
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@AiService
internal interface TraceService {
    @Operation(prompt = "Answer", model = "logical-model", providerRetries = 0)
    suspend fun answer(input: String): String

    @Operation(prompt = "Answer", model = "logical-model", cacheable = true, providerRetries = 0)
    suspend fun cached(input: String): String

    @Operation(prompt = "Answer", model = "logical-model", providerRetries = 1)
    suspend fun retryOnce(input: String): String

    @Operation(prompt = "Answer", model = "logical-model", providerRetries = 1)
    suspend fun retryThenFallback(input: String): String

    @Operation(prompt = "Answer", model = "logical-model", providerRetries = 0, maxRetries = 1)
    suspend fun structured(input: String): TraceStructuredResult

    @Operation(prompt = "Answer", model = "logical-model", tools = ["payment"], providerRetries = 0)
    suspend fun toolCall(input: String): String

    @Operation(prompt = "Answer", model = "logical-model", tools = ["payment"], providerRetries = 0)
    suspend fun toolApproval(input: String): String
}

internal data class TraceStructuredResult(val value: String)

@AiService
internal interface StreamingTraceService {
    @Operation(prompt = "Answer", model = "logical-model", providerRetries = 0)
    fun stream(input: String): kotlinx.coroutines.flow.Flow<dev.tramai.core.model.StreamChunk>
}

internal class ExecutionTraceFixture {
    val trace = ExecutionTrace()
    private val sink = ExecutionTraceSink(trace)
    var memoryEnabled = false
    var cacheEnabled = false
    var cachePreloaded = false
    var denyAt: EnforcementPoint? = null
    /** Fail the first N provider invocations with a retryable [ProviderException]. */
    var providerFailures: Int = 0
    /** Register a fallback provider for the logical model. */
    var fallbackEnabled = false
    /** Enable the circuit breaker with a failure threshold of 1. */
    var circuitBreakerEnabled = false
    /** Configure a structured-output handler that fails the first N analyzes. */
    var structuredFailures: Int = 0
    /** Whether the primary provider emits engine events into the trace. */
    var recordEngineEvents = false
    /** Whether tool execution is denied at BEFORE_TOOL_EXECUTION. */
    var denyToolExecution = false
    /** Whether tool exposure is denied at BEFORE_TOOL_EXPOSURE. */
    var denyToolExposure = false
    /** Whether tool execution requires approval (BEFORE_TOOL_EXECUTION → RequireApproval). */
    var approvalRequired = false
    /** Whether a recording DlpInterceptor inspects tool results (dlp.tool-result.inspect). */
    var dlpEnabled = false
    /** Whether the provider emits a stream of chunks instead of a single response. */
    var streaming = false
    /** Whether the streaming provider fails with a terminal error chunk. */
    var streamingFails = false
    /** Whether the provider suspends indefinitely during execution (for cancellation). */
    var blockingProvider = false
    /** Completed once the blocking provider has recorded provider.execute (deterministic cancellation sync). */
    val providerEntered = kotlinx.coroutines.CompletableDeferred<Unit>()

    fun engine(): TramaiEngine {
        val cache = RecordingCache(sink, cachePreloaded)
        val provider = RecordingProvider(sink, providerFailures, streaming = streaming, streamingFails = streamingFails, blocking = blockingProvider, entered = providerEntered)
        val builder = ProviderRegistry.builder()
            .provider("primary", provider, default = true)
            .model("logical-model", "primary")
        if (fallbackEnabled) {
            builder
                .provider("fallback", RecordingProvider(sink, 0, providerName = "fallback"))
                .fallbackProvider("logical-model", "fallback")
        }
        val toolRegistry = ToolRegistry(mapOf("payment" to RecordingTool(sink)))
        val approvalStore = if (approvalRequired) RecordingApprovalContinuationStore(sink) else null
        val approvalCoordinator = if (approvalRequired) RecordingApprovalCoordinator(sink) else null
        return TramaiEngine(
            providerRegistry = builder.build(),
            operationObserver = RecordingObserver(sink, recordEngineEvents),
            responseCache = if (cacheEnabled) cache else dev.tramai.engine.NoOpOperationResponseCache,
            modelRegistry = RecordingModelRegistry(sink),
            modelRegistrySettings = ModelRegistrySettings(enabled = true),
            chatMemory = if (memoryEnabled) RecordingMemory(sink) else null,
            conversationIdProvider = ConversationIdProvider { "conversation-1" },
            policyEngine = RecordingPolicyEngine(sink) { point ->
                when {
                    point == denyAt -> PolicyDecision.Deny("denied", "denied")
                    denyToolExposure && point == EnforcementPoint.BEFORE_TOOL_EXPOSURE ->
                        PolicyDecision.Deny("tool-exposure-denied", "tool_denied")
                    denyToolExecution && point == EnforcementPoint.BEFORE_TOOL_EXECUTION ->
                        PolicyDecision.Deny("tool-execution-denied", "tool_denied")
                    approvalRequired && point == EnforcementPoint.BEFORE_TOOL_EXECUTION ->
                        PolicyDecision.RequireApproval(
                            dev.tramai.core.policy.ApprovalRequirement(
                                toolName = "payment",
                                argumentsDigest = "",
                                reason = "requires approval",
                                timeoutMillis = 60_000,
                            ),
                        )
                    else -> PolicyDecision.Allow
                }
            },
            policyDecisionAuditEmitter = RecordingPolicyAudit(sink),
            engineEventObserver = object : EngineEventObserver {
                override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
                    if (recordEngineEvents) {
                        sink.record("engine.event", "name" to name)
                    }
                }
            },
            circuitBreakerSettings = if (circuitBreakerEnabled) {
                CircuitBreakerSettings(enabled = true, failureThreshold = 1, openDurationMillis = 60_000)
            } else {
                CircuitBreakerSettings()
            },
            structuredOutputHandler = RecordingStructuredHandler(sink, structuredFailures),
            toolRegistry = toolRegistry,
            dlpInterceptor = if (dlpEnabled) RecordingDlpInterceptor(sink) else NoOpDlpInterceptor,
            dlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
            suspendedInvocationStore = if (approvalRequired) RecordingSuspendedInvocationStore(sink) else dev.tramai.engine.InMemorySuspendedInvocationStore(),
            approvalContinuationStore = approvalStore,
            toolArgumentsDigester = if (approvalRequired) dev.tramai.security.approval.Sha256ToolArgumentsDigester() else null,
            approvalGateCoordinator = approvalCoordinator,
            approvalLifecycleAuditEmitter = if (approvalRequired) RecordingApprovalLifecycleEmitter(sink) else dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter,
            clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
        )
    }

    fun approved(name: String): List<TraceEvent> = javaClass.classLoader
        .getResourceAsStream("characterization/$name")!!
        .bufferedReader().readLines().filter(String::isNotBlank).map { line ->
            val tokens = line.split(' ')
            TraceEvent(tokens.first(), tokens.drop(1).associate { token -> token.substringBefore('=') to token.substringAfter('=') })
        }

    private class RecordingObserver(
        private val sink: ExecutionTraceSink,
        private val recordEngineEvents: Boolean,
    ) : OperationObserver {
        override fun onCallStarted(context: OperationCallContext): OperationObservation {
            sink.record("provider.start", "provider" to context.providerId, "model" to context.requestedModel, "attempt" to (context.attempt + 1).toString())
            return object : OperationObservation {
                private var failed = false
                override fun onProviderResponse(response: ModelResponse) = sink.record("provider.success", "provider" to context.providerId)
                override fun onProviderFailure(error: Throwable) {
                    failed = true
                    sink.record("provider.failure", "provider" to context.providerId)
                }
                override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) =
                    sink.record("structured.parse.failure")
                override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
                    if (recordEngineEvents) sink.record("engine.event", "name" to name)
                }
                override fun onCallCompleted(parseSuccess: Boolean?) =
                    sink.record("operation.complete", "outcome" to if (failed || parseSuccess == false) "failure" else "success")
                override fun onCallCancelled() {
                    failed = true
                    sink.record("operation.cancelled")
                }
            }
        }
    }
    private class RecordingMemory(private val sink: ExecutionTraceSink) : ChatMemory {
        override fun get(conversationId: String): List<Message> { sink.record("memory.load", "conversation" to conversationId); return emptyList() }
        override fun add(conversationId: String, messages: List<Message>) = sink.record("memory.persist", "conversation" to conversationId)
        override fun add(conversationId: String, message: Message) = add(conversationId, listOf(message))
        override fun clear(conversationId: String) = Unit
    }
    private class RecordingCache(private val sink: ExecutionTraceSink, private val preloaded: Boolean) : OperationResponseCache {
        private var value: CachedOperationResult? = if (preloaded) CachedOperationResult("cached", CachedResponseProvenance("primary", "logical-model", null, null, "id=*", "revision")) else null
        override fun get(key: OperationCacheKey): CachedOperationResult? { sink.record("cache.lookup"); return value.also { sink.record(if (it == null) "cache.miss" else "cache.hit") } }
        override fun put(key: OperationCacheKey, value: CachedOperationResult, ttlMillis: Long) { this.value = value; sink.record("cache.store") }
    }
    private class RecordingPolicyEngine(private val sink: ExecutionTraceSink, private val decision: (EnforcementPoint) -> PolicyDecision) : PolicyEngine {
        override suspend fun evaluate(context: PolicyContext): PolicyDecision = decision(context.enforcementPoint).also { sink.record("policy.evaluate", "point" to context.enforcementPoint.name); sink.record(
            when (it) {
                is PolicyDecision.Allow -> "policy.allow"
                is PolicyDecision.RequireApproval -> "policy.require-approval"
                else -> "policy.deny"
            },
            "point" to context.enforcementPoint.name,
        ) }
    }
    private class RecordingPolicyAudit(private val sink: ExecutionTraceSink) : PolicyDecisionAuditEmitter {
        override suspend fun emit(enforcementPoint: EnforcementPoint, context: PolicyContext, decision: PolicyDecision) = sink.record("policy.audit.emit", "point" to enforcementPoint.name)
    }
    private class RecordingModelRegistry(private val sink: ExecutionTraceSink) : ModelRegistry {
        override suspend fun findApprovedModel(providerId: String, modelName: String): RegisteredModel { sink.record("model.authorize", "model" to modelName); return RegisteredModel("id=*", providerId, modelName, "revision") }
    }
    private class RecordingProvider(
        private val sink: ExecutionTraceSink,
        private var failFirst: Int,
        private val providerName: String = "primary",
        private val streaming: Boolean = false,
        private val streamingFails: Boolean = false,
        private val blocking: Boolean = false,
        private val entered: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ) : ModelProvider, dev.tramai.core.provider.StreamCapable {
        private val callCount = AtomicInteger(0)
        private val toolCallEmitted = AtomicInteger(0)
        override suspend fun complete(request: ModelRequest): ModelResponse {
            val call = callCount.incrementAndGet()
            sink.record("provider.execute", "provider" to providerName, "model" to request.model)
            if (call <= failFirst) {
                delay(1)
                throw ProviderException("provider failed", retryable = true)
            }
            if (blocking) {
                entered?.let { if (!it.isCompleted) it.complete(Unit) }
                kotlinx.coroutines.awaitCancellation()
            }
            val wantsTool = request.tools?.isNotEmpty() == true && toolCallEmitted.incrementAndGet() == 1
            return if (wantsTool) {
                ModelResponse(
                    content = "",
                    modelUsed = request.model,
                    toolCalls = listOf(ToolCall("call-1", "payment", "{}")),
                )
            } else {
                ModelResponse(content = "answer", modelUsed = request.model)
            }
        }
        override fun providerId() = providerName
        override fun stream(request: ModelRequest): kotlinx.coroutines.flow.Flow<dev.tramai.core.model.StreamChunk> =
            kotlinx.coroutines.flow.flow {
                sink.record("streaming.start", "provider" to providerName)
                if (streamingFails) {
                    sink.record("streaming.terminal", "provider" to providerName, "outcome" to "failure")
                    emit(dev.tramai.core.model.StreamChunk.Error(ProviderException("stream failed", retryable = true)))
                    return@flow
                }
                emit(dev.tramai.core.model.StreamChunk.Token("first"))
                emit(dev.tramai.core.model.StreamChunk.Token("second"))
                sink.record("streaming.terminal", "provider" to providerName, "outcome" to "success")
                emit(dev.tramai.core.model.StreamChunk.Complete(fullText = "firstsecond"))
            }
    }
    private class RecordingTool(private val sink: ExecutionTraceSink) : ResolvedTool {
        override val name = "payment"
        override val description = "Payment tool"
        override val inputSchemaJson = "{}"
        override val idempotent = true
        override val sideEffectLevel = dev.tramai.core.model.SideEffectLevel.READ_ONLY
        override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
            sink.record("tool.execute.start", "tool" to name, "call" to "call-1")
            sink.record("tool.execute.success", "tool" to name)
            return ToolResult.Success("paid")
        }
    }
    private class RecordingStructuredHandler(
        private val sink: ExecutionTraceSink,
        private var failFirst: Int,
    ) : StructuredOutputHandler {
        override fun createContract(targetType: kotlin.reflect.KType): StructuredOutputContract =
            StructuredOutputContract(targetType = targetType, schemaJson = "{}")
        override fun analyze(rawResponse: String, targetType: kotlin.reflect.KType): StructuredOutputResult {
            if (failFirst > 0) {
                failFirst--
                return StructuredOutputResult.Failure(
                    rawResponse = rawResponse,
                    errorSummary = "parse failed",
                    feedbackMessage = "repair",
                )
            }
            return StructuredOutputResult.Success(
                value = TraceStructuredResult("parsed"),
                rawResponse = rawResponse,
            )
        }
        override fun generateSchema(type: kotlin.reflect.KType): String = "{}"
        override fun deserialize(input: Any, targetType: kotlin.reflect.KType): Any = input
        override fun serialize(value: Any): Any = value
    }
    private class RecordingDlpInterceptor(private val sink: ExecutionTraceSink) : DlpInterceptor {
        override fun inspect(context: DlpContext, text: String): DlpResult {
            sink.record(
                if (context.contentType == DlpContentType.TOOL_RESULT) "dlp.tool-result.inspect" else "dlp.request.inspect",
                "location" to context.contentLocation.name,
            )
            return DlpResult(sanitizedText = text)
        }
    }
    private class RecordingSuspendedInvocationStore(private val sink: ExecutionTraceSink) :
        dev.tramai.engine.SuspendedInvocationStore {
        private val delegate = dev.tramai.engine.InMemorySuspendedInvocationStore()
        override suspend fun create(
            metadata: dev.tramai.engine.SuspendedInvocationMetadata,
            replayEnvelope: dev.tramai.engine.SensitiveReplayEnvelope,
        ) {
            sink.record("invocation.suspended", "approval" to "id=*")
            delegate.create(metadata, replayEnvelope)
        }
        override suspend fun get(approvalId: String): dev.tramai.engine.SuspendedInvocationMetadata? = delegate.get(approvalId)
        override suspend fun revealReplayEnvelope(approvalId: String): dev.tramai.engine.SensitiveReplayEnvelope? = delegate.revealReplayEnvelope(approvalId)
        override suspend fun remove(approvalId: String): dev.tramai.engine.SuspendedInvocationMetadata? {
            sink.record("invocation.cleanup", "approval" to "id=*")
            return delegate.remove(approvalId)
        }
    }
    private class RecordingApprovalContinuationStore(private val sink: ExecutionTraceSink) :
        dev.tramai.core.approval.ApprovalContinuationStore {
        private val delegate = dev.tramai.security.approval.InMemoryApprovalContinuationStore(
            clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
        )
        override suspend fun create(
            continuation: dev.tramai.core.approval.ApprovalContinuation,
            arguments: dev.tramai.core.approval.SensitiveToolArguments,
        ): dev.tramai.core.approval.ApprovalContinuation {
            sink.record("approval.continuation.persist", "approval" to "id=*")
            return delegate.create(continuation, arguments)
        }
        override suspend fun get(approvalId: String): dev.tramai.core.approval.ApprovalContinuation? = delegate.get(approvalId)
        override suspend fun claimForExecution(
            approvalId: String,
            expectedVersion: Long,
            claimedBy: String,
        ): dev.tramai.core.approval.ClaimedApprovalContinuation {
            sink.record("approval.continuation.claim", "approval" to "id=*")
            return delegate.claimForExecution(approvalId, expectedVersion, claimedBy)
        }
        override suspend fun complete(
            approvalId: String,
            expectedVersion: Long,
            completedBy: String,
        ): dev.tramai.core.approval.ApprovalContinuation {
            sink.record("approval.continuation.complete", "approval" to "id=*")
            return delegate.complete(approvalId, expectedVersion, completedBy)
        }
        override suspend fun expire(approvalId: String, expectedVersion: Long): dev.tramai.core.approval.ApprovalContinuation = delegate.expire(approvalId, expectedVersion)
        override suspend fun cancel(approvalId: String, expectedVersion: Long): dev.tramai.core.approval.ApprovalContinuation = delegate.cancel(approvalId, expectedVersion)
        override suspend fun findStaleClaimed(claimedBefore: java.time.Instant, limit: Int): List<dev.tramai.core.approval.ApprovalContinuation> = delegate.findStaleClaimed(claimedBefore, limit)
        override suspend fun forceCancelClaimed(
            approvalId: String,
            expectedVersion: Long,
            cancelledBy: String,
            reasonCode: String,
        ): dev.tramai.core.approval.ApprovalContinuation = delegate.forceCancelClaimed(approvalId, expectedVersion, cancelledBy, reasonCode)
        override suspend fun sweepExpired(): Int = delegate.sweepExpired()
    }
    private class RecordingApprovalCoordinator(private val sink: ExecutionTraceSink) :
        dev.tramai.core.approval.ApprovalGateCoordinator {
        private val delegate = PassThroughApprovalCoordinator()
        override suspend fun createApproval(command: dev.tramai.core.approval.CreateApprovalCommand): dev.tramai.core.approval.ApprovalChallenge {
            sink.record("approval.required", "tool" to command.toolName)
            return delegate.createApproval(command)
        }
        override suspend fun validateResume(command: dev.tramai.core.approval.ValidateResumeCommand): dev.tramai.core.approval.ApprovalValidation {
            sink.record("approval.resume.validate", "approval" to "id=*")
            return delegate.validateResume(command)
        }
        override suspend fun authorizeResume(command: dev.tramai.core.approval.AuthorizeResumeCommand): dev.tramai.core.approval.ApprovalAuthorization {
            sink.record("approval.resume.authorize", "approval" to "id=*")
            return delegate.authorizeResume(command)
        }
        override suspend fun cancelApproval(approvalId: String, expectedVersion: Long, reason: String) = delegate.cancelApproval(approvalId, expectedVersion, reason)
    }
    private class RecordingApprovalLifecycleEmitter(private val sink: ExecutionTraceSink) :
        dev.tramai.core.approval.ApprovalLifecycleAuditEmitter {
        override suspend fun onToolExecutionSuspended(
            approvalId: String,
            workflowRunId: String,
            toolName: String,
            toolCallId: String,
            correlationId: String,
            argumentsDigest: dev.tramai.core.approval.Sha256Digest,
            expiresAt: java.time.Instant,
        ) = sink.record("approval.audit.emit", "tool" to toolName)
        override suspend fun onToolExecutionResumed(approvalId: String, workflowRunId: String, toolName: String, resumedBy: String) =
            sink.record("approval.resume.audit.emit", "tool" to toolName)
        override suspend fun onToolExecutionCompleted(approvalId: String, workflowRunId: String, toolName: String, completedBy: String) = Unit
        override suspend fun onUncertainOutcome(approvalId: String, workflowRunId: String, toolName: String, reason: String) = Unit
        override suspend fun onSuspensionCancelled(approvalId: String, workflowRunId: String, toolName: String, reason: String) = Unit
        override suspend fun onStaleClaimDetected(approvalId: String, workflowRunId: String, toolName: String, claimedAt: java.time.Instant) = Unit
        override suspend fun onClaimedContinuationForceCancellationRequested(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
        override suspend fun onClaimedContinuationForceCancelled(approvalId: String, workflowRunId: String, toolName: String, cancelledBy: String, reasonCode: String) = Unit
    }
    /** Minimal in-memory coordinator producing deterministic challenge/authorization values. */
    private class PassThroughApprovalCoordinator : dev.tramai.core.approval.ApprovalGateCoordinator {
        private var version = 0L
        override suspend fun createApproval(command: dev.tramai.core.approval.CreateApprovalCommand): dev.tramai.core.approval.ApprovalChallenge {
            version++
            return dev.tramai.core.approval.ApprovalChallenge(
                approvalId = "approval-1",
                token = dev.tramai.core.approval.ApprovalToken.parsePresented("token-approval-1"),
                expiresAt = command.expiresAt,
            )
        }
        override suspend fun validateResume(command: dev.tramai.core.approval.ValidateResumeCommand): dev.tramai.core.approval.ApprovalValidation =
            dev.tramai.core.approval.ApprovalValidation("approval-1", command.consumedBy, java.time.Instant.parse("2026-01-01T00:00:00Z"), version)
        override suspend fun authorizeResume(command: dev.tramai.core.approval.AuthorizeResumeCommand): dev.tramai.core.approval.ApprovalAuthorization =
            dev.tramai.core.approval.ApprovalAuthorization("approval-1", command.consumedBy, java.time.Instant.parse("2026-01-01T00:00:00Z"), version, replayed = false)
        override suspend fun cancelApproval(approvalId: String, expectedVersion: Long, reason: String) = Unit
    }
}
