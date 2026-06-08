package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.ConversationId
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.System as SystemMessage
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.annotations.User as UserMessage
import dev.tramai.core.memory.ChatMemory
import dev.tramai.core.memory.ConversationIdProvider
import dev.tramai.core.memory.UuidConversationIdProvider
import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.exception.TramaiException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.ClassifiedDocument
import dev.tramai.core.model.ToolCall
import dev.tramai.core.model.ToolDefinition
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ToolResult
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.observation.NoOpOperationInterceptor
import dev.tramai.core.observation.NoOpOperationObserver
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.policy.PolicyEngine
import dev.tramai.core.policy.PolicyDecisionAuditEmitter
import dev.tramai.core.policy.NoOpPolicyDecisionAuditEmitter
import dev.tramai.core.security.DlpContentType
import dev.tramai.core.security.DlpContentLocation
import dev.tramai.core.security.DlpContext
import dev.tramai.core.security.DlpInspectionException
import dev.tramai.core.security.DlpInterceptor
import dev.tramai.core.security.DlpRedactionAuditEmitter
import dev.tramai.core.security.NoOpDlpInterceptor
import dev.tramai.core.security.NoOpDlpRedactionAuditEmitter
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalSuspendedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction

private const val MAX_SAFE_TOOL_NAME_LENGTH = 128
private const val UNREGISTERED_TOOL_NAME = "unregistered_tool"

/**
 * Runtime engine that turns annotated service interfaces into AI-backed proxies.
 */
class TramaiEngine(
    private val providerRegistry: ProviderRegistry,
    private val structuredOutputHandler: StructuredOutputHandler? = null,
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val operationObserver: OperationObserver = NoOpOperationObserver,
    private val operationInterceptor: OperationInterceptor = NoOpOperationInterceptor,
    private val responseCache: OperationResponseCache = NoOpOperationResponseCache,
    private val circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
    private val retryPolicySettings: RetryPolicySettings = RetryPolicySettings(),
    private val tokenBudgetSettings: TokenBudgetSettings = TokenBudgetSettings(),
    private val promptSanitizer: PromptSanitizer? = null,
    private val chatMemory: ChatMemory? = null,
    private val conversationIdProvider: ConversationIdProvider = UuidConversationIdProvider(),
    private val job: Job = SupervisorJob(),
    private val scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
    private val policyEngine: dev.tramai.core.policy.PolicyEngine? = null,
    private val dlpInterceptor: DlpInterceptor = NoOpDlpInterceptor,
    private val dlpRedactionAuditEmitter: DlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
    private val toolResultFilteringSettings: ToolResultFilteringSettings = ToolResultFilteringSettings(),
    private val engineEventObserver: EngineEventObserver = NoOpEngineEventObserver,
    private val policyDecisionAuditEmitter: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
    // Approval suspension dependencies
    private val suspendedInvocationStore: SuspendedInvocationStore = InMemorySuspendedInvocationStore(),
    private val approvalContinuationStore: ApprovalContinuationStore? = null,
    private val toolArgumentsDigester: ToolArgumentsDigester? = null,
    private val approvalGateCoordinator: ApprovalGateCoordinator? = null,
    private val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val circuitBreaker = ProviderCircuitBreaker(circuitBreakerSettings)
    private val retryDelayPolicy = ProviderRetryDelayPolicy(retryPolicySettings)
    private val migrationWarningGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val resolvedPolicyEngine: PolicyEngine = policyEngine
        ?: LegacyPermissivePolicyEngine
    private val isLegacyFallback: Boolean = policyEngine == null
    /** Internal handler reference used by [resumeApproval] and [resumeApprovalTyped]. */
    private var resumeHandler: TramaiInvocationHandler? = null

    /**
     * Creates an engine backed by a single provider.
     */
    constructor(
        provider: ModelProvider,
        structuredOutputHandler: StructuredOutputHandler? = null,
        toolRegistry: ToolRegistry = ToolRegistry(),
        operationObserver: OperationObserver = NoOpOperationObserver,
        operationInterceptor: OperationInterceptor = NoOpOperationInterceptor,
        responseCache: OperationResponseCache = NoOpOperationResponseCache,
        circuitBreakerSettings: CircuitBreakerSettings = CircuitBreakerSettings(),
        retryPolicySettings: RetryPolicySettings = RetryPolicySettings(),
        tokenBudgetSettings: TokenBudgetSettings = TokenBudgetSettings(),
        promptSanitizer: PromptSanitizer? = null,
        chatMemory: ChatMemory? = null,
        conversationIdProvider: ConversationIdProvider = UuidConversationIdProvider(),
        job: Job = SupervisorJob(),
        scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
        policyEngine: dev.tramai.core.policy.PolicyEngine? = null,
        dlpInterceptor: DlpInterceptor = NoOpDlpInterceptor,
        dlpRedactionAuditEmitter: DlpRedactionAuditEmitter = NoOpDlpRedactionAuditEmitter,
        toolResultFilteringSettings: ToolResultFilteringSettings = ToolResultFilteringSettings(),
        engineEventObserver: EngineEventObserver = NoOpEngineEventObserver,
        policyDecisionAuditEmitter: PolicyDecisionAuditEmitter = NoOpPolicyDecisionAuditEmitter,
        suspendedInvocationStore: SuspendedInvocationStore = InMemorySuspendedInvocationStore(),
        approvalContinuationStore: ApprovalContinuationStore? = null,
        toolArgumentsDigester: ToolArgumentsDigester? = null,
        approvalGateCoordinator: ApprovalGateCoordinator? = null,
        approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
        clock: Clock = Clock.systemUTC(),
    ) : this(
        providerRegistry = ProviderRegistry.singleProvider(provider),
        structuredOutputHandler = structuredOutputHandler,
        toolRegistry = toolRegistry,
        operationObserver = operationObserver,
        operationInterceptor = operationInterceptor,
        responseCache = responseCache,
        circuitBreakerSettings = circuitBreakerSettings,
        retryPolicySettings = retryPolicySettings,
        tokenBudgetSettings = tokenBudgetSettings,
        promptSanitizer = promptSanitizer,
        chatMemory = chatMemory,
        conversationIdProvider = conversationIdProvider,
        job = job,
        scope = scope,
        policyEngine = policyEngine,
        dlpInterceptor = dlpInterceptor,
        dlpRedactionAuditEmitter = dlpRedactionAuditEmitter,
        toolResultFilteringSettings = toolResultFilteringSettings,
        engineEventObserver = engineEventObserver,
        policyDecisionAuditEmitter = policyDecisionAuditEmitter,
        suspendedInvocationStore = suspendedInvocationStore,
        approvalContinuationStore = approvalContinuationStore,
        toolArgumentsDigester = toolArgumentsDigester,
        approvalGateCoordinator = approvalGateCoordinator,
        approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
        clock = clock,
    )

    /**
     * Creates a proxy implementation for the given Tramai service interface.
     */
    fun <T : Any> create(serviceType: KClass<T>): T {
        val definition = ServiceDefinition.create(
            serviceType = serviceType,
            toolRegistry = toolRegistry,
            promptSanitizer = promptSanitizer,
        )
        val handler = TramaiInvocationHandler(
            providerRegistry = providerRegistry,
            structuredOutputHandler = structuredOutputHandler,
            toolRegistry = toolRegistry,
            operationObserver = operationObserver,
            operationInterceptor = operationInterceptor,
            responseCache = responseCache,
            circuitBreaker = circuitBreaker,
            retryDelayPolicy = retryDelayPolicy,
            tokenBudgetSettings = tokenBudgetSettings,
            promptSanitizer = promptSanitizer,
            chatMemory = chatMemory,
            conversationIdProvider = conversationIdProvider,
            scope = scope,
            serviceDefinition = definition,
            policyEngine = resolvedPolicyEngine,
            migrationWarningGuard = migrationWarningGuard,
            isLegacyFallback = isLegacyFallback,
            dlpInterceptor = dlpInterceptor,
            dlpRedactionAuditEmitter = dlpRedactionAuditEmitter,
            toolResultFilteringSettings = toolResultFilteringSettings,
            engineEventObserver = engineEventObserver,
            policyDecisionAuditEmitter = policyDecisionAuditEmitter,
            suspendedInvocationStore = suspendedInvocationStore,
            approvalContinuationStore = approvalContinuationStore,
            toolArgumentsDigester = toolArgumentsDigester,
            approvalGateCoordinator = approvalGateCoordinator,
            approvalLifecycleAuditEmitter = approvalLifecycleAuditEmitter,
            clock = clock,
        )

        @Suppress("UNCHECKED_CAST")
        return (Proxy.newProxyInstance(
            serviceType.java.classLoader,
            arrayOf(serviceType.java),
            handler,
        ) as T).also {
            resumeHandler = handler
        }
    }

    /**
     * Resume an approval-suspended tool execution and return the operation result.
     *
     * Validates the approval, authorises the resume, claims the continuation,
     * executes the suspended tool, and continues the provider loop.
     *
     * @throws ApprovalSuspendedException if workflow resume policy requires approval.
     * @throws dev.tramai.core.exception.ApprovalNotFoundException if the approval does not exist.
     * @throws dev.tramai.core.exception.ApprovalTokenRejectedException if the presented token is invalid.
     * @throws dev.tramai.core.exception.ApprovalBindingMismatchException if binding metadata does not match.
     * @throws dev.tramai.core.exception.ApprovalAuthorizationException on store-level failures.
     */
    suspend fun resumeApproval(command: ResumeApprovalCommand): Any? {
        val handler = resumeHandler
            ?: throw dev.tramai.core.exception.ConfigurationException(
                "No service proxy created yet. Call create() before resumeApproval()."
            )
        return handler.resumeApprovalInternal(command)
    }

    /**
     * Typed convenience overload for [resumeApproval].
     */
    @Suppress("UNCHECKED_CAST")
    suspend inline fun <reified R> resumeApprovalTyped(command: ResumeApprovalCommand): R =
        resumeApproval(command) as R

    /**
     * Cancels the engine-owned coroutine job hierarchy.
     */
    override fun close() {
        job.cancel()
    }
}

/**
 * Reified convenience overload for [TramaiEngine.create].
 */
inline fun <reified T : Any> TramaiEngine.create(): T = create(T::class)

/**
 * Command to resume an approval-suspended tool execution.
 *
 * @property approvalId The approval ID of the suspended execution.
 * @property approvalExpectedVersion The expected version of the approval.
 * @property continuationExpectedVersion The expected version of the continuation.
 * @property presentedToken The approval token presented for authorization.
 * @property resumedBy The identity initiating the resume.
 */
data class ResumeApprovalCommand(
    val approvalId: String,
    val approvalExpectedVersion: Long,
    val continuationExpectedVersion: Long,
    val presentedToken: ApprovalToken,
    val resumedBy: String,
)

internal class TramaiInvocationHandler(
    private val providerRegistry: ProviderRegistry,
    private val structuredOutputHandler: StructuredOutputHandler?,
    private val toolRegistry: ToolRegistry,
    private val operationObserver: OperationObserver,
    private val operationInterceptor: OperationInterceptor,
    private val responseCache: OperationResponseCache,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val retryDelayPolicy: ProviderRetryDelayPolicy,
    private val tokenBudgetSettings: TokenBudgetSettings,
    private val promptSanitizer: PromptSanitizer?,
    private val chatMemory: ChatMemory?,
    private val conversationIdProvider: ConversationIdProvider,
    private val scope: CoroutineScope,
    private val serviceDefinition: ServiceDefinition,
    policyEngine: PolicyEngine,
    private val migrationWarningGuard: java.util.concurrent.atomic.AtomicBoolean,
    isLegacyFallback: Boolean,
    private val dlpInterceptor: DlpInterceptor,
    private val dlpRedactionAuditEmitter: DlpRedactionAuditEmitter,
    private val toolResultFilteringSettings: ToolResultFilteringSettings,
    private val engineEventObserver: EngineEventObserver,
    private val policyDecisionAuditEmitter: PolicyDecisionAuditEmitter,
    // Approval suspension dependencies
    private val suspendedInvocationStore: SuspendedInvocationStore,
    private val approvalContinuationStore: ApprovalContinuationStore?,
    private val toolArgumentsDigester: ToolArgumentsDigester?,
    private val approvalGateCoordinator: ApprovalGateCoordinator?,
    private val approvalLifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
    private val clock: Clock,
) : InvocationHandler {

    private val policyHelper = PolicyEnforcementHelper(policyEngine, migrationWarningGuard, isLegacyFallback = isLegacyFallback, auditEmitter = policyDecisionAuditEmitter)

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) {
            return handleObjectMethod(proxy, method, args.orEmpty())
        }

        val operation = serviceDefinition.operations[method]
            ?: throw ConfigurationException("No operation metadata registered for ${method.name}")

        val conversationId = if (chatMemory != null) resolveConversationId(method, args.orEmpty()) else null
        return if (operation.isSuspend) {
            invokeSuspend(operation, args.orEmpty(), conversationId)
        } else {
            runBlocking {
                execute(operation, args.orEmpty().toList(), conversationId)
            }
        }
    }

    private fun invokeSuspend(
        operation: OperationDefinition,
        args: Array<out Any?>,
        conversationId: String?,
    ): Any {
        // Kotlin suspend proxies receive the continuation as the last JVM argument.
        @Suppress("UNCHECKED_CAST")
        val continuation = args.lastOrNull() as? Continuation<Any?>
            ?: throw ConfigurationException("Suspend invocation for ${operation.method.name} is missing its continuation")

        val callArguments = args.dropLast(1)
        scope.launch(continuation.context) {
            runCatching { execute(operation, callArguments, conversationId) }
                .onSuccess { continuation.resumeWith(Result.success(it)) }
                .onFailure { continuation.resumeWith(Result.failure(it)) }
        }
        return COROUTINE_SUSPENDED
    }

    private suspend fun execute(
        operation: OperationDefinition,
        arguments: List<Any?>,
        conversationId: String?,
    ): Any? {
        val tokenBudgetTracker = TokenBudgetTracker(tokenBudgetSettings)
        val workflowRunId = java.util.UUID.randomUUID().toString()
        val workflowDigest = WorkflowDigestHelper.compute(operation, serviceDefinition)
        val identity = EngineExecutionIdentity(
            workflowRunId = workflowRunId,
            correlationId = "", // Will be set in executeRaw/executeStructured
            workflowDigest = workflowDigest,
            policyVersion = policyHelper.getPolicyVersion(),
            actorId = PolicyEnforcementHelper.ACTOR_ANONYMOUS,
        )
        return when (operation.returnKind) {
            ReturnKind.STRING -> executeRaw(operation, arguments, tokenBudgetTracker, conversationId, identity)
            ReturnKind.UNIT -> {
                executeRaw(operation, arguments, tokenBudgetTracker, conversationId, identity)
                Unit
            }
            ReturnKind.STRUCTURED -> executeStructured(operation, arguments, tokenBudgetTracker, conversationId, identity)
            ReturnKind.STREAMING -> executeStreaming(operation, arguments, tokenBudgetTracker, conversationId)
        }
    }

    private suspend fun executeStreaming(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
    ): Flow<StreamChunk> {
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val memoryInjection = injectMemoryMessages(operation, arguments, conversationId)
        val historySize = memoryInjection?.first?.size ?: 0
        val memoryMessages = memoryInjection?.second

        return flow {
            val correlationId = java.util.UUID.randomUUID().toString()

            // Enforce BEFORE_PROVIDER_RESOLUTION inside the flow for cold-Flow semantics
            policyHelper.enforce(
                policyHelper.buildContext(
                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_RESOLUTION,
                    correlationId = correlationId,
                ).modelName(operation.operation.model)
                    .applySecurityContext(securityContext)
                    .build()
            )

            val candidates = providerRegistry.resolveCandidates(operation.operation)

            var lastFailure: Throwable? = null
            var lastCircuitOpen: CircuitBreakerOpenException? = null
            val attemptCounter = AttemptCounter()

            for ((routeIndex, route) in candidates.withIndex()) {
                val blockedUntil = circuitBreaker.beforeCall(route.providerName)
                if (blockedUntil != null) {
                    lastCircuitOpen = CircuitBreakerOpenException(route.providerName, blockedUntil)
                    // Enforce BEFORE_FALLBACK before skipping to next route
                    val nextRoute = candidates.getOrNull(routeIndex + 1)
                    if (nextRoute != null) {
                        try {
                            enforceFallbackTransition(
                                correlationId = correlationId,
                                previousProviderId = route.providerName,
                                previousModelName = route.effectiveModelName,
                                nextProviderId = nextRoute.providerName,
                                reason = "circuit-breaker-open",
                                securityContext = securityContext,
                            )
                        } catch (policyError: PolicyViolationException) {
                            policyError.addSuppressed(lastCircuitOpen)
                            throw policyError
                        }
                    }
                    continue
                }

                // Enforce BEFORE_RESPONSE_RETURN per-route before any token emission
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(route.providerName)
                        .modelName(route.effectiveModelName)
                        .applySecurityContext(securityContext)
                        .build()
                )

                // Enforce BEFORE_TOOL_EXPOSURE per tool definition
                operation.toolDefinitions.forEach { toolDef ->
                    val tool = toolRegistry.resolve(toolDef.name)
                    policyHelper.enforce(
                        policyHelper.buildContext(
                            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_EXPOSURE,
                            correlationId = correlationId,
                        ).toolName(toolDef.name)
                            .toolSecurity(tool?.security)
                            .applySecurityContext(securityContext)
                            .build()
                    )
                }

                // Enforce BEFORE_PROVIDER_INVOCATION
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                        correlationId = correlationId,
                    ).providerId(route.providerName)
                        .modelName(route.effectiveModelName)
                        .applySecurityContext(securityContext)
                        .build()
                )

                val streamCapable = route.provider as? StreamCapable
                    ?: throw ProviderCapabilityException(route.providerName, "streaming")
                val request = operation.toRequest(arguments, modelName = route.effectiveModelName)
                val memoryInjectedRequest = if (memoryMessages != null) {
                    request.copy(messages = memoryMessages)
                } else {
                    request
                }

                when (
                    val result = collectStreamingRoute(
                        streamCapable = streamCapable,
                        request = memoryInjectedRequest,
                        operation = operation,
                        route = route,
                        attempt = attemptCounter.next(),
                        routeIndex = routeIndex,
                        tokenBudgetTracker = tokenBudgetTracker,
                        emitChunk = { emit(it) },
                    )
                ) {
                    is StreamingRouteResult.Completed -> {
                        if (chatMemory != null && conversationId != null) {
                            val assistantMessage = Message(
                                role = MessageRole.ASSISTANT,
                                content = result.fullText,
                            )
                            val turnMessages = memoryInjectedRequest.messages
                                .drop(historySize)
                                .filter { it.role != MessageRole.SYSTEM }
                            chatMemory.add(conversationId, turnMessages + assistantMessage)
                        }
                        return@flow
                    }
                    is StreamingRouteResult.StartupFailure -> {
                        // Enforce BEFORE_FALLBACK before trying the next route
                        val nextRoute = candidates.getOrNull(routeIndex + 1)
                        if (nextRoute != null) {
                            try {
                                enforceFallbackTransition(
                                    correlationId = correlationId,
                                    previousProviderId = route.providerName,
                                    previousModelName = route.effectiveModelName,
                                    nextProviderId = nextRoute.providerName,
                                    reason = "streaming-startup-failure",
                                    securityContext = securityContext,
                                )
                            } catch (policyError: PolicyViolationException) {
                                policyError.addSuppressed(result.error)
                                throw policyError
                            }
                        }
                        lastFailure = result.error
                    }
                    is StreamingRouteResult.TerminalError -> {
                        emit(result.errorChunk)
                        return@flow
                    }
                }
            }

            emit(
                StreamChunk.Error(
                    (lastFailure ?: lastCircuitOpen ?: ProviderException(
                        message = "No available streaming provider route for model '${operation.operation.model}'",
                        retryable = true,
                    )) as TramaiException,
                ),
            )
        }
    }

    private suspend fun collectStreamingRoute(
        streamCapable: StreamCapable,
        request: ModelRequest,
        operation: OperationDefinition,
        route: ResolvedProviderRoute,
        attempt: Int,
        routeIndex: Int,
        tokenBudgetTracker: TokenBudgetTracker,
        emitChunk: suspend (StreamChunk) -> Unit,
    ): StreamingRouteResult {
        var emittedAnyTokens = false
        val callContext = streamingCallContext(operation, route.providerName, attempt)
        val interceptedRequest = request.copy(
            messages = operationInterceptor.interceptRequest(callContext, request.messages),
        )
        val observation = startStreamingObservation(route, operation, attempt, routeIndex)

        return try {
            collectStreamingRouteChunks(
                streamCapable = streamCapable,
                request = interceptedRequest,
                timeoutMillis = request.timeoutMillis ?: operation.operation.timeoutMillis,
                ctx = StreamingRouteContext(
                    route = route,
                    operation = operation,
                    tokenBudgetTracker = tokenBudgetTracker,
                    callContext = callContext,
                    observation = observation,
                    emitChunk = emitChunk,
                ),
                hasEmittedTokens = { emittedAnyTokens },
                onToken = { chunk ->
                    emittedAnyTokens = true
                    emitChunk(chunk)
                },
            )
            error("Streaming route completed without a terminal result")
        } catch (finished: StreamingRouteFinished) {
            finished.result
        } catch (error: TimeoutCancellationException) {
            val timeout = TimeoutException(
                message = buildTimeoutMessage(
                    providerId = route.providerName,
                    operation = operation,
                    timeoutMillis = request.timeoutMillis ?: operation.operation.timeoutMillis,
                ),
                cause = error,
            )
            observation.onProviderFailure(timeout)
            handleFallbackResult(timeout, emittedAnyTokens, route.providerName, observation)
        } catch (error: CancellationException) {
            val cancellation = CancellationException("Streaming operation was cancelled by the consumer")
            cancellation.initCause(error)
            observation.onProviderFailure(cancellation)
            observation.onCallCompleted(parseSuccess = null)
            throw error
        } catch (error: Throwable) {
            val normalized = normalizeStreamingError(error, route.providerName, operation)
            observation.onProviderFailure(normalized)
            handleFallbackResult(normalized, emittedAnyTokens, route.providerName, observation)
        }
    }

    private fun streamingCallContext(
        operation: OperationDefinition,
        providerId: String,
        attempt: Int,
    ) = OperationCallContext(
        serviceInterface = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
        methodName = operation.method.name,
        providerId = providerId,
        requestedModel = operation.operation.model,
        attempt = attempt,
    )

    private fun startStreamingObservation(
        route: ResolvedProviderRoute,
        operation: OperationDefinition,
        attempt: Int,
        routeIndex: Int,
    ): OperationObservation = startObservation(
        providerId = route.providerName,
        operation = operation,
        attempt = attempt,
    ).also { observation ->
        observation.onEngineEvent(
            name = EVENT_ROUTE_SELECTED,
            attributes = routeSelectedAttributes(route, routeIndex),
        )
    }

    private data class StreamingRouteContext(
        val route: ResolvedProviderRoute,
        val operation: OperationDefinition,
        val tokenBudgetTracker: TokenBudgetTracker,
        val callContext: OperationCallContext,
        val observation: OperationObservation,
        val emitChunk: suspend (StreamChunk) -> Unit,
    )

    private data class StructuredAttemptContext(
        val operation: OperationDefinition,
        val arguments: List<Any?>,
        val schemaJson: String,
        val handler: StructuredOutputHandler,
        val messages: MutableList<Message>,
        val historySize: Int,
        val tokenBudgetTracker: TokenBudgetTracker,
        val conversationId: String?,
    )

    private suspend fun collectStreamingRouteChunks(
        streamCapable: StreamCapable,
        request: ModelRequest,
        timeoutMillis: Long,
        ctx: StreamingRouteContext,
        hasEmittedTokens: () -> Boolean,
        onToken: suspend (StreamChunk.Token) -> Unit,
    ) {
        withTimeout(timeoutMillis) {
            streamCapable.stream(request).collect { chunk ->
                handleStreamingChunk(
                    chunk = chunk,
                    ctx = ctx,
                    emittedAnyTokens = hasEmittedTokens(),
                    onToken = onToken,
                )
            }
            handleStreamingTerminationWithoutTerminalChunk(
                route = ctx.route,
                operation = ctx.operation,
                observation = ctx.observation,
                emittedAnyTokens = hasEmittedTokens(),
            )
        }
    }

    private suspend fun handleStreamingChunk(
        chunk: StreamChunk,
        ctx: StreamingRouteContext,
        emittedAnyTokens: Boolean,
        onToken: suspend (StreamChunk.Token) -> Unit,
    ) {
        when (chunk) {
            is StreamChunk.Token -> onToken(chunk)
            is StreamChunk.Complete -> handleStreamingComplete(
                chunk = chunk,
                route = ctx.route,
                tokenBudgetTracker = ctx.tokenBudgetTracker,
                callContext = ctx.callContext,
                observation = ctx.observation,
                emitChunk = ctx.emitChunk,
            )
            is StreamChunk.Error -> {
                ctx.observation.onProviderFailure(chunk.cause)
                finishStreamingRoute(
                    handleFallbackResult(
                        error = chunk.cause,
                        emittedAnyTokens = emittedAnyTokens,
                        providerName = ctx.route.providerName,
                        observation = ctx.observation,
                        terminalChunk = chunk,
                    ),
                )
            }
        }
    }

    private suspend fun handleStreamingComplete(
        chunk: StreamChunk.Complete,
        route: ResolvedProviderRoute,
        tokenBudgetTracker: TokenBudgetTracker,
        callContext: OperationCallContext,
        observation: OperationObservation,
        emitChunk: suspend (StreamChunk) -> Unit,
    ) {
        val response = ModelResponse(
            content = chunk.fullText,
            inputTokens = chunk.usage.inputTokens,
            outputTokens = chunk.usage.outputTokens,
            thinkingTokens = chunk.usage.thinkingTokens,
            modelUsed = route.effectiveModelName,
            finishReason = FinishReason.STOP,
        )

        val interceptedResponse = operationInterceptor.interceptResponse(callContext, response)
        observation.onProviderResponse(interceptedResponse)

        try {
            enforceTokenBudget(
                tracker = tokenBudgetTracker,
                response = interceptedResponse,
                observation = observation,
                providerId = route.providerName,
                modelName = route.effectiveModelName,
            )
        } catch (error: TokenBudgetExceededException) {
            observation.onCallCompleted(parseSuccess = null)
            throw StreamingRouteFinished(
                StreamingRouteResult.TerminalError(StreamChunk.Error(error)),
            )
        }
        observation.onCallCompleted(parseSuccess = null)
        circuitBreaker.onSuccess(route.providerName)
        emitChunk(
            if (interceptedResponse.content != chunk.fullText) {
                chunk.copy(fullText = interceptedResponse.content)
            } else {
                chunk
            }
        )
        throw StreamingRouteFinished(StreamingRouteResult.Completed(interceptedResponse.content))
    }

    private fun handleStreamingTerminationWithoutTerminalChunk(
        route: ResolvedProviderRoute,
        operation: OperationDefinition,
        observation: OperationObservation,
        emittedAnyTokens: Boolean,
    ): Nothing {
        val error = ProviderException(
            message = "Provider ${route.providerName} ended streaming without a terminal chunk while invoking ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}",
        )
        observation.onProviderFailure(error)
        finishStreamingRoute(
            handleFallbackResult(
                error = error,
                emittedAnyTokens = emittedAnyTokens,
                providerName = route.providerName,
                observation = observation,
            ),
        )
    }

    private fun normalizeStreamingError(
        error: Throwable,
        providerName: String,
        operation: OperationDefinition,
    ): TramaiException = when (error) {
        is TramaiException -> error
        else -> ProviderException(
            message = "Provider $providerName failed while streaming ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}",
            cause = error,
        )
    }

    private fun recordCircuitBreakerFailure(
        providerName: String,
        error: Throwable,
        observation: OperationObservation,
    ) {
        val opened = circuitBreaker.onFailure(providerName, error)
        if (opened) {
            observation.onEngineEvent(
                name = EVENT_CIRCUIT_OPENED,
                attributes = mapOf(ATTR_PROVIDER_ID to providerName),
            )
        }
    }

    /**
     * Enforces [EnforcementPoint.BEFORE_FALLBACK] at any transition point.
     * Used for provider failures, circuit breaker opens, streaming startup failures,
     * and route-unavailable transitions.
     */
    private suspend fun enforceFallbackTransition(
        correlationId: String,
        previousProviderId: String?,
        previousModelName: String?,
        nextProviderId: String,
        reason: String,
        securityContext: ExecutionSecurityContext,
    ) {
        val ctx = policyHelper.buildContext(
            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_FALLBACK,
            correlationId = correlationId,
        ).applySecurityContext(securityContext)
        if (previousProviderId != null) ctx.providerId(previousProviderId)
        if (previousModelName != null) ctx.modelName(previousModelName)
        ctx.fallbackProviderId(nextProviderId)
        ctx.attribute("fallbackReason", reason)
        policyHelper.enforce(ctx.build())
    }

    private fun recordStartupRetryEvent(
        providerName: String,
        failureType: String,
        observation: OperationObservation,
    ) {
        observation.onEngineEvent(
            name = EVENT_STARTUP_RETRY,
            attributes = mapOf(
                ATTR_PROVIDER_ID to providerName,
                ATTR_FAILURE_TYPE to failureType,
            ),
        )
    }

    private fun handleFallbackResult(
        error: TramaiException,
        emittedAnyTokens: Boolean,
        providerName: String,
        observation: OperationObservation,
        terminalChunk: StreamChunk.Error = StreamChunk.Error(error),
    ): StreamingRouteResult {
        val result = if (!emittedAnyTokens && shouldFallbackFrom(error)) {
            recordStartupRetryEvent(providerName, error::class.simpleName ?: "unknown", observation)
            StreamingRouteResult.StartupFailure(error)
        } else {
            StreamingRouteResult.TerminalError(terminalChunk)
        }
        recordCircuitBreakerFailure(providerName, error, observation)
        observation.onCallCompleted(parseSuccess = null)
        return result
    }

    private fun finishStreamingRoute(result: StreamingRouteResult): Nothing {
        throw StreamingRouteFinished(result)
    }

    private fun injectMemoryMessages(
        operation: OperationDefinition,
        arguments: List<Any?>,
        conversationId: String?,
    ): Pair<List<Message>, List<Message>>? = injectMemoryMessages(
        initialMessages = operation.initialMessages(arguments),
        conversationId = conversationId,
    )

    private fun injectMemoryMessages(
        initialMessages: List<Message>,
        conversationId: String?,
    ): Pair<List<Message>, List<Message>>? {
        if (chatMemory == null || conversationId == null) return null
        val history = chatMemory.get(conversationId)
        if (history.isEmpty()) return null
        val currentSystem = initialMessages.firstOrNull { it.role == MessageRole.SYSTEM }
        val deduped = if (currentSystem != null && history.any { it.role == MessageRole.SYSTEM }) {
            initialMessages.filter { it.role != MessageRole.SYSTEM }
        } else {
            initialMessages
        }
        return history to (history + deduped)
    }

    private suspend fun executeRaw(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
        identity: EngineExecutionIdentity,
    ): String {
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val correlationId = java.util.UUID.randomUUID().toString()
        val effectiveIdentity = identity.copy(correlationId = correlationId)
        val initialMessages = operation.initialMessages(arguments)
        val (history, effectiveMessages) = injectMemoryMessages(initialMessages, conversationId)
            ?: (emptyList<Message>() to initialMessages)
        val cacheKey = operation.takeIf { isSafeCacheEligible(it, conversationId) }?.buildCacheKey(
            digestSource = effectiveMessages,
            securityPartition = securityContext.toCacheSecurityPartition(),
        )

        cacheKey?.let { key ->
            operation.cachedValue(key, conversationId)?.let { cached ->
                authorizeCachedResult(
                    cacheKey = key,
                    cached = cached,
                    securityContext = securityContext,
                    correlationId = correlationId,
                )
                return cached.value as String
            }
        }

        val effectiveMutableMessages = effectiveMessages.toMutableList()

        val result = executeWithTools(
            operation = operation,
            messages = effectiveMutableMessages,
            tokenBudgetTracker = tokenBudgetTracker,
            correlationId = correlationId,
            securityContext = securityContext,
            identity = effectiveIdentity,
            conversationId = conversationId,
            historySize = history.size,
        )

        // DLP is already applied inside callProviderWithRetries — use the sanitized response directly

        // Enforce BEFORE_RESPONSE_RETURN
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = correlationId,
            ).providerId(result.providerId)
                .modelName(result.modelName)
                .applySecurityContext(securityContext)
                .build()
        )

        // Memory: persist response if chatMemory is configured
        if (chatMemory != null && conversationId != null) {
            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                content = result.response.content,
                toolCalls = result.response.toolCalls,
            )
            // Persist non-system messages from this turn (tool rounds + final assistant)
            val turnMessages = effectiveMutableMessages.drop(history.size).filter { it.role != MessageRole.SYSTEM }
            chatMemory.add(conversationId, turnMessages + assistantMessage)
        }

        result.observation.onCallCompleted(parseSuccess = null)
        return result.response.content.also {
            cacheKey?.let { key ->
                operation.cacheValue(key, it, result.providerId, result.modelName, securityContext, conversationId)
            }
        }
    }

    private suspend fun executeStructured(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
        identity: EngineExecutionIdentity,
    ): Any {
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val handler = structuredOutputHandler ?: throw ConfigurationException(
            "Structured return type ${operation.returnTypeDescription} requires a StructuredOutputHandler implementation from tramai-structured",
        )
        val contract = operation.structuredContract(handler)
        val correlationId = java.util.UUID.randomUUID().toString()
        val effectiveIdentity = identity.copy(correlationId = correlationId)
        val initialMessages = operation.initialMessages(arguments, contract.schemaJson)
        val (history, effectiveMessages) = injectMemoryMessages(initialMessages, conversationId)
            ?: (emptyList<Message>() to initialMessages)
        val cacheKey = operation.takeIf { isSafeCacheEligible(it, conversationId) }?.buildCacheKey(
            digestSource = effectiveMessages,
            securityPartition = securityContext.toCacheSecurityPartition(),
        )

        cacheKey?.let { key ->
            operation.cachedValue(key, conversationId)?.let { cached ->
                authorizeCachedResult(
                    cacheKey = key,
                    cached = cached,
                    securityContext = securityContext,
                    correlationId = correlationId,
                )
                return cached.value
            }
        }

        // Re-initialize messages list with history-injected content
        val messages = effectiveMessages.toMutableList()
        val initialTurnCount = history.size

        return executeStructuredRetryLoop(
            operation = operation,
            cacheKey = cacheKey,
            handler = handler,
            messages = messages,
            historySize = initialTurnCount,
            tokenBudgetTracker = tokenBudgetTracker,
            conversationId = conversationId,
            correlationId = correlationId,
            securityContext = securityContext,
            identity = effectiveIdentity,
        )
    }

    private suspend fun executeStructuredRetryLoop(
        operation: OperationDefinition,
        cacheKey: OperationCacheKey?,
        handler: StructuredOutputHandler,
        messages: MutableList<Message>,
        historySize: Int,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        identity: EngineExecutionIdentity,
    ): Any {
        val maxAttempts = operation.operation.maxRetries + 1
        val targetType = requireNotNull(operation.returnType) {
            "Structured return type ${operation.returnTypeDescription} could not be inspected without Kotlin reflection metadata"
        }

        repeat(maxAttempts) { attemptIndex ->
            val value = executeStructuredAttempt(
                operation = operation,
                cacheKey = cacheKey,
                handler = handler,
                messages = messages,
                historySize = historySize,
                tokenBudgetTracker = tokenBudgetTracker,
                conversationId = conversationId,
                targetType = targetType,
                attemptIndex = attemptIndex,
                maxAttempts = maxAttempts,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = identity,
            )
            if (value != null) {
                return value
            }
        }

        error("Structured retry loop exited without returning or throwing")
    }

    private suspend fun executeStructuredAttempt(
        operation: OperationDefinition,
        cacheKey: OperationCacheKey?,
        handler: StructuredOutputHandler,
        messages: MutableList<Message>,
        historySize: Int,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
        targetType: kotlin.reflect.KType,
        attemptIndex: Int,
        maxAttempts: Int,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        identity: EngineExecutionIdentity,
    ): Any? {
        val messagesBeforeCall = messages.size
        val result = executeWithTools(
            operation = operation,
            messages = messages,
            tokenBudgetTracker = tokenBudgetTracker,
            correlationId = correlationId,
            securityContext = securityContext,
            identity = identity,
            conversationId = conversationId,
            historySize = historySize,
        )

        // DLP is already applied inside callProviderWithRetries — use the sanitized response directly

        return when (
            val analysis = handler.analyze(
                rawResponse = result.response.content,
                targetType = targetType,
            )
        ) {
            is StructuredOutputResult.Success -> {
                // Enforce BEFORE_RESPONSE_RETURN before any side effects (persist, cache)
                // and before onCallCompleted so external consumers don't assume availability
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(result.providerId)
                        .modelName(result.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )

                result.observation.onCallCompleted(parseSuccess = true)

                persistStructuredSuccess(
                    result = result,
                    messages = messages,
                    historySize = historySize,
                    conversationId = conversationId,
                    messagesBeforeCall = messagesBeforeCall,
                )
                cacheKey?.let { key ->
                    operation.cacheValue(key, analysis.value, result.providerId, result.modelName, securityContext, conversationId)
                }

                analysis.value
            }
            is StructuredOutputResult.Failure -> {
                handleStructuredFailure(
                    operation = operation,
                    analysis = analysis,
                    result = result,
                    messages = messages,
                    attemptIndex = attemptIndex,
                    maxAttempts = maxAttempts,
                )
                null
            }
        }
    }

    private fun persistStructuredSuccess(
        result: ProviderCallResult,
        messages: MutableList<Message>,
        historySize: Int,
        conversationId: String?,
        messagesBeforeCall: Int,
    ) {
        if (chatMemory == null || conversationId == null) return
        val content = result.response.content
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = content,
            toolCalls = result.response.toolCalls,
        )
        val userPrompt = messages.subList(historySize, messagesBeforeCall)
            .filter { it.role != MessageRole.SYSTEM }
        val toolMessages = messages.drop(messagesBeforeCall)
        chatMemory.add(conversationId, userPrompt + toolMessages + assistantMessage)
    }

    /**
     * Persists the assistant response and this turn's non-system messages to chat memory.
     * Shared by [finalizeResumedOperation] for STRING/UNIT paths and by
     * [resumeStructuredResult] for the STRUCTURED path.
     */
    private fun persistMemory(
        loopResult: ProviderCallResult,
        messages: MutableList<Message>,
        historySize: Int,
        conversationId: String?,
    ) {
        if (chatMemory == null || conversationId == null) return
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = loopResult.response.content,
            toolCalls = loopResult.response.toolCalls,
        )
        val turnMessages = messages.drop(historySize).filter { it.role != MessageRole.SYSTEM }
        chatMemory.add(conversationId, turnMessages + assistantMessage)
    }

    private fun handleStructuredFailure(
        operation: OperationDefinition,
        analysis: StructuredOutputResult.Failure,
        result: ProviderCallResult,
        messages: MutableList<Message>,
        attemptIndex: Int,
        maxAttempts: Int,
    ) {
        result.observation.onStructuredParseFailure(
            rawResponse = analysis.rawResponse,
            errorSummary = analysis.errorSummary,
        )
        if (attemptIndex == maxAttempts - 1) {
            result.observation.onCallCompleted(parseSuccess = false)
            throw dev.tramai.core.exception.StructuredOutputException(
                message = "Structured output parsing failed after $maxAttempts attempt(s)",
                originalPrompt = operation.operation.prompt,
                lastRawResponse = analysis.rawResponse,
                validationError = analysis.errorSummary,
                attemptCount = maxAttempts,
            )
        }

        result.observation.onCallCompleted(parseSuccess = false)
        messages += Message(MessageRole.ASSISTANT, analysis.rawResponse)
        messages += Message(MessageRole.USER, analysis.feedbackMessage)
    }

    private suspend fun executeWithTools(
        operation: OperationDefinition,
        messages: MutableList<Message>,
        tokenBudgetTracker: TokenBudgetTracker,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        identity: EngineExecutionIdentity,
        conversationId: String? = null,
        historySize: Int = 0,
        resumingApproval: Boolean = false,
        parentApprovalId: String? = null,
    ): ProviderCallResult {
        val maxToolLoops = 5 // Guard against infinite tool loops
        val attemptCounter = AttemptCounter()
        repeat(maxToolLoops) {
            val result = callProviderWithFallbacks(
                operation = operation,
                messages = messages,
                attemptCounter = attemptCounter,
                correlationId = correlationId,
                securityContext = securityContext,
            )
            try {
                enforceTokenBudget(
                    tracker = tokenBudgetTracker,
                    response = result.response,
                    observation = result.observation,
                    providerId = result.providerId,
                    modelName = result.modelName,
                )
            } catch (error: TokenBudgetExceededException) {
                result.observation.onCallCompleted(parseSuccess = null)
                throw error
            }

            val toolCalls = result.response.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                return result
            }

            result.observation.onCallCompleted(parseSuccess = null)

            // Normalize unregistered tool calls: replace unknown names with safe placeholder
            val normalizedToolCalls = toolCalls.map { toolCall ->
                if (toolRegistry.resolve(toolCall.name) == null) {
                    toolCall.copy(name = UNREGISTERED_TOOL_NAME, argumentsJson = "{}")
                } else {
                    toolCall
                }
            }

            // Append assistant message with normalized tool calls
            messages += Message(
                role = MessageRole.ASSISTANT,
                content = result.response.content,
                toolCalls = normalizedToolCalls,
            )
            processToolCalls(
                operation = operation,
                toolCalls = normalizedToolCalls,
                messages = messages,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = identity,
                tokenBudgetTracker = tokenBudgetTracker,
                conversationId = conversationId,
                historySize = historySize,
                resumingApproval = resumingApproval,
                parentApprovalId = parentApprovalId,
            )
        }
        error("Exceeded maximum tool call loops ($maxToolLoops)")
    }

    private suspend fun processToolCalls(
        operation: OperationDefinition,
        toolCalls: List<ToolCall>,
        messages: MutableList<Message>,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        identity: EngineExecutionIdentity,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String? = null,
        historySize: Int = 0,
        resumingApproval: Boolean = false,
        parentApprovalId: String? = null,
    ) {
        for ((index, toolCall) in toolCalls.withIndex()) {
            val tool = toolRegistry.resolve(toolCall.name)
            val toolResult = if (tool == null) {
                ToolResult.PermanentFailure("Tool '<unregistered>' not found")
            } else {
                executeTool(tool, toolCall, operation, correlationId, securityContext, identity, messages, index, tokenBudgetTracker, conversationId, historySize, resumingApproval, parentApprovalId = parentApprovalId)
            }

            // Enforce BEFORE_TOOL_RESULT_REINJECTION
            policyHelper.enforce(
                policyHelper.buildContext(
                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION,
                    correlationId = correlationId,
                ).toolName(tool?.name ?: "<unregistered>")
                    .toolSecurity(tool?.security)
                    .applySecurityContext(securityContext)
                    .build()
            )

            val toolMessage = formatToolResult(
                toolResult = toolResult,
                toolCallId = toolCall.id,
            )
            messages += sanitizeToolMessageForReinjection(
                message = toolMessage,
                operation = operation,
                toolName = toolCall.name,
                correlationId = correlationId,
                securityContext = securityContext,
                engineEventObserver = engineEventObserver,
            )
        }
    }

    /**
     * Performs the authoritative DLP scan for a single response boundary.
     *
     * `text` must be the raw pre-DLP text for the specific scan boundary being inspected.
     */
    private suspend fun inspectDlpAuthoritatively(
        context: DlpContext,
        text: String,
    ) = dlpInterceptor.inspect(context, text).also { result ->
        val sanitizedTextChanged = result.sanitizedText != text
        val hasRedactionEvidence = result.redactions.isNotEmpty()

        if (sanitizedTextChanged && !hasRedactionEvidence && dlpRedactionAuditEmitter !== NoOpDlpRedactionAuditEmitter) {
            throw DlpInspectionException("DLP modified output without redaction evidence")
        }
        if (!sanitizedTextChanged && hasRedactionEvidence) {
            throw DlpInspectionException("DLP redactions reported without modifying output")
        }
        if (result.redactions.isNotEmpty()) {
            try {
                dlpRedactionAuditEmitter.emit(context, result.redactions)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw DlpInspectionException(
                    message = "DLP redaction audit emission failed",
                    cause = error,
                )
            }
        }
    }

    private fun inspectDlpForDetectionOnly(
        context: DlpContext,
        text: String,
    ) = dlpInterceptor.inspect(context, text)

    private suspend fun sanitizeToolMessageForReinjection(
        message: Message,
        operation: OperationDefinition,
        toolName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        engineEventObserver: EngineEventObserver,
    ): Message {
        if (dlpInterceptor === NoOpDlpInterceptor) {
            return message
        }

        val resolvedTool = toolRegistry.resolve(toolName)
        val canonicalToolName = resolvedTool?.name ?: "<unregistered>"
        val safeToolLabel = canonicalToolName.take(MAX_SAFE_TOOL_NAME_LENGTH)
        val dlpContext = DlpContext(
            contentType = DlpContentType.TOOL_RESULT,
            contentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT,
            operationInterface = operation.method.declaringClass.name,
            operationMethod = operation.method.name,
            toolName = canonicalToolName,
            correlationId = correlationId,
            dataClassification = securityContext.dataClassification,
            classificationSource = securityContext.classificationSource,
        )
        val aggregateTextLimit = toolResultFilteringSettings.maxAggregateTextLengthForTool(toolName)

        fun emitEngineEventSafely(
            name: String,
            attributes: Map<String, Any?>,
        ) {
            try {
                engineEventObserver.onEngineEvent(name, attributes)
            } catch (error: Exception) {
                System.getLogger("dev.tramai.engine.TramaiEngine")
                    .log(System.Logger.Level.WARNING, "Engine event observer failed for '$name': ${error::class.simpleName}")
            }
        }

        fun rejectAggregateTextLength(actualLength: Long): Nothing {
            emitEngineEventSafely(
                name = "tramai.dlp.tool_result_rejected",
                attributes = mapOf(
                    "reasonCode" to "aggregate_text_limit_exceeded",
                    "aggregateTextLength" to actualLength,
                    "configuredLimit" to aggregateTextLimit,
                    "correlationId" to correlationId,
                    "toolName" to safeToolLabel,
                ),
            )
            throw dev.tramai.core.security.DlpInspectionException(
                message = "Tool result from '$safeToolLabel' exceeds aggregate input limit ($actualLength > $aggregateTextLimit)",
            )
        }

        fun rejectSanitizedTextLimit(actualLength: Long): Nothing {
            emitEngineEventSafely(
                name = "tramai.dlp.tool_result_rejected",
                attributes = mapOf(
                    "reasonCode" to "sanitized_text_limit_exceeded",
                    "aggregateTextLength" to actualLength,
                    "configuredLimit" to aggregateTextLimit,
                    "correlationId" to correlationId,
                    "toolName" to safeToolLabel,
                ),
            )
            throw dev.tramai.core.security.DlpInspectionException(
                message = "Sanitized tool result from '$safeToolLabel' exceeds aggregate limit ($actualLength > $aggregateTextLimit)",
            )
        }

        fun rejectCrossBoundarySensitiveText(): Nothing {
            emitEngineEventSafely(
                name = "tramai.dlp.tool_result_rejected",
                attributes = mapOf(
                    "reasonCode" to "cross_boundary_sensitive_text_detected",
                    "correlationId" to correlationId,
                    "toolName" to safeToolLabel,
                ),
            )
            throw dev.tramai.core.security.DlpInspectionException(
                message = "Tool result from '$safeToolLabel' contains sensitive text spanning non-text boundaries",
            )
        }

        suspend fun sanitizeText(
            text: String,
            contentLocation: DlpContentLocation,
            authoritative: Boolean,
        ): String = try {
            val effectiveContext = dlpContext.copy(contentLocation = contentLocation)
            val result = if (authoritative) {
                inspectDlpAuthoritatively(effectiveContext, text)
            } else {
                inspectDlpForDetectionOnly(effectiveContext, text)
            }
            result.sanitizedText.also { sanitizedText ->
                if (sanitizedText.length.toLong() > aggregateTextLimit) {
                    rejectSanitizedTextLimit(sanitizedText.length.toLong())
                }
            }
        } catch (e: DlpInspectionException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emitEngineEventSafely(
                name = "tramai.dlp.inspection_failed",
                attributes = mapOf("toolName" to safeToolLabel, "correlationId" to correlationId),
            )
            throw dev.tramai.core.security.DlpInspectionException(
                message = "DLP inspection failed for tool result from tool '$safeToolLabel'",
                cause = e,
            )
        }

        fun accumulateLength(currentLength: Long, text: String): Long {
            val nextLength = if (currentLength > Long.MAX_VALUE - text.length.toLong()) {
                Long.MAX_VALUE
            } else {
                currentLength + text.length.toLong()
            }
            if (nextLength > aggregateTextLimit) {
                rejectAggregateTextLength(nextLength)
            }
            return nextLength
        }

        val contentParts = message.contentParts
        if (contentParts.isNullOrEmpty()) {
            if (message.content.isEmpty()) {
                return message
            }
            accumulateLength(0L, message.content)
            return message.copy(
                content = sanitizeText(
                    text = message.content,
                    contentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT,
                    authoritative = true,
                ),
            )
        }

        var aggregateLength = 0L
        val sanitizedParts = mutableListOf<ContentPart>()
        val textRun = mutableListOf<String>()
        val sanitizedTextRuns = mutableListOf<String>()
        var sanitizedAggregateLength = 0L

        fun accumulateSanitizedLength(currentLength: Long, text: String): Long {
            val nextLength = if (currentLength > Long.MAX_VALUE - text.length.toLong()) {
                Long.MAX_VALUE
            } else {
                currentLength + text.length.toLong()
            }
            if (nextLength > aggregateTextLimit) {
                rejectSanitizedTextLimit(nextLength)
            }
            return nextLength
        }

        suspend fun flushTextRun() {
            if (textRun.isEmpty()) {
                return
            }
            val combinedText = buildString {
                textRun.forEach(::append)
            }
            val sanitizedText = sanitizeText(
                text = combinedText,
                contentLocation = DlpContentLocation.TOOL_MESSAGE_TEXT_RUN,
                authoritative = true,
            )
            sanitizedAggregateLength = accumulateSanitizedLength(sanitizedAggregateLength, sanitizedText)
            sanitizedTextRuns += sanitizedText
            if (sanitizedText.isNotEmpty()) {
                sanitizedParts += ContentPart.TextPart(sanitizedText)
            }
            textRun.clear()
        }

        contentParts.forEach { part ->
            when (part) {
                is ContentPart.TextPart -> {
                    aggregateLength = accumulateLength(aggregateLength, part.text)
                    textRun += part.text
                }
                else -> {
                    flushTextRun()
                    sanitizedParts += part
                }
            }
        }
        flushTextRun()

        val allTextParts = contentParts.mapNotNull { part ->
            (part as? ContentPart.TextPart)?.text
        }
        if (allTextParts.size > 1) {
            val projectedText = allTextParts.joinToString("")
            val projectedResult = sanitizeText(
                text = projectedText,
                contentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT,
                authoritative = false,
            )
            val individualCombined = buildString {
                sanitizedTextRuns.forEach(::append)
            }
            val combinedResanitized = sanitizeText(
                text = individualCombined,
                contentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT,
                authoritative = false,
            )
            if (projectedResult != individualCombined && combinedResanitized != individualCombined) {
                rejectCrossBoundarySensitiveText()
            }
        }

        return message.copy(
            content = "",
            contentParts = sanitizedParts.ifEmpty { null },
        )
    }

    private fun formatToolResult(toolResult: ToolResult, toolCallId: String): Message = when (toolResult) {
        is ToolResult.Success -> createToolSuccessMessage(toolResult, toolCallId)
        is ToolResult.InvalidInput -> Message(
            role = MessageRole.TOOL,
            content = "Error: ${toolResult.message}",
            toolCallId = toolCallId,
        )
        is ToolResult.PermanentFailure -> Message(
            role = MessageRole.TOOL,
            content = "Permanent error: ${toolResult.message}",
            toolCallId = toolCallId,
        )
        is ToolResult.TransientFailure -> error("TransientFailure should be resolved inside executeTool")
    }

    private fun createToolSuccessMessage(toolResult: ToolResult.Success, toolCallId: String): Message {
        val textContent = toolResult.value.toString()
        val contentParts = toolResult.contentParts
        return if (contentParts != null && contentParts.isNotEmpty()) {
            val parts = buildList<ContentPart> {
                add(ContentPart.TextPart(textContent))
                addAll(contentParts)
            }
            Message(
                role = MessageRole.TOOL,
                content = "",
                contentParts = parts,
                toolCallId = toolCallId,
            )
        } else {
            Message(
                role = MessageRole.TOOL,
                content = textContent,
                toolCallId = toolCallId,
            )
        }
    }

    private suspend fun callProviderWithFallbacks(
        operation: OperationDefinition,
        messages: List<Message>,
        attemptCounter: AttemptCounter,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): ProviderCallResult {
        var lastFallbackFailure: Throwable? = null
        var lastCircuitOpen: CircuitBreakerOpenException? = null

        val base = policyHelper.buildContext(
            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_RESOLUTION,
            correlationId = correlationId,
        ).providerId(null)
            .modelName(operation.operation.model)
            .applySecurityContext(securityContext)
            .build()
        policyHelper.enforce(base)

        val candidates = providerRegistry.resolveCandidates(operation.operation)

        for ((routeIndex, route) in candidates.withIndex()) {
            val blockedUntil = circuitBreaker.beforeCall(route.providerName)
            if (blockedUntil != null) {
                lastCircuitOpen = CircuitBreakerOpenException(route.providerName, blockedUntil)
                // Enforce BEFORE_FALLBACK before skipping to next route
                val nextRoute = candidates.getOrNull(routeIndex + 1)
                if (nextRoute != null) {
                    try {
                        enforceFallbackTransition(
                            correlationId = correlationId,
                            previousProviderId = route.providerName,
                            previousModelName = route.effectiveModelName,
                            nextProviderId = nextRoute.providerName,
                            reason = "circuit-breaker-open",
                            securityContext = securityContext,
                        )
                    } catch (policyError: PolicyViolationException) {
                        policyError.addSuppressed(lastCircuitOpen)
                        throw policyError
                    }
                }
                continue
            }

            try {
                // Enforce BEFORE_TOOL_EXPOSURE per tool definition
                operation.toolDefinitions.forEach { toolDef ->
                    val tool = toolRegistry.resolve(toolDef.name)
                    policyHelper.enforce(
                        policyHelper.buildContext(
                            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_EXPOSURE,
                            correlationId = correlationId,
                        ).toolName(toolDef.name)
                            .toolSecurity(tool?.security)
                            .applySecurityContext(securityContext)
                            .build()
                    )
                }

                return callProviderWithRetries(
                    providerId = route.providerName,
                    provider = route.provider,
                    request = ModelRequest(
                        model = route.effectiveModelName,
                        messages = messages.toList(),
                        tools = operation.toolDefinitions.takeIf { it.isNotEmpty() },
                        timeoutMillis = operation.operation.timeoutMillis,
                        operationInterface = operation.method.declaringClass.name,
                        operationMethod = operation.method.name,
                    ),
                    operation = operation,
                    attemptCounter = attemptCounter,
                    routeIndex = routeIndex,
                    correlationId = correlationId,
                    securityContext = securityContext,
                )
            } catch (error: Throwable) {
                if (!shouldFallbackFrom(error)) {
                    throw error
                }
                // Only enforce BEFORE_FALLBACK when another candidate exists
                val nextRoute = candidates.getOrNull(routeIndex + 1)
                if (nextRoute != null) {
                    try {
                        enforceFallbackTransition(
                            correlationId = correlationId,
                            previousProviderId = route.providerName,
                            previousModelName = route.effectiveModelName,
                            nextProviderId = nextRoute.providerName,
                            reason = "provider-failure",
                            securityContext = securityContext,
                        )
                    } catch (policyError: PolicyViolationException) {
                        // Fallback denied — propagate policy violation with original error as suppressed
                        policyError.addSuppressed(error)
                        throw policyError
                    }
                }
                lastFallbackFailure = error
            }
        }

        throw lastFallbackFailure
            ?: lastCircuitOpen
            ?: ProviderException(
                message = "No available provider route for model '${operation.operation.model}'",
                retryable = true,
            )
    }

    private suspend fun callProviderWithRetries(
        providerId: String,
        provider: ModelProvider,
        request: ModelRequest,
        operation: OperationDefinition,
        attemptCounter: AttemptCounter,
        routeIndex: Int,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): ProviderCallResult {
        val maxAttempts = operation.operation.providerRetries + 1

        repeat(maxAttempts) { retryIndex ->
            val callContext = OperationCallContext(
                serviceInterface = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
                methodName = operation.method.name,
                providerId = providerId,
                requestedModel = operation.operation.model,
                attempt = attemptCounter.next(),
            )

            val interceptedMessages = operationInterceptor.interceptRequest(callContext, request.messages)
            val interceptedRequest = request.copy(messages = interceptedMessages)

            val observation = operationObserver.onCallStarted(callContext)
            observation.onEngineEvent(
                name = EVENT_ROUTE_SELECTED,
                attributes = routeSelectedAttributes(
                    ResolvedProviderRoute(
                        providerName = providerId,
                        provider = provider,
                        requestedModelName = operation.operation.model,
                        effectiveModelName = request.model,
                    ),
                    routeIndex = routeIndex,
                ),
            )

            try {
                // Enforce BEFORE_PROVIDER_INVOCATION
                policyHelper.enforce(
                policyHelper.buildContext(
                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                    correlationId = correlationId,
                ).providerId(providerId)
                    .modelName(request.model)
                    .applySecurityContext(securityContext)
                    .build()
                )

                val rawResponse = callProviderOnce(providerId, provider, interceptedRequest, operation)
                val interceptedResponse = operationInterceptor.interceptResponse(callContext, rawResponse)

                // DLP: sanitize model output at the earliest safe boundary
                val sanitizedResponse = try {
                    if (dlpInterceptor !== NoOpDlpInterceptor) {
                        val dlpContext = DlpContext(
                            contentType = DlpContentType.MODEL_OUTPUT,
                            contentLocation = DlpContentLocation.MODEL_RESPONSE_CONTENT,
                            operationInterface = serviceDefinition.serviceType.qualifiedName
                                ?: serviceDefinition.serviceType.simpleName.orEmpty(),
                            operationMethod = operation.method.name,
                            providerId = providerId,
                            modelName = request.model,
                            correlationId = correlationId,
                            dataClassification = securityContext.dataClassification,
                            classificationSource = securityContext.classificationSource,
                        )
                        val dlpResult = inspectDlpAuthoritatively(dlpContext, interceptedResponse.content)
                        if (dlpResult.sanitizedText != interceptedResponse.content) {
                            interceptedResponse.copy(content = dlpResult.sanitizedText)
                        } else {
                            interceptedResponse
                        }
                    } else {
                        interceptedResponse
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: dev.tramai.core.security.DlpInspectionException) {
                    throw e
                } catch (e: Exception) {
                    // DLP failures are separate from provider failures:
                    //   - Do NOT call observation.onProviderFailure(...)
                    //   - Do NOT call circuitBreaker.onFailure(...)
                    //   - Do NOT retry (DLP is deterministic per response)
                    //   - Do NOT fallback (response content cannot be returned unsanitized)
                    observation.onEngineEvent(
                        name = "tramai.dlp.inspection_failed",
                        attributes = mapOf("providerId" to providerId, "correlationId" to correlationId),
                    )
                    throw dev.tramai.core.security.DlpInspectionException(
                        message = "DLP inspection failed for provider '$providerId'",
                        cause = e,
                    )
                }

                observation.onProviderResponse(sanitizedResponse)
                return ProviderCallResult(
                    response = sanitizedResponse,
                    observation = observation,
                    providerId = providerId,
                    modelName = request.model,
                )
            } catch (error: dev.tramai.core.security.DlpInspectionException) {
                // DLP failures propagate directly — NOT a provider failure.
                // Do NOT call observation.onProviderFailure, circuitBreaker.onFailure,
                // or retry. Record call completion once.
                observation.onCallCompleted(parseSuccess = null)
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                observation.onProviderFailure(error)
                observation.onCallCompleted(parseSuccess = null)

                if (!shouldRetryProviderCall(error, retryIndex, maxAttempts)) {
                    val opened = circuitBreaker.onFailure(providerId, error)
                    if (opened) {
                        observation.onEngineEvent(
                            name = EVENT_CIRCUIT_OPENED,
                            attributes = mapOf(ATTR_PROVIDER_ID to providerId),
                        )
                    }
                    throw error
                }

                val delayMillis = providerRetryDelayMillis(retryIndex, error)
                observation.onEngineEvent(
                    name = "tramai.retry.scheduled",
                    attributes = mapOf(
                        ATTR_PROVIDER_ID to providerId,
                        ATTR_RETRY_INDEX to retryIndex,
                        ATTR_DELAY_MILLIS to delayMillis,
                        ATTR_DELAY_SOURCE to retryDelaySource(error),
                    ),
                )
                delay(delayMillis)
            }
        }

        error("Provider retry loop exited without returning or throwing")
    }

    private suspend fun executeTool(
        tool: ResolvedTool,
        toolCall: ToolCall,
        operation: OperationDefinition,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        identity: EngineExecutionIdentity,
        messages: List<Message>,
        toolCallIndex: Int = -1,
        tokenBudgetTracker: TokenBudgetTracker? = null,
        conversationId: String? = null,
        historySize: Int = 0,
        resumingApproval: Boolean = false,
        parentApprovalId: String? = null,
    ): ToolResult {
        val input = toolCall.argumentsJson
        val maxAttempts = if (tool.idempotent) IDEMPOTENT_TOOL_MAX_ATTEMPTS else 1

        repeat(maxAttempts) { attemptIndex ->
            val context = ToolExecutionContext(
                operationName = operation.method.name,
                modelName = operation.operation.model,
                attemptNumber = attemptIndex,
                timeout = java.time.Duration.ofMillis(operation.operation.timeoutMillis),
            )

            // Evaluate BEFORE_TOOL_EXECUTION — supports suspension
            val policyDecision = policyHelper.evaluate(
                policyHelper.buildContext(
                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    correlationId = correlationId,
                ).toolName(tool.name)
                    .toolSecurity(tool.security)
                    .applySecurityContext(securityContext)
                    .build()
            )

            if (policyDecision is dev.tramai.core.policy.PolicyDecision.RequireApproval) {
                // Fix 5: Nested approval not supported during resume
                if (resumingApproval) {
                    throw dev.tramai.core.exception.NestedApprovalNotSupportedException(
                        approvalId = parentApprovalId ?: "unknown",
                        message = "Nested approval not supported in v1: tool '${tool.name}' requires approval during a resumed workflow",
                    )
                }
                // R3: Validate policy-provided approval binding
                val requirement = policyDecision.requirement
                val requiredDigest = dev.tramai.core.approval.Sha256Digest.of(requirement.argumentsDigest)
                require(requirement.toolName == tool.name) {
                    "Approval requirement tool binding mismatch: expected '${tool.name}', got '${requirement.toolName}'"
                }
                val rawDigest = if (toolArgumentsDigester != null) {
                    toolArgumentsDigester.digest(dev.tramai.core.approval.SensitiveToolArguments.of(input))
                } else {
                    throw dev.tramai.core.exception.ConfigurationException(
                        "ToolArgumentsDigester is required for approval binding validation"
                    )
                }
                require(requiredDigest == rawDigest) {
                    "Approval requirement argument binding mismatch"
                }
                require(requirement.timeoutMillis > 0) {
                    "Approval requirement timeout must be positive"
                }
                return suspendToolExecution(
                    tool = tool,
                    toolCall = toolCall,
                    operation = operation,
                    correlationId = correlationId,
                    input = input,
                    identity = identity,
                    toolCallIndex = toolCallIndex,
                    messages = messages,
                    argumentsDigest = rawDigest,
                    timeoutMillis = policyDecision.requirement.timeoutMillis,
                    securityContext = securityContext,
                    tokenBudgetTracker = tokenBudgetTracker,
                    conversationId = conversationId,
                    historySize = historySize,
                )
            }
            // Deny also handled by enforce() but we already evaluated above
            if (policyDecision is dev.tramai.core.policy.PolicyDecision.Deny) {
                throw dev.tramai.core.exception.PolicyViolationException(policyDecision)
            }

            val result = try {
                tool.execute(input, context)
            } catch (e: dev.tramai.core.exception.ToolInvalidInputException) {
                ToolResult.InvalidInput(e.message ?: "Invalid tool input")
            } catch (e: Exception) {
                if (tool.idempotent) {
                    ToolResult.TransientFailure(e)
                } else {
                    ToolResult.PermanentFailure(e.message ?: "Tool execution failed")
                }
            }

            when (result) {
                is ToolResult.TransientFailure -> {
                    if (attemptIndex < maxAttempts - 1) {
                        return@repeat
                    }
                    return ToolResult.PermanentFailure(
                        result.cause.message ?: "Tool execution failed after $maxAttempts attempt(s)",
                    )
                }
                else -> return result
            }
        }

        error("Tool retry loop exited without returning")
    }

    /**
     * Suspends tool execution by creating an approval challenge, persisting
     * the continuation and suspended invocation, then throwing [ApprovalSuspendedException].
     */
    private suspend fun suspendToolExecution(
        tool: ResolvedTool,
        toolCall: ToolCall,
        operation: OperationDefinition,
        correlationId: String,
        input: String,
        identity: EngineExecutionIdentity,
        toolCallIndex: Int,
        messages: List<Message>,
        argumentsDigest: Sha256Digest,
        timeoutMillis: Long,
        securityContext: ExecutionSecurityContext,
        tokenBudgetTracker: TokenBudgetTracker? = null,
        conversationId: String? = null,
        historySize: Int = 0,
    ): Nothing {
        val approvalGateCoordinator = approvalGateCoordinator
            ?: throw dev.tramai.core.exception.ConfigurationException(
                "ApprovalGateCoordinator is required for tool execution suspension"
            )
        val approvalContinuationStore = approvalContinuationStore
            ?: throw dev.tramai.core.exception.ConfigurationException(
                "ApprovalContinuationStore is required for tool execution suspension"
            )

        val sensitiveArgs = SensitiveToolArguments.of(input)
        val expiresAt = clock.instant().plusMillis(timeoutMillis)

        // Track IDs for compensation (accessible in catch block)
        var createdChallengeId: String? = null
        var createdContinuationVersion: Long = 0L

        // Phase 1: Create approval, continuation, and suspended state
        try {
            // Create approval challenge via coordinator
            val createCommand = CreateApprovalCommand(
                workflowRunId = identity.workflowRunId,
                toolName = tool.name,
                argumentsDigest = argumentsDigest,
                policyVersion = identity.policyVersion,
                workflowDigest = identity.workflowDigest,
                requestedBy = identity.actorId,
                expiresAt = expiresAt,
            )
            val challenge = approvalGateCoordinator.createApproval(createCommand)
            createdChallengeId = challenge.approvalId

            // Persist continuation with sensitive arguments
            val continuation = approvalContinuationStore.create(
                continuation = ApprovalContinuation(
                    approvalId = challenge.approvalId,
                    workflowRunId = identity.workflowRunId,
                    correlationId = correlationId,
                    toolCallId = toolCall.id,
                    toolName = tool.name,
                    argumentsDigest = argumentsDigest,
                    policyVersion = identity.policyVersion,
                    workflowDigest = identity.workflowDigest,
                    status = dev.tramai.core.approval.ApprovalContinuationStatus.PENDING,
                    createdAt = clock.instant(),
                    approvalExpiresAt = challenge.expiresAt,
                    claimedBy = null,
                    claimedAt = null,
                    completedAt = null,
                    version = 0L,
                ),
                arguments = sensitiveArgs,
            )
            createdContinuationVersion = continuation.version

            // Fix 7: Capture token budget snapshot if available
            val budgetSnapshot = tokenBudgetTracker?.snapshot()

            // Store safe invocation metadata with separated sensitive context
            // historySize is passed in from the caller (computed at the initial suspension point)

            suspendedInvocationStore.create(
                metadata = SuspendedInvocationMetadata(
                    approvalId = challenge.approvalId,
                    toolCallId = toolCall.id,
                    toolName = tool.name,
                    toolCallIndex = toolCallIndex,
                    correlationId = correlationId,
                    identity = identity,
                    securityContext = securityContext,
                    conversationId = conversationId,
                    historySize = historySize,
                    tokenBudgetSnapshot = budgetSnapshot,
                    toolSecurity = tool.security,
                ),
                sensitiveContext = SensitiveResumeContext.of(
                    operation = operation,
                    tool = tool,
                    messages = messages,
                    toolCall = toolCall,
                ),
            )

            // Emit audit event
            approvalLifecycleAuditEmitter.onToolExecutionSuspended(
                approvalId = challenge.approvalId,
                workflowRunId = identity.workflowRunId,
                toolName = tool.name,
                toolCallId = toolCall.id,
                correlationId = correlationId,
                argumentsDigest = argumentsDigest,
                expiresAt = challenge.expiresAt,
            )

            throw ApprovalSuspendedException(
                challenge = challenge,
                approvalId = challenge.approvalId,
                workflowRunId = identity.workflowRunId,
                toolCallId = toolCall.id,
                toolName = tool.name,
                continuationVersion = continuation.version,
            )
        } catch (failure: Exception) {
            // Do NOT compensate for successful suspension — ApprovalSuspendedException is the intended result
            if (failure is ApprovalSuspendedException) throw failure

            // Phase 2: Full saga compensation in reverse order
            val approvalId = createdChallengeId
            if (approvalId != null) {
                // 1. Remove suspended state
                runCatching { suspendedInvocationStore.remove(approvalId) }
                // 2. Cancel continuation
                runCatching {
                    approvalContinuationStore.cancel(
                        approvalId = approvalId,
                        expectedVersion = createdContinuationVersion,
                    )
                }
                // 3. Cancel approval
                runCatching {
                    approvalGateCoordinator.cancelApproval(
                        approvalId = approvalId,
                        expectedVersion = 0L,
                        reason = "suspension-compensation",
                    )
                }
            }
            // throw the original failure — don't let compensation mask it
            throw failure
        }
    }

    private suspend fun callProviderOnce(
        providerId: String,
        provider: ModelProvider,
        request: ModelRequest,
        operation: OperationDefinition,
    ): ModelResponse = try {
        val timeoutMillis = request.timeoutMillis ?: operation.operation.timeoutMillis
        // Check capability: does the provider support images?
        if (request.messages.any { it.hasImage() } && !provider.supportsCapability(ProviderCapability.VISION)) {
            throw ProviderCapabilityException(
                providerId = provider.providerId(),
                capability = "VISION",
            )
        }
        withTimeout(timeoutMillis) {
            provider.complete(request)
        }
    } catch (error: Throwable) {
        throw when (error) {
            is TimeoutCancellationException -> TimeoutException(
                message = buildTimeoutMessage(
                    providerId = providerId,
                    operation = operation,
                    timeoutMillis = request.timeoutMillis ?: operation.operation.timeoutMillis,
                ),
                cause = error,
            )

            is ProviderException -> error

            else -> ProviderException(
                message = "Provider $providerId failed while invoking ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}",
                cause = error,
            )
        }
    }

    private fun shouldRetryProviderCall(
        error: Throwable,
        retryIndex: Int,
        maxAttempts: Int,
    ): Boolean {
        if (retryIndex >= maxAttempts - 1) {
            return false
        }

        return when (error) {
            is TimeoutException -> true
            is ProviderException -> error.retryable
            else -> false
        }
    }

    private fun providerRetryDelayMillis(
        retryIndex: Int,
        error: Throwable,
    ): Long = retryDelayPolicy.delayMillis(
        error = error,
        fallbackDelayMillis = minOf(INITIAL_PROVIDER_RETRY_DELAY_MILLIS shl retryIndex, MAX_PROVIDER_RETRY_DELAY_MILLIS),
    )

    private fun buildTimeoutMessage(
        providerId: String,
        operation: OperationDefinition,
        timeoutMillis: Long,
    ): String = "Provider $providerId timed out after ${timeoutMillis}ms while invoking ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}"

    private fun startObservation(
        providerId: String,
        operation: OperationDefinition,
        attempt: Int,
    ): OperationObservation = operationObserver.onCallStarted(
        OperationCallContext(
            serviceInterface = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
            methodName = operation.method.name,
            providerId = providerId,
            requestedModel = operation.operation.model,
            attempt = attempt,
        ),
    )

    private fun shouldFallbackFrom(error: Throwable): Boolean = when (error) {
        is CircuitBreakerOpenException -> true
        is TimeoutException -> true
        is ProviderException -> error.retryable
        else -> false
    }

    private fun retryDelaySource(error: Throwable): String = if (error is ProviderException && error.retryAfterMillis != null) {
        "retry_after"
    } else {
        "backoff"
    }

    private fun enforceTokenBudget(
        tracker: TokenBudgetTracker,
        response: ModelResponse,
        observation: OperationObservation,
        providerId: String,
        modelName: String,
    ) {
        when (val result = tracker.observe(response)) {
            is TokenBudgetCheckResult.Ok -> Unit
            is TokenBudgetCheckResult.UsageUnavailable -> observation.onEngineEvent(
                name = "tramai.token_budget.usage_unavailable",
                attributes = mapOf(
                    ATTR_PROVIDER_ID to providerId,
                    ATTR_EFFECTIVE_MODEL to modelName,
                ),
            )
            is TokenBudgetCheckResult.SoftLimitExceeded -> observation.onEngineEvent(
                name = "tramai.token_budget.soft_limit_exceeded",
                attributes = mapOf(
                    ATTR_PROVIDER_ID to providerId,
                    ATTR_EFFECTIVE_MODEL to modelName,
                    ATTR_LIMIT_TOKENS to result.limitTokens,
                    ATTR_OBSERVED_TOKENS to result.observedTokens,
                    ATTR_SCOPE to "operation",
                ),
            )
            is TokenBudgetCheckResult.HardLimitExceeded -> {
                observation.onEngineEvent(
                    name = "tramai.token_budget.hard_limit_exceeded",
                    attributes = mapOf(
                        ATTR_PROVIDER_ID to providerId,
                        ATTR_EFFECTIVE_MODEL to modelName,
                        ATTR_LIMIT_TOKENS to result.limitTokens,
                        ATTR_OBSERVED_TOKENS to result.observedTokens,
                        ATTR_SCOPE to result.scope,
                    ),
                )
                throw TokenBudgetExceededException(
                    scope = result.scope,
                    limitTokens = result.limitTokens,
                    observedTokens = result.observedTokens,
                    providerId = providerId,
                    modelName = modelName,
                )
            }
        }
    }

    private fun routeSelectedAttributes(
        route: ResolvedProviderRoute,
        routeIndex: Int,
    ): Map<String, Any?> = mapOf(
        ATTR_PROVIDER_ID to route.providerName,
        ATTR_EFFECTIVE_MODEL to route.effectiveModelName,
        ATTR_ROUTE_INDEX to routeIndex,
        ATTR_IS_FALLBACK to (routeIndex > 0),
    )

    /**
     * Internal resume implementation called by [TramaiEngine.resumeApproval].
     *
     * 1. Loads the [SuspendedInvocation] from the store
     * 2. Authorises the resume via [ApprovalGateCoordinator]
     * 3. Enforces [dev.tramai.core.policy.EnforcementPoint.BEFORE_WORKFLOW_RESUME]
     * 4. Claims the continuation and releases raw arguments
     * 5. Executes the suspended tool
     * 6. Completes the continuation
     * 7. Returns the tool result
     */
    /**
     * Internal resume implementation called by [TramaiEngine.resumeApproval].
     *
     * 1. Loads the [SuspendedInvocationMetadata] from the store
     * 2. Authorises the resume via [ApprovalGateCoordinator]
     * 3. Enforces [dev.tramai.core.policy.EnforcementPoint.BEFORE_WORKFLOW_RESUME]
     *    - R4: On Deny: cancel continuation, remove invocation, emit cancellation, throw PolicyViolationException
     *    - R5: On RequireApproval: cancel continuation, remove invocation, emit cancellation, throw ConfigurationException
     * 4. Claims the continuation and releases raw arguments
     * 5. Reveals sensitive resume context
     * 6. Executes the suspended tool
     * 7. Continues the provider loop (BEFORE_TOOL_RESULT_REINJECTION, format, append, process remaining, callProvider)
     * 8. Completes the continuation
     * 9. Returns the typed operation result
     */
    suspend fun resumeApprovalInternal(command: ResumeApprovalCommand): Any? {
        // 1. Load suspended invocation metadata
        val metadata = suspendedInvocationStore.get(command.approvalId)
            ?: throw dev.tramai.core.exception.ApprovalNotFoundException(command.approvalId)

        // 2. Authorise resume via approvalGateCoordinator
        val coordinator = approvalGateCoordinator
            ?: throw dev.tramai.core.exception.ConfigurationException(
                "ApprovalGateCoordinator is required for resume"
            )
        val store = approvalContinuationStore
            ?: throw dev.tramai.core.exception.ConfigurationException(
                "ApprovalContinuationStore is required for resume"
            )

        // Get the continuation to read the arguments digest
        val existingContinuation = store.get(command.approvalId)
            ?: throw dev.tramai.core.exception.ApprovalNotFoundException(command.approvalId)

        val authorization = coordinator.authorizeResume(
            AuthorizeResumeCommand(
                approvalId = command.approvalId,
                expectedVersion = command.approvalExpectedVersion,
                presentedToken = command.presentedToken,
                consumedBy = command.resumedBy,
                workflowRunId = metadata.identity.workflowRunId,
                toolName = metadata.toolName,
                argumentsDigest = existingContinuation.argumentsDigest,
                policyVersion = metadata.identity.policyVersion,
                workflowDigest = metadata.identity.workflowDigest,
            )
        )

        // Use metadata.toolSecurity for BEFORE_WORKFLOW_RESUME policy context (Fix 2)
        // Sensitive context is revealed AFTER claimForExecution, not before

        // 3. Enforce BEFORE_WORKFLOW_RESUME policy with safe metadata only (Fix 2 & 6)
        val resumeDecision = policyHelper.evaluate(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_WORKFLOW_RESUME,
                correlationId = metadata.correlationId,
            ).toolName(metadata.toolName)
                .toolSecurity(metadata.toolSecurity)
                .applySecurityContext(metadata.securityContext)
                .workflowRunId(metadata.identity.workflowRunId)
                .workflowDigest(metadata.identity.workflowDigest.value)
                .actorId(command.resumedBy)
                .build()
        )
        if (resumeDecision is dev.tramai.core.policy.PolicyDecision.Deny) {
            // R4: Denied — close the continuation and remove invocation before throwing
            runCatching {
                store.cancel(
                    approvalId = command.approvalId,
                    expectedVersion = command.continuationExpectedVersion,
                )
            }
            runCatching {
                suspendedInvocationStore.remove(command.approvalId)
            }
            runCatching {
                approvalLifecycleAuditEmitter.onSuspensionCancelled(
                    approvalId = command.approvalId,
                    workflowRunId = metadata.identity.workflowRunId,
                    toolName = metadata.toolName,
                    reason = "workflow-resume-denied: ${resumeDecision.reasonCode}",
                )
            }
            throw dev.tramai.core.exception.PolicyViolationException(resumeDecision)
        }
        if (resumeDecision is dev.tramai.core.policy.PolicyDecision.RequireApproval) {
            // R5: Nested approval not supported — close and throw NestedApprovalNotSupportedException (Fix 5)
            runCatching {
                store.cancel(
                    approvalId = command.approvalId,
                    expectedVersion = command.continuationExpectedVersion,
                )
            }
            runCatching {
                suspendedInvocationStore.remove(command.approvalId)
            }
            runCatching {
                approvalLifecycleAuditEmitter.onSuspensionCancelled(
                    approvalId = command.approvalId,
                    workflowRunId = metadata.identity.workflowRunId,
                    toolName = metadata.toolName,
                    reason = "nested-approval-not-supported",
                )
            }
            throw dev.tramai.core.exception.NestedApprovalNotSupportedException(
                approvalId = command.approvalId,
                message = "Nested approval not supported: use the original approval challenge",
            )
        }

        // Before claim — validate mandatory dependencies
        val digester = requireNotNull(toolArgumentsDigester) {
            "ToolArgumentsDigester is required for payload integrity verification"
        }

        // 4. Claim continuation and release raw arguments
        val claimed = store.claimForExecution(
            approvalId = command.approvalId,
            expectedVersion = command.continuationExpectedVersion,
            claimedBy = command.resumedBy,
        )

        // Fix 4: Universal uncertain-outbound boundary — wrap everything after claim in try/catch
        var uncertainOutcomeEmitted = false
        return try {
            // 5. Reveal sensitive resume context — INSIDE try/catch, AFTER claimForExecution (Fix 1)
            val sensitiveContext = suspendedInvocationStore.revealSensitiveContext(command.approvalId)
                ?: throw dev.tramai.core.exception.ConfigurationException(
                    "Sensitive resume context not found for approvalId '${command.approvalId}'"
                )
            val resumeContext = sensitiveContext.revealForResume()

            // R7: Verify payload integrity after claim — re-digest and compare
            val actualDigest = digester.digest(claimed.arguments)
            val expectedDigest = claimed.continuation.argumentsDigest
            if (actualDigest != expectedDigest) {
                uncertainOutcomeEmitted = true
                approvalLifecycleAuditEmitter.onUncertainOutcome(
                    approvalId = command.approvalId,
                    workflowRunId = metadata.identity.workflowRunId,
                    toolName = metadata.toolName,
                    reason = "payload-integrity-mismatch",
                )
                throw dev.tramai.core.exception.ConfigurationException(
                    "Claimed continuation payload integrity mismatch: digest ${actualDigest.value} != expected ${expectedDigest.value}"
                )
            }

            // Emit resume audit event
            approvalLifecycleAuditEmitter.onToolExecutionResumed(
                approvalId = command.approvalId,
                workflowRunId = metadata.identity.workflowRunId,
                toolName = metadata.toolName,
                resumedBy = command.resumedBy,
            )

            // 6. Execute the suspended tool with the released arguments
            val resumedInput = claimed.arguments.reveal()
            val toolResult = try {
                val context = ToolExecutionContext(
                    operationName = resumeContext.operation.method.name,
                    modelName = resumeContext.operation.operation.model,
                    attemptNumber = 0,
                    timeout = java.time.Duration.ofMillis(resumeContext.operation.operation.timeoutMillis),
                )
                resumeContext.tool.execute(resumedInput, context)
            } catch (e: Exception) {
                uncertainOutcomeEmitted = true
                approvalLifecycleAuditEmitter.onUncertainOutcome(
                    approvalId = command.approvalId,
                    workflowRunId = metadata.identity.workflowRunId,
                    toolName = metadata.toolName,
                    reason = "tool-execution-failed: ${e::class.simpleName ?: "unknown"}",
                )
                throw e
            }

            // 7. Continue the provider loop — enforce BEFORE_TOOL_RESULT_REINJECTION,
            //    format/sanitize the tool result, append to messages, process remaining,
            //    and call the provider for the next turn
            val tokenBudgetTracker = TokenBudgetTracker(tokenBudgetSettings)
            // Restore token budget snapshot if available
            metadata.tokenBudgetSnapshot?.let { snapshot ->
                tokenBudgetTracker.restore(snapshot)
            }
            val securityContext = metadata.securityContext
            val messages = resumeContext.messages.toMutableList()
            val loopResult = continueAfterToolResult(
                operation = resumeContext.operation,
                messages = messages,
                toolResult = toolResult,
                toolCallId = resumeContext.toolCall.id,
                toolCallIndex = metadata.toolCallIndex,
                correlationId = metadata.correlationId,
                securityContext = securityContext,
                identity = metadata.identity,
                tokenBudgetTracker = tokenBudgetTracker,
                suspendedToolName = metadata.toolName,
                approvalId = command.approvalId,
                conversationId = metadata.conversationId,
                historySize = metadata.historySize,
                resumingApproval = true,
            )

            // Finalize result BEFORE completing continuation (handles String, Unit, Structured)
            val result = finalizeResumedOperation(
                operation = resumeContext.operation,
                loopResult = loopResult,
                messages = messages,
                correlationId = metadata.correlationId,
                securityContext = securityContext,
                conversationId = metadata.conversationId,
                historySize = metadata.historySize,
            )

            // 8. Complete continuation (moved AFTER finalization)
            store.complete(
                approvalId = command.approvalId,
                expectedVersion = claimed.continuation.version,
                completedBy = command.resumedBy,
            )

            // Clean up invocation store BEFORE audit (Fix 2: audit completion ordering)
            suspendedInvocationStore.remove(command.approvalId)

            // Emit completion audit event
            approvalLifecycleAuditEmitter.onToolExecutionCompleted(
                approvalId = command.approvalId,
                workflowRunId = metadata.identity.workflowRunId,
                toolName = metadata.toolName,
                completedBy = command.resumedBy,
            )

            result
        } catch (e: dev.tramai.core.exception.NestedApprovalNotSupportedException) {
            // Fix 2: Nested approval — emit uncertain outcome with parent approvalId, leave CLAIMED
            if (!uncertainOutcomeEmitted) {
                uncertainOutcomeEmitted = true
                approvalLifecycleAuditEmitter.onUncertainOutcome(
                    approvalId = e.approvalId,
                    workflowRunId = metadata.identity.workflowRunId,
                    toolName = metadata.toolName,
                    reason = "nested-approval-not-supported",
                )
            }
            throw e
        } catch (e: dev.tramai.core.exception.StructuredOutputException) {
            // ResumeStructuredResult does not emit uncertain-outcome for parse failures —
            // emit it here before rethrowing so the continuation stays CLAIMED and auditable
            if (!uncertainOutcomeEmitted) {
                uncertainOutcomeEmitted = true
                approvalLifecycleAuditEmitter.onUncertainOutcome(
                    approvalId = command.approvalId,
                    workflowRunId = metadata.identity.workflowRunId,
                    toolName = metadata.toolName,
                    reason = "structured-parse-failed: ${e::class.simpleName ?: "unknown"}",
                )
            }
            throw e
        } catch (e: Exception) {
            // Fix 4: Universal uncertain-outcome — any failure after claim leaves continuation CLAIMED
            if (!uncertainOutcomeEmitted) {
                approvalLifecycleAuditEmitter.onUncertainOutcome(
                    approvalId = command.approvalId,
                    workflowRunId = metadata.identity.workflowRunId,
                    toolName = metadata.toolName,
                    reason = "resume-failed: ${e::class.simpleName ?: "unknown"}",
                )
            }
            throw e
        }
    }

    /**
     * Finalizes a resumed operation for all return kinds that don't need
     * structured parsing. Enforces BEFORE_RESPONSE_RETURN, persists conversation
     * memory, completes the observation, and returns the appropriate result.
     *
     * For [ReturnKind.STRUCTURED] callers must handle parsing separately via
     * [resumeStructuredResult] — this method enforces only the shared parts
     * (BEFORE_RESPONSE_RETURN, memory, observation) and then throws so the
     * caller knows to use the structured path instead.
     */
    private suspend fun finalizeResumedOperation(
        operation: OperationDefinition,
        loopResult: ProviderCallResult,
        messages: MutableList<Message>,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        conversationId: String?,
        historySize: Int,
    ): Any? {
        when (operation.returnKind) {
            ReturnKind.STRING -> {
                // Enforce BEFORE_RESPONSE_RETURN (Fix 3: per-return-kind, not before dispatch)
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(loopResult.providerId)
                        .modelName(loopResult.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )
                // Memory persistence + observation + return (once)
                persistMemory(loopResult, messages, historySize, conversationId)
                loopResult.observation.onCallCompleted(parseSuccess = null)
                return loopResult.response.content
            }
            ReturnKind.UNIT -> {
                // Enforce BEFORE_RESPONSE_RETURN (Fix 3: per-return-kind, not before dispatch)
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(loopResult.providerId)
                        .modelName(loopResult.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )
                persistMemory(loopResult, messages, historySize, conversationId)
                loopResult.observation.onCallCompleted(parseSuccess = null)
                loopResult.response.content // consume it
                return Unit
            }
            ReturnKind.STRUCTURED -> {
                // Structured: delegate to resumeStructuredResult which handles
                // BEFORE_RESPONSE_RETURN (on success only), memory, observation, and parse
                return resumeStructuredResult(
                    operation = operation,
                    loopResult = loopResult,
                    messages = messages,
                    correlationId = correlationId,
                    securityContext = securityContext,
                    conversationId = conversationId,
                    historySize = historySize,
                )
            }
            ReturnKind.STREAMING -> throw ConfigurationException("Streaming approval resume not supported")
        }
    }

    /**
     * Parses the structured (typed) result from a resumed provider loop.
     *
     * **Single-attempt limitation (v1):** Unlike the normal flow, which retries structured
     * parsing when the provider responds with content that cannot be parsed — feeding
     * the error back to the provider for a corrected attempt — this resume path makes
     * exactly one parse attempt. If parsing fails, the exception is thrown immediately
     * without a retry cycle. This is a deliberate v1 limitation: the resume path is a
     * linear re-entrant flow, not a multi-turn conversation, so retry-with-feedback
     * semantics are not available here.
     */
    private suspend fun resumeStructuredResult(
        operation: OperationDefinition,
        loopResult: ProviderCallResult,
        messages: MutableList<Message>,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        conversationId: String?,
        historySize: Int,
    ): Any {
        val handler = structuredOutputHandler
            ?: throw ConfigurationException(
                "Structured return type ${operation.returnTypeDescription} requires a StructuredOutputHandler implementation from tramai-structured",
            )
        val targetType = operation.returnType
            ?: throw ConfigurationException(
                "Structured return type ${operation.returnTypeDescription} could not be inspected without Kotlin reflection metadata",
            )

        // Fix 3: Parse FIRST before memory persistence and BEFORE_RESPONSE_RETURN
        val analysis = handler.analyze(
            rawResponse = loopResult.response.content,
            targetType = targetType,
        )
        return when (analysis) {
            is StructuredOutputResult.Success -> {
                // On success: enforce BEFORE_RESPONSE_RETURN, persist memory, complete observation, return value
                // BEFORE_RESPONSE_RETURN is enforced HERE (not in finalizeResumedOperation) per Fix 3
                // so that parse failure does not trip BEFORE_RESPONSE_RETURN
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).providerId(loopResult.providerId)
                        .modelName(loopResult.modelName)
                        .applySecurityContext(securityContext)
                        .build()
                )
                persistMemory(loopResult, messages, historySize, conversationId)
                loopResult.observation.onCallCompleted(parseSuccess = true)
                analysis.value
            }
            is StructuredOutputResult.Failure -> {
                // On failure: record parse failure, do NOT enforce BEFORE_RESPONSE_RETURN,
                // do NOT persist invalid data, leave continuation CLAIMED
                loopResult.observation.onStructuredParseFailure(
                    rawResponse = analysis.rawResponse,
                    errorSummary = analysis.errorSummary,
                )
                loopResult.observation.onCallCompleted(parseSuccess = false)
                throw dev.tramai.core.exception.StructuredOutputException(
                    message = "Structured output parsing failed after resume",
                    originalPrompt = operation.operation.prompt,
                    lastRawResponse = analysis.rawResponse,
                    validationError = analysis.errorSummary,
                    attemptCount = 1,
                )
            }
        }
    }

    /**
     * Continues the provider loop after a suspended tool has been executed on resume.
     *
     * 1. Enforces BEFORE_TOOL_RESULT_REINJECTION policy
     * 2. Formats and sanitizes the tool result message
     * 3. Appends the tool message to the messages list
     * 4. Processes any remaining unprocessed tool calls from the same batch
     * 5. Continues the provider loop via [executeWithTools]
     */
    private suspend fun continueAfterToolResult(
        operation: OperationDefinition,
        messages: MutableList<Message>,
        toolResult: ToolResult,
        toolCallId: String,
        toolCallIndex: Int,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        identity: EngineExecutionIdentity,
        tokenBudgetTracker: TokenBudgetTracker,
        suspendedToolName: String = "",
        approvalId: String = "",
        conversationId: String? = null,
        historySize: Int = 0,
        resumingApproval: Boolean = false,
    ): ProviderCallResult {
        // Tool name is provided by the caller from the stored SuspendedInvocationMetadata.toolName,
        // which is more reliable than searching through messages by toolCallId.
        // The caller (resumeApproval) already has the metadata and passes it through.

        // 1. Enforce BEFORE_TOOL_RESULT_REINJECTION
        val resolvedTool = toolRegistry.resolve(suspendedToolName)
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION,
                correlationId = correlationId,
            ).toolName(suspendedToolName)
                .toolSecurity(resolvedTool?.security)
                .applySecurityContext(securityContext)
                .build()
        )

        // 2. Format and sanitize the tool result
        val toolMessage = formatToolResult(toolResult, toolCallId)
        val sanitizedMessage = sanitizeToolMessageForReinjection(
            message = toolMessage,
            operation = operation,
            toolName = suspendedToolName,
            correlationId = correlationId,
            securityContext = securityContext,
            engineEventObserver = engineEventObserver,
        )

        // 3. Append the tool message to messages
        messages += sanitizedMessage

        // 4. Process any remaining unprocessed tool calls from the same batch
        if (toolCallIndex >= 0) {
            val allToolCalls = messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.toolCalls != null }?.toolCalls ?: emptyList()
            val remainingToolCalls = allToolCalls.drop(toolCallIndex + 1)
            for ((remainingIdx, tc) in remainingToolCalls.withIndex()) {
                val actualIndex = toolCallIndex + 1 + remainingIdx
                val t = toolRegistry.resolve(tc.name)
                val tr = if (t == null) {
                    ToolResult.PermanentFailure("Tool '<unregistered>' not found")
                } else {
                    try {
                        executeTool(t, tc, operation, correlationId, securityContext, identity, messages, actualIndex, tokenBudgetTracker, conversationId, historySize, resumingApproval, parentApprovalId = approvalId)
                    } catch (e: dev.tramai.core.exception.NestedApprovalNotSupportedException) {
                        // Fix 5: Nested approval not supported during resume — fail closed
                        approvalLifecycleAuditEmitter.onUncertainOutcome(
                            approvalId = approvalId,
                            workflowRunId = identity.workflowRunId,
                            toolName = t.name,
                            reason = "nested-approval-not-supported: sibling tool ${tc.name} requires approval",
                        )
                        throw dev.tramai.core.exception.NestedApprovalNotSupportedException(
                            approvalId = approvalId,
                            message = "Nested approval not supported in v1: sibling tool ${tc.name} requires approval",
                        )
                    } catch (e: ApprovalSuspendedException) {
                        // Fix 8: Nested approval not supported in v1 — fail closed (backward compat for non-resume path)
                        approvalLifecycleAuditEmitter.onUncertainOutcome(
                            approvalId = approvalId,
                            workflowRunId = identity.workflowRunId,
                            toolName = t.name,
                            reason = "nested-approval-not-supported: sibling tool ${tc.name} requires approval",
                        )
                        throw ConfigurationException("Nested approval not supported in v1: sibling tool ${tc.name} requires approval")
                    }
                }
                // Enforce BEFORE_TOOL_RESULT_REINJECTION for each remaining tool
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION,
                        correlationId = correlationId,
                    ).toolName(t?.name ?: "<unregistered>")
                        .toolSecurity(t?.security)
                        .applySecurityContext(securityContext)
                        .build()
                )
                val msg = formatToolResult(tr, tc.id)
                messages += sanitizeToolMessageForReinjection(
                    message = msg,
                    operation = operation,
                    toolName = tc.name,
                    correlationId = correlationId,
                    securityContext = securityContext,
                    engineEventObserver = engineEventObserver,
                )
            }
        }

        // 5. Continue the provider loop
        return executeWithTools(
            operation = operation,
            messages = messages,
            tokenBudgetTracker = tokenBudgetTracker,
            correlationId = correlationId,
            securityContext = securityContext,
            identity = identity,
            conversationId = conversationId,
            historySize = historySize,
            resumingApproval = resumingApproval,
            parentApprovalId = approvalId,
        )
    }

    private fun handleObjectMethod(
        proxy: Any,
        method: Method,
        args: Array<out Any?>,
    ): Any? = when (method.name) {
        "toString" -> "TramaiProxy(${serviceDefinition.serviceType.qualifiedName})"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args.firstOrNull()
        else -> throw UnsupportedOperationException("Unsupported Object method: ${method.name}")
    }

    private fun resolveConversationId(method: Method, args: Array<out Any?>): String {
        val parameters = method.parameters
        for (i in parameters.indices) {
            if (parameters[i].isAnnotationPresent(ConversationId::class.java)) {
                return args[i]?.toString() ?: throw IllegalArgumentException(
                    "@ConversationId parameter '${parameters[i].name}' at index $i is null"
                )
            }
        }
        return conversationIdProvider.resolve()
    }

    private suspend fun authorizeCachedResult(
        cacheKey: OperationCacheKey,
        cached: CachedOperationResult,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        validateCachedEntry(cacheKey, cached)
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_RESOLUTION,
                correlationId = correlationId,
            ).modelName(cacheKey.requestedModel)
                .applySecurityContext(securityContext)
                .attribute("cacheReuse", "true")
                .build()
        )
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                correlationId = correlationId,
            ).providerId(cached.provenance.providerId)
                .modelName(cached.provenance.modelName)
                .applySecurityContext(securityContext)
                .attribute("cacheReuse", "true")
                .build()
        )
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = correlationId,
            ).providerId(cached.provenance.providerId)
                .modelName(cached.provenance.modelName)
                .applySecurityContext(securityContext)
                .attribute("cacheReuse", "true")
                .build()
        )
    }

    private fun validateCachedEntry(
        key: OperationCacheKey,
        cached: CachedOperationResult,
    ) {
        val provenance = cached.provenance
        if (provenance.providerId.isBlank() ||
            provenance.modelName.isBlank() ||
            provenance.dataClassification != key.securityPartition.dataClassification ||
            provenance.classificationSource != key.securityPartition.classificationSource
        ) {
            throw IllegalStateException(
                "Cached entry envelope mismatch: key partition " +
                    "${key.securityPartition} != cached provenance partition " +
                    "(${provenance.dataClassification}, ${provenance.classificationSource})",
            )
        }
    }

    private fun OperationDefinition.cachedValue(
        key: OperationCacheKey,
        conversationId: String?,
    ): CachedOperationResult? = if (isSafeCacheEligible(this, conversationId)) {
        responseCache.get(key)
    } else {
        null
    }

    private fun OperationDefinition.cacheValue(
        key: OperationCacheKey,
        value: Any,
        providerId: String,
        modelName: String,
        securityContext: ExecutionSecurityContext,
        conversationId: String?,
    ) {
        if (!isSafeCacheEligible(this, conversationId)) {
            return
        }
        responseCache.put(
            key = key,
            value = CachedOperationResult(
                value = value,
                provenance = CachedResponseProvenance(
                    providerId = providerId,
                    modelName = modelName,
                    dataClassification = securityContext.dataClassification,
                    classificationSource = securityContext.classificationSource,
                ),
            ),
            ttlMillis = operation.cacheTtlMillis,
        )
    }

    /**
     * Cache eligibility including the conversation-memory and custom-interceptor
     * gates. Both gates are engine-scoped, not operation-scoped, so they live
     * on the handler.
     *
     * - **No chat memory** in scope: a cache hit would skip the
     *   `chatMemory.add(...)` that a fresh execution performs.
     * - **No custom interceptor**: a cache hit would skip
     *   `operationInterceptor.interceptRequest(...)` and
     *   `operationInterceptor.interceptResponse(...)`, allowing stale
     *   redacted/audited responses to bypass current rules.
     *
     * Interceptor-aware caching is deferred to a follow-up that introduces a
     * dedicated cache-aware interceptor SPI.
     */
    private fun isSafeCacheEligible(
        operation: OperationDefinition,
        conversationId: String?,
    ): Boolean =
        operation.isOperationCacheEligible() &&
            conversationId == null &&
            operationInterceptor === NoOpOperationInterceptor &&
            dlpInterceptor === NoOpDlpInterceptor
}

internal data class ServiceDefinition(
    val serviceType: KClass<*>,
    val systemPrompt: String?,
    val operations: Map<Method, OperationDefinition>,
) {
    companion object {
        fun create(
            serviceType: KClass<*>,
            toolRegistry: ToolRegistry,
            promptSanitizer: PromptSanitizer?,
        ): ServiceDefinition {
            val javaType = serviceType.java
            if (!javaType.isInterface) {
                throw ConfigurationException("${javaType.name} must be an interface")
            }
            if (!javaType.isAnnotationPresent(AiService::class.java)) {
                throw ConfigurationException("${javaType.name} must be annotated with @AiService")
            }

            val systemPrompt = serviceType.java.getAnnotation(SystemPrompt::class.java)?.value?.takeIf { it.isNotBlank() }
            val operations = javaType.methods
                .filterNot { it.declaringClass == Any::class.java }
                .associateWith { method ->
                    val operation = method.getAnnotation(Operation::class.java)
                        ?: throw ConfigurationException("${javaType.name}.${method.name} must be annotated with @Operation")

                    val toolDefinitions = operation.tools.map { toolName ->
                        val tool = toolRegistry.resolve(toolName)
                            ?: throw ConfigurationException("Tool '$toolName' requested by ${method.name} is not registered in the engine")
                        ToolDefinition(tool.name, tool.description, tool.inputSchemaJson)
                    }

                    val systemAnnotations = method.getAnnotationsByType(SystemMessage::class.java).map { it.value }
                    val userAnnotations = method.getAnnotationsByType(UserMessage::class.java).map { it.value }

                    OperationDefinition.create(
                        method = method,
                        operation = operation,
                        classLevelSystemPrompt = systemPrompt,
                        systemAnnotations = systemAnnotations,
                        userAnnotations = userAnnotations,
                        toolDefinitions = toolDefinitions,
                        promptSanitizer = promptSanitizer,
                    )
                }

            return ServiceDefinition(
                serviceType = serviceType,
                systemPrompt = systemPrompt,
                operations = operations,
            )
        }
    }
}

data class OperationDefinition(
    val method: Method,
    val operation: Operation,
    val classLevelSystemPrompt: String?,
    val systemAnnotations: List<String>,
    val userAnnotations: List<String>,
    val isSuspend: Boolean,
    val parameterNames: List<String>,
    val returnKind: ReturnKind,
    val returnType: kotlin.reflect.KType?,
    val returnTypeDescription: String,
    val toolDefinitions: List<ToolDefinition>,
    val promptSanitizer: PromptSanitizer?,
) {
    /**
     * Operation-static cache eligibility (no chat memory, no tools, no streaming,
     * no custom [dev.tramai.core.observation.OperationInterceptor]).
     *
     * The interceptor-aware portion of the check lives on the invocation handler
     * because the interceptor is engine-scoped, not operation-scoped. Use the
     * handler's [isSafeCacheEligible] when evaluating an actual cache read/write.
     */
    fun isOperationCacheEligible(): Boolean =
        operation.cacheable &&
            returnKind != ReturnKind.STREAMING &&
            toolDefinitions.isEmpty()

    /**
     * The effective system message, resolved by precedence:
     * 1. Method-level @System annotations (concatenated)
     * 2. Class-level @SystemPrompt
     * 3. null (engine will construct a default)
     */
    val effectiveSystemMessage: String? get() {
        if (systemAnnotations.isNotEmpty()) {
            return systemAnnotations.joinToString("\n")
        }
        return classLevelSystemPrompt?.takeIf { it.isNotBlank() }
    }

    /**
     * Whether multi-message annotations (@System / @User) are present.
     * When true, [initialMessages] builds messages from annotations instead of [Operation.prompt].
     */
    val hasMultiMessageAnnotations: Boolean get() =
        systemAnnotations.isNotEmpty() || userAnnotations.isNotEmpty()

    private fun sanitizedArgumentValues(arguments: List<Any?>): List<String> = arguments.map { argument ->
        val rendered = when (argument) {
            is ClassifiedDocument<*> -> argument.payload?.toString() ?: ""
            else -> argument?.toString() ?: ""
        }
        promptSanitizer?.sanitize(rendered) ?: rendered
    }

    fun toRequest(
        arguments: List<Any?>,
        modelName: String = operation.model,
    ): ModelRequest {
        return ModelRequest(
            model = modelName,
            messages = initialMessages(arguments),
            tools = toolDefinitions.takeIf { it.isNotEmpty() },
            timeoutMillis = operation.timeoutMillis,
            operationInterface = method.declaringClass.name,
            operationMethod = method.name,
        )
    }

    fun initialMessages(
        arguments: List<Any?>,
        schemaJson: String? = null,
    ): List<Message> {
        val sanitizedArguments = sanitizedArgumentValues(arguments)
        return if (hasMultiMessageAnnotations) {
            buildMessagesFromAnnotations(sanitizedArguments, schemaJson)
        } else {
            buildMessagesFromPrompt(sanitizedArguments, schemaJson)
        }
    }

    private fun buildMessagesFromAnnotations(
        arguments: List<String>,
        schemaJson: String?,
    ): List<Message> {
        val messages = mutableListOf<Message>()

        // 1. System messages (method-level @System or class-level @SystemPrompt)
        val system = effectiveSystemMessage
        if (!system.isNullOrBlank()) {
            messages.add(Message(role = MessageRole.SYSTEM, content = interpolate(system, arguments)))
        } else {
            // Default system message
            messages.add(Message(
                role = MessageRole.SYSTEM,
                content = defaultSystemMessage(),
            ))
        }

        // 2. User messages from @User annotations
        if (userAnnotations.isNotEmpty()) {
            for (template in userAnnotations) {
                val content = interpolate(template, arguments)
                messages.add(Message(role = MessageRole.USER, content = content))
            }
        } else if (operation.prompt.isNotBlank()) {
            // @User absent but @Operation.prompt present → use prompt as single user message
            val content = interpolate(operation.prompt, arguments)
            messages.add(Message(role = MessageRole.USER, content = content))
        } else {
            // Neither @User nor prompt → construct default user message
            messages.add(Message(
                role = MessageRole.USER,
                content = "Execute the operation ${method.name} with the provided parameters.",
            ))
        }

        // Append schema constraint to the last user message
        if (!schemaJson.isNullOrBlank()) {
            val lastUserIndex = messages.indexOfLast { it.role == MessageRole.USER }
            if (lastUserIndex >= 0) {
                val last = messages[lastUserIndex]
                messages[lastUserIndex] = last.copy(
                    content = last.content + "\n\nRespond only with valid JSON matching this schema:\n$schemaJson",
                )
            }
        }

        return messages
    }

    private fun buildMessagesFromPrompt(
        arguments: List<String>,
        schemaJson: String?,
    ): List<Message> {
        val messages = buildList {
            // Class-level @SystemPrompt still applies for backward compat
            if (!classLevelSystemPrompt.isNullOrBlank()) {
                add(Message(role = MessageRole.SYSTEM, content = classLevelSystemPrompt))
            }

            val userMessage = buildString {
                append(operation.prompt)
                if (!schemaJson.isNullOrBlank()) {
                    append("\n\nRespond only with valid JSON matching this schema:\n")
                    append(schemaJson)
                }
                if (arguments.isNotEmpty()) {
                    append("\n\nArguments:")
                    arguments.forEachIndexed { index, value ->
                        append("\n- ")
                        append(parameterNames.getOrElse(index) { "arg$index" })
                        append(": ")
                        append(value)
                    }
                }
            }
            add(Message(role = MessageRole.USER, content = userMessage))
        }

        return messages
    }

    private fun interpolate(template: String, arguments: List<String>): String {
        var result = template
        arguments.forEachIndexed { index, value ->
            val name = parameterNames.getOrElse(index) { "arg$index" }
            result = result.replace("{$name}", value)
        }
        return result
    }

    private fun defaultSystemMessage(): String = buildString {
        append("You are an AI assistant implementing the \"")
        append(method.declaringClass.simpleName)
        append("\" service.\nMethod: ")
        append(method.name)
        append("(")
        append(parameterNames.joinToString(", "))
        append(")\nReturn type: ")
        append(returnTypeDescription)
    }

    fun cacheKey(
        arguments: List<Any?>,
        schemaJson: String? = null,
    ): OperationCacheKey = buildCacheKey(
        digestSource = initialMessages(arguments, schemaJson),
        securityPartition = ExecutionSecurityContext.fromArguments(arguments.toTypedArray()).toCacheSecurityPartition(),
    )

    internal fun buildCacheKey(
        digestSource: List<Message>,
        securityPartition: CacheSecurityPartition,
    ): OperationCacheKey = OperationCacheKey(
        serviceInterface = method.declaringClass.name,
        methodName = method.name,
        requestedModel = operation.model,
        explicitProvider = operation.provider.takeIf { it.isNotBlank() },
        requestDigest = sha256Hex(canonicalizeMessages(digestSource)),
        operationFingerprint = operationFingerprint(),
        securityPartition = securityPartition,
    )

    private fun operationFingerprint(): String {
        val canonical = buildString {
            append("tools_count=").append(toolDefinitions.size).append('\n')
            toolDefinitions.forEachIndexed { index, tool ->
                append("tool_").append(index).append("_name_len=").append(tool.name.length).append('\n')
                append(tool.name).append('\n')
                append("tool_").append(index).append("_schema_len=").append(tool.inputSchemaJson.length).append('\n')
                append(tool.inputSchemaJson).append('\n')
            }
            append("timeout_millis=").append(operation.timeoutMillis).append('\n')
            append("cacheable=").append(operation.cacheable).append('\n')
            append("cache_ttl_millis=").append(operation.cacheTtlMillis).append('\n')
        }
        return sha256Hex(canonical)
    }

    fun structuredContract(handler: StructuredOutputHandler) = handler.createContract(
        requireNotNull(returnType) {
            "Structured return type $returnTypeDescription could not be inspected without Kotlin reflection metadata"
        },
    )

    companion object {
        fun create(
            method: Method,
            operation: Operation,
            classLevelSystemPrompt: String?,
            systemAnnotations: List<String> = emptyList(),
            userAnnotations: List<String> = emptyList(),
            toolDefinitions: List<ToolDefinition> = emptyList(),
            promptSanitizer: PromptSanitizer? = null,
        ): OperationDefinition {
            require(operation.maxRetries >= 0) {
                "@Operation(maxRetries) must be zero or greater for ${method.declaringClass.name}.${method.name}"
            }
            require(operation.providerRetries >= 0) {
                "@Operation(providerRetries) must be zero or greater for ${method.declaringClass.name}.${method.name}"
            }
            require(operation.timeoutMillis > 0) {
                "@Operation(timeoutMillis) must be greater than zero for ${method.declaringClass.name}.${method.name}"
            }
            require(!operation.cacheable || operation.cacheTtlMillis > 0) {
                "@Operation(cacheTtlMillis) must be greater than zero when caching is enabled for ${method.declaringClass.name}.${method.name}"
            }

            // Warn if both @System (method) and @SystemPrompt (class) are present
            if (systemAnnotations.isNotEmpty() && !classLevelSystemPrompt.isNullOrBlank()) {
                val logger = System.getLogger("dev.tramai.engine.OperationDefinition")
                logger.log(System.Logger.Level.WARNING,
                    "@System on ${method.declaringClass.name}.${method.name} takes precedence over @SystemPrompt on the class")
            }

            val kotlinFunction = runCatching { method.kotlinFunction }.getOrNull()
            val isSuspend = kotlinFunction?.isSuspend ?: method.isSuspendSignature()
            val parameterNames = resolveParameterNames(method, kotlinFunction)
            val returnType = resolveReturnType(kotlinFunction)
            val returnKind = resolveReturnKind(method, isSuspend, returnType)
            val returnTypeDescription = resolveReturnTypeDescription(method, returnType)

            return OperationDefinition(
                method = method,
                operation = operation,
                classLevelSystemPrompt = classLevelSystemPrompt,
                systemAnnotations = systemAnnotations,
                userAnnotations = userAnnotations,
                isSuspend = isSuspend,
                parameterNames = parameterNames,
                returnKind = returnKind,
                returnType = returnType,
                returnTypeDescription = returnTypeDescription,
                toolDefinitions = toolDefinitions,
                promptSanitizer = promptSanitizer,
            )
        }

        private fun resolveParameterNames(
            method: Method,
            kotlinFunction: KFunction<*>?,
        ): List<String> {
            val valueParameters = kotlinFunction?.parameters
                ?.filter { it.kind == KParameter.Kind.VALUE }
                ?.map { it.name ?: "arg${it.index}" }
            if (valueParameters != null) {
                return valueParameters
            }

            return method.parameters.mapIndexed { index, parameter ->
                parameter.name?.takeIf { it.isNotBlank() } ?: "arg$index"
            }
        }

        private fun resolveReturnKind(
            method: Method,
            isSuspend: Boolean,
            returnType: kotlin.reflect.KType?,
        ): ReturnKind {
            val classifier = returnType?.classifier
            return when (classifier) {
                String::class -> ReturnKind.STRING
                Unit::class -> ReturnKind.UNIT
                kotlinx.coroutines.flow.Flow::class -> ReturnKind.STREAMING
                null -> when {
                    isSuspend -> throw ConfigurationException(
                        "Suspend method ${method.declaringClass.name}.${method.name} requires Kotlin reflection metadata to inspect its return type",
                    )
                    method.returnType == String::class.java -> ReturnKind.STRING
                    method.returnType == Void.TYPE -> ReturnKind.UNIT
                    kotlinx.coroutines.flow.Flow::class.java.isAssignableFrom(method.returnType) -> ReturnKind.STREAMING
                    else -> ReturnKind.STRUCTURED
                }
                else -> ReturnKind.STRUCTURED
            }
        }

        private fun resolveReturnType(
            kotlinFunction: KFunction<*>?,
        ) = kotlinFunction?.returnType

        private fun resolveReturnTypeDescription(
            method: Method,
            returnType: kotlin.reflect.KType?,
        ): String = returnType?.toString() ?: method.genericReturnType.typeName

        private fun Method.isSuspendSignature(): Boolean =
            parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"
    }
}

internal fun buildOperationCacheKeyForTesting(
    serviceType: KClass<*>,
    methodName: String,
    arguments: List<Any?>,
    schemaJson: String? = null,
    promptSanitizer: PromptSanitizer? = null,
    toolRegistry: ToolRegistry = ToolRegistry(),
): OperationCacheKey {
    val definition = ServiceDefinition.create(
        serviceType = serviceType,
        toolRegistry = toolRegistry,
        promptSanitizer = promptSanitizer,
    )
    val method = serviceType.java.methods.firstOrNull { it.name == methodName }
        ?: throw IllegalArgumentException("No method named '$methodName' on ${serviceType.java.name}")
    val operation = definition.operations[method]
        ?: throw IllegalArgumentException("No operation metadata for ${serviceType.java.name}.$methodName")
    return operation.buildCacheKey(
        digestSource = operation.initialMessages(arguments, schemaJson),
        securityPartition = ExecutionSecurityContext.fromArguments(arguments.toTypedArray()).toCacheSecurityPartition(),
    )
}

private data class ProviderCallResult(
    val response: ModelResponse,
    val observation: OperationObservation,
    val providerId: String,
    val modelName: String,
)

private sealed class StreamingRouteResult {
    data class Completed(
        val fullText: String,
    ) : StreamingRouteResult()

    data class StartupFailure(
        val error: TramaiException,
    ) : StreamingRouteResult()

    data class TerminalError(
        val errorChunk: StreamChunk.Error,
    ) : StreamingRouteResult()
}

private class StreamingRouteFinished(
    val result: StreamingRouteResult,
) : RuntimeException(null, null, false, false)

private class AttemptCounter {
    private var attempt = 0

    fun next(): Int = attempt++
}

internal class ProviderCircuitBreaker(
    private val settings: CircuitBreakerSettings,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val states = mutableMapOf<String, ProviderCircuitState>()

    @Synchronized
    fun beforeCall(providerId: String): Long? {
        if (!settings.enabled) {
            return null
        }

        val state = states[providerId] ?: return null
        val now = clockMillis()
        val openUntil = state.openUntilMillis ?: return null
        if (now < openUntil) {
            return openUntil
        }

        state.openUntilMillis = null
        state.consecutiveFailures = 0
        return null
    }

    @Synchronized
    fun openUntilMillis(providerId: String): Long? {
        if (!settings.enabled) {
            return null
        }

        val state = states[providerId] ?: return null
        val openUntil = state.openUntilMillis ?: return null
        return if (clockMillis() < openUntil) openUntil else null
    }

    @Synchronized
    fun onSuccess(providerId: String) {
        if (!settings.enabled) {
            return
        }

        states.remove(providerId)
    }

    @Synchronized
    fun onFailure(
        providerId: String,
        error: Throwable,
    ): Boolean {
        if (!settings.enabled || !isCircuitBreakingFailure(error)) {
            return false
        }

        val state = states.getOrPut(providerId) { ProviderCircuitState() }
        state.consecutiveFailures += 1
        if (state.consecutiveFailures >= settings.failureThreshold) {
            state.consecutiveFailures = 0
            state.openUntilMillis = clockMillis() + settings.openDurationMillis
            return true
        }
        return false
    }

    private fun isCircuitBreakingFailure(error: Throwable): Boolean = when (error) {
        is TimeoutException -> true
        is ProviderException -> error.retryable
        else -> false
    }
}

private data class ProviderCircuitState(
    var consecutiveFailures: Int = 0,
    var openUntilMillis: Long? = null,
)

internal class ProviderRetryDelayPolicy(
    private val settings: RetryPolicySettings,
    private val randomDouble: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    fun delayMillis(
        error: Throwable,
        fallbackDelayMillis: Long,
    ): Long {
        val cappedBaseDelay = when (error) {
            is ProviderException -> {
                val retryAfterMillis = error.retryAfterMillis
                if (retryAfterMillis != null) {
                    minOf(retryAfterMillis, settings.maxRetryAfterMillis)
                } else {
                    fallbackDelayMillis
                }
            }
            else -> fallbackDelayMillis
        }

        val jitter = (cappedBaseDelay * settings.jitterRatio * randomDouble()).toLong()
        return cappedBaseDelay + jitter
    }
}

private class TokenBudgetTracker(
    private val settings: TokenBudgetSettings,
) {
    private var totalTokensObserved: Long = 0
    private var softLimitReported: Boolean = false

    /**
     * Capture a snapshot of the current budget state for suspension.
     */
    fun snapshot(): TokenBudgetSnapshot = TokenBudgetSnapshot(
        totalInputTokens = totalTokensObserved,
        totalOutputTokens = 0L,
        totalInputCost = 0.0,
        totalOutputCost = 0.0,
    )

    /**
     * Restore budget state from a snapshot taken at suspension time.
     */
    fun restore(snapshot: TokenBudgetSnapshot) {
        totalTokensObserved = snapshot.totalInputTokens
    }

    fun observe(response: ModelResponse): TokenBudgetCheckResult {
        if (!isEnabled()) {
            return TokenBudgetCheckResult.Ok
        }

        val attemptTokens = response.totalTokens()?.toLong() ?: return TokenBudgetCheckResult.UsageUnavailable
        totalTokensObserved += attemptTokens

        settings.hardMaxTokensPerAttempt?.let { limit ->
            if (attemptTokens > limit) {
                return TokenBudgetCheckResult.HardLimitExceeded(
                    scope = "attempt",
                    limitTokens = limit,
                    observedTokens = attemptTokens,
                )
            }
        }

        settings.hardMaxTokensPerOperation?.let { limit ->
            if (totalTokensObserved > limit) {
                return TokenBudgetCheckResult.HardLimitExceeded(
                    scope = "operation",
                    limitTokens = limit,
                    observedTokens = totalTokensObserved,
                )
            }
        }

        settings.softMaxTokensPerOperation?.let { limit ->
            if (!softLimitReported && totalTokensObserved > limit) {
                softLimitReported = true
                return TokenBudgetCheckResult.SoftLimitExceeded(
                    limitTokens = limit,
                    observedTokens = totalTokensObserved,
                )
            }
        }

        return TokenBudgetCheckResult.Ok
    }

    private fun isEnabled(): Boolean =
        settings.hardMaxTokensPerAttempt != null ||
            settings.hardMaxTokensPerOperation != null ||
            settings.softMaxTokensPerOperation != null
}

private sealed class TokenBudgetCheckResult {
    data object Ok : TokenBudgetCheckResult()

    data object UsageUnavailable : TokenBudgetCheckResult()

    data class SoftLimitExceeded(
        val limitTokens: Long,
        val observedTokens: Long,
    ) : TokenBudgetCheckResult()

    data class HardLimitExceeded(
        val scope: String,
        val limitTokens: Long,
        val observedTokens: Long,
    ) : TokenBudgetCheckResult()
}

enum class ReturnKind {
    STRING,
    UNIT,
    STRUCTURED,
    STREAMING,
}

private fun PolicyContextBuilder.applySecurityContext(
    securityContext: ExecutionSecurityContext,
): PolicyContextBuilder = dataClassification(securityContext.dataClassification)
    .classificationSource(securityContext.classificationSource)

private fun ExecutionSecurityContext.toCacheSecurityPartition() = CacheSecurityPartition(
    dataClassification = dataClassification,
    classificationSource = classificationSource,
)

/** Length-prefixed field encoding with a framed message separator. Adding a field: extend with appendField; never reuse `---` as a content marker. */
private fun canonicalizeMessages(messages: List<Message>): String = buildString {
    messages.forEachIndexed { index, message ->
        if (index > 0) {
            append("\n---\n")
        }
        append("role=")
        append(message.role.name)
        append('\n')
        appendField("content", message.content)
        append("parts_count=").append(message.contentParts?.size ?: 0).append('\n')
        message.contentParts.orEmpty().forEachIndexed { partIndex, part ->
            append("part_index=").append(partIndex).append('\n')
            when (part) {
                is ContentPart.TextPart -> {
                    append("part_type=text\n")
                    appendField("text", part.text)
                }
                is ContentPart.ImagePart -> {
                    append("part_type=image\n")
                    appendField("mime", part.mimeType)
                    appendField("data_b64", Base64.getEncoder().encodeToString(part.data))
                }
                is ContentPart.ImageUrlContent -> {
                    append("part_type=image_url\n")
                    appendField("url", part.url)
                    appendField("mime", part.mimeType)
                }
            }
        }
        if (message.toolCallId != null) {
            appendField("tool_call_id", message.toolCallId)
        }
        message.toolCalls?.let { toolCalls ->
            append("tool_calls_count=").append(toolCalls.size).append('\n')
            toolCalls.forEachIndexed { toolIndex, toolCall ->
                append("tool_call_index=").append(toolIndex).append('\n')
                appendField("tool_call_id", toolCall.id)
                appendField("tool_call_name", toolCall.name)
                appendField("tool_call_args", toolCall.argumentsJson)
            }
        }
    }
}

private fun StringBuilder.appendField(name: String, value: String?) {
    if (value == null) {
        append(name).append("_null\n")
        return
    }
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    append(name).append("_len=").append(bytes.size).append('\n')
    append(value).append('\n')
}

internal fun buildRequestDigest(messages: List<Message>): String = sha256Hex(canonicalizeMessages(messages))

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private const val INITIAL_PROVIDER_RETRY_DELAY_MILLIS = 50L
private const val MAX_PROVIDER_RETRY_DELAY_MILLIS = 1_000L
private const val IDEMPOTENT_TOOL_MAX_ATTEMPTS = 2

private const val ATTR_PROVIDER_ID = "provider_id"
private const val ATTR_EFFECTIVE_MODEL = "effective_model"
private const val ATTR_ROUTE_INDEX = "route_index"
private const val ATTR_IS_FALLBACK = "is_fallback"
private const val ATTR_RETRY_INDEX = "retry_index"
private const val ATTR_DELAY_MILLIS = "delay_millis"
private const val ATTR_DELAY_SOURCE = "delay_source"
private const val ATTR_LIMIT_TOKENS = "limit_tokens"
private const val ATTR_OBSERVED_TOKENS = "observed_tokens"
private const val ATTR_SCOPE = "scope"
private const val ATTR_FAILURE_TYPE = "failure_type"
private const val EVENT_CIRCUIT_OPENED = "tramai.circuit.opened"
private const val EVENT_STARTUP_RETRY = "tramai.streaming.startup_retry"
private const val EVENT_ROUTE_SELECTED = "tramai.route.selected"
