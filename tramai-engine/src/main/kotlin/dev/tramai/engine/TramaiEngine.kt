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
import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.CachedModelProvenanceMismatchException
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
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.ApprovalChallenge
import dev.tramai.core.approval.ApprovalGateCoordinator
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalToken
import dev.tramai.core.approval.CreateApprovalCommand
import dev.tramai.core.approval.AuthorizeResumeCommand
import dev.tramai.core.approval.IdempotencyKeyUtil
import dev.tramai.core.approval.ValidateResumeCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.ToolArgumentsDigester
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalSuspendedException
import dev.tramai.core.exception.ApprovalNotFoundException
import dev.tramai.core.exception.ToolInvalidInputException
import dev.tramai.core.model.*
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
    private val modelRegistry: ModelRegistry = NoOpModelRegistry,
    private val modelRegistrySettings: ModelRegistrySettings = ModelRegistrySettings(),
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
    private val resumeOperationRegistry: ResumeOperationRegistry = ResumeOperationRegistry()

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
        modelRegistry: ModelRegistry = NoOpModelRegistry,
        modelRegistrySettings: ModelRegistrySettings = ModelRegistrySettings(),
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
        modelRegistry = modelRegistry,
        modelRegistrySettings = modelRegistrySettings,
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
            modelRegistry = modelRegistry,
            modelRegistrySettings = modelRegistrySettings,
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
            resumeOperationRegistry = resumeOperationRegistry,
            clock = clock,
        )

        @Suppress("UNCHECKED_CAST")
        return (Proxy.newProxyInstance(
            serviceType.java.classLoader,
            arrayOf(serviceType.java),
            handler,
        ) as T).also {
            resumeOperationRegistry.registerAll(
                serviceDefinition = definition,
                handler = handler,
            )
        }
    }

    /**
     * Registers a service type's operations in the [ResumeOperationRegistry]
     * without creating a Java proxy. After restart, call this before [resumeApproval]
     * to make suspended operations resolvable.
     *
     * Repeated identical registration is idempotent.
     * Conflicting registration (same key, different digest) fails closed.
     */
    fun registerService(serviceType: KClass<*>) {
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
            modelRegistry = modelRegistry,
            modelRegistrySettings = modelRegistrySettings,
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
            resumeOperationRegistry = resumeOperationRegistry,
            clock = clock,
        )
        resumeOperationRegistry.registerAll(
            serviceDefinition = definition,
            handler = handler,
        )
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
        // P1-2: Check continuation status BEFORE loading metadata
        // (post-completion cleanup removes metadata, but continuation is authoritative)
        val store = approvalContinuationStore
            ?: throw dev.tramai.core.exception.ConfigurationException("ApprovalContinuationStore is required for resume")
        val continuationSnapshot = store.get(command.approvalId)
        if (continuationSnapshot != null &&
            continuationSnapshot.status == dev.tramai.core.approval.ApprovalContinuationStatus.COMPLETED
        ) {
            throw dev.tramai.core.exception.ApprovalTokenRejectedException(command.approvalId)
        }

        val metadata = suspendedInvocationStore.get(command.approvalId)
            ?: throw dev.tramai.core.exception.ApprovalNotFoundException(command.approvalId)
        val registered = resumeOperationRegistry.resolve(metadata.operationReference)
        return registered.handler.resumeApprovalInternal(command, metadata, registered)
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
    private val modelRegistry: ModelRegistry,
    private val modelRegistrySettings: ModelRegistrySettings,
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
    private val resumeOperationRegistry: ResumeOperationRegistry,
    private val clock: Clock,
) : InvocationHandler {

    private val policyHelper = PolicyEnforcementHelper(policyEngine, migrationWarningGuard, isLegacyFallback = isLegacyFallback, auditEmitter = policyDecisionAuditEmitter)
    private val modelRegistryEnforcer = ModelRegistryEnforcer(modelRegistry, modelRegistrySettings)

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

    private fun executeStreaming(
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
            enforceBeforeProviderResolution(operation, correlationId, securityContext)
            val candidates = providerRegistry.resolveCandidates(operation.operation)
            var lastFailure: Throwable? = null
            var lastCircuitOpen: CircuitBreakerOpenException? = null
            val attemptCounter = AttemptCounter()

            for ((routeIndex, route) in candidates.withIndex()) {
                val circuitOpen = handleCircuitBreakerOpenRoute(
                    route = route,
                    nextRoute = candidates.getOrNull(routeIndex + 1),
                    correlationId = correlationId,
                    securityContext = securityContext,
                )
                if (circuitOpen != null) {
                    lastCircuitOpen = circuitOpen
                    continue
                }

                when (
                    val result = executeStreamingRoute(
                        StreamingExecutionRoute(
                            operation = operation,
                            route = route,
                            routeIndex = routeIndex,
                            attempt = attemptCounter.next(),
                            tokenBudgetTracker = tokenBudgetTracker,
                            memoryMessages = memoryMessages,
                            historySize = historySize,
                            conversationId = conversationId,
                            emitChunk = { emit(it) },
                        ),
                        correlationId = correlationId,
                        securityContext = securityContext,
                        arguments = arguments,
                    )
                ) {
                    is StreamingRouteResult.Completed -> {
                        if (chatMemory != null && conversationId != null) {
                            val assistantMessage = Message(
                                role = MessageRole.ASSISTANT,
                                content = result.fullText,
                            )
                            val turnMessages = operation.initialMessages(arguments)
                                .drop(historySize)
                                .filter { it.role != MessageRole.SYSTEM }
                            chatMemory.add(conversationId, turnMessages + assistantMessage)
                        }
                        return@flow
                    }
                    is StreamingRouteResult.StartupFailure -> {
                        enforceStreamingFallbackAfterFailure(
                            error = result.error,
                            route = route,
                            nextRoute = candidates.getOrNull(routeIndex + 1),
                            correlationId = correlationId,
                            securityContext = securityContext,
                        )
                        lastFailure = result.error
                    }
                    is StreamingRouteResult.TerminalError -> {
                        emit(result.errorChunk)
                        return@flow
                    }
                }
            }

            emit(noAvailableStreamingRouteChunk(operation, lastFailure, lastCircuitOpen))
        }
    }

    private data class StreamingExecutionRoute(
        val operation: OperationDefinition,
        val route: ResolvedProviderRoute,
        val routeIndex: Int,
        val attempt: Int,
        val tokenBudgetTracker: TokenBudgetTracker,
        val memoryMessages: List<Message>?,
        val historySize: Int,
        val conversationId: String?,
        val emitChunk: suspend (StreamChunk) -> Unit,
    )

    private suspend fun executeStreamingRoute(
        request: StreamingExecutionRoute,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        arguments: List<Any?>,
    ): StreamingRouteResult {
        val route = request.route
        val observation = startStreamingObservation(route, request.operation, request.attempt, request.routeIndex)
        authorizeStreamingRoute(route, observation)
        enforceBeforeResponseReturn(route, correlationId, securityContext)
        enforceToolExposure(request.operation, correlationId, securityContext)
        enforceBeforeProviderInvocation(route.providerName, route.effectiveModelName, correlationId, securityContext)

        val streamCapable = route.provider as? StreamCapable
            ?: throw ProviderCapabilityException(route.providerName, "streaming")
        val modelRequest = request.operation.toRequest(arguments, modelName = route.effectiveModelName)
        val memoryInjectedRequest = request.memoryMessages?.let { modelRequest.copy(messages = it) } ?: modelRequest

        return collectStreamingRoute(
            StreamingRouteCall(
                streamCapable = streamCapable,
                request = memoryInjectedRequest,
                operation = request.operation,
                route = route,
                attempt = request.attempt,
                observation = observation,
                tokenBudgetTracker = request.tokenBudgetTracker,
                emitChunk = request.emitChunk,
            ),
        )
    }

    private suspend fun authorizeStreamingRoute(
        route: ResolvedProviderRoute,
        observation: OperationObservation,
    ) {
        try {
            modelRegistryEnforcer.authorize(route.providerName, route.effectiveModelName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelRegistryException) {
            observation.onCallCompleted(parseSuccess = null)
            throw e
        }
    }

    private fun persistStreamingMemory(
        fullText: String,
        memoryInjectedMessages: List<Message>?,
        historySize: Int,
        conversationId: String?,
    ) {
        if (chatMemory == null || conversationId == null || memoryInjectedMessages == null) {
            return
        }
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = fullText,
        )
        val turnMessages = memoryInjectedMessages
            .drop(historySize)
            .filter { it.role != MessageRole.SYSTEM }
        chatMemory.add(conversationId, turnMessages + assistantMessage)
    }

    private suspend fun enforceBeforeProviderResolution(
        operation: OperationDefinition,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_RESOLUTION,
                correlationId = correlationId,
            ).modelName(operation.operation.model)
                .applySecurityContext(securityContext)
                .build()
        )
    }

    private suspend fun enforceBeforeResponseReturn(
        route: ResolvedProviderRoute,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = correlationId,
            ).providerId(route.providerName)
                .modelName(route.effectiveModelName)
                .applySecurityContext(securityContext)
                .build()
        )
    }

    private suspend fun enforceToolExposure(
        operation: OperationDefinition,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
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
    }

    private suspend fun enforceBeforeProviderInvocation(
        providerId: String,
        modelName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                correlationId = correlationId,
            ).providerId(providerId)
                .modelName(modelName)
                .applySecurityContext(securityContext)
                .build()
        )
    }

    private suspend fun handleCircuitBreakerOpenRoute(
        route: ResolvedProviderRoute,
        nextRoute: ResolvedProviderRoute?,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): CircuitBreakerOpenException? {
        val blockedUntil = circuitBreaker.beforeCall(route.providerName) ?: return null
        val circuitOpen = CircuitBreakerOpenException(route.providerName, blockedUntil)
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
                policyError.addSuppressed(circuitOpen)
                throw policyError
            }
        }
        return circuitOpen
    }

    private suspend fun enforceStreamingFallbackAfterFailure(
        error: Throwable,
        route: ResolvedProviderRoute,
        nextRoute: ResolvedProviderRoute?,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        if (nextRoute == null) return
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
            policyError.addSuppressed(error)
            throw policyError
        }
    }

    private fun noAvailableStreamingRouteChunk(
        operation: OperationDefinition,
        lastFailure: Throwable?,
        lastCircuitOpen: CircuitBreakerOpenException?,
    ): StreamChunk.Error = StreamChunk.Error(
        (lastFailure ?: lastCircuitOpen ?: ProviderException(
            message = "No available streaming provider route for model '${operation.operation.model}'",
            retryable = true,
        )) as TramaiException,
    )

    private suspend fun collectStreamingRoute(
        call: StreamingRouteCall,
    ): StreamingRouteResult {
        val streamCapable = call.streamCapable
        val request = call.request
        val operation = call.operation
        val route = call.route
        val attempt = call.attempt
        val observation = call.observation
        val tokenBudgetTracker = call.tokenBudgetTracker
        val emitChunk = call.emitChunk
        var emittedAnyTokens = false
        val callContext = streamingCallContext(operation, route.providerName, attempt)
        val interceptedRequest = request.copy(
            messages = operationInterceptor.interceptRequest(callContext, request.messages),
        )

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

    private data class StreamingRouteCall(
        val streamCapable: StreamCapable,
        val request: ModelRequest,
        val operation: OperationDefinition,
        val route: ResolvedProviderRoute,
        val attempt: Int,
        val observation: OperationObservation,
        val tokenBudgetTracker: TokenBudgetTracker,
        val emitChunk: suspend (StreamChunk) -> Unit,
    )

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
                try {
                    authorizeCachedResult(
                        cacheKey = key,
                        cached = cached,
                        securityContext = securityContext,
                        correlationId = correlationId,
                    )
                    return cached.value as String
                } catch (_: CachedModelProvenanceMismatchException) {
                    responseCache.invalidate(key)
                }
            }
        }

        val effectiveMutableMessages = effectiveMessages.toMutableList()

        val result = executeWithTools(
            ToolLoopContext(
                operation = operation,
                messages = effectiveMutableMessages,
                tokenBudgetTracker = tokenBudgetTracker,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = effectiveIdentity,
                conversationId = conversationId,
                historySize = history.size,
            ),
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
                operation.cacheValue(
                    key = key,
                    value = it,
                    providerId = result.providerId,
                    modelName = result.modelName,
                    securityContext = securityContext,
                    conversationId = conversationId,
                    approvedModel = result.approvedModel,
                )
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
                try {
                    authorizeCachedResult(
                        cacheKey = key,
                        cached = cached,
                        securityContext = securityContext,
                        correlationId = correlationId,
                    )
                    return cached.value
                } catch (_: CachedModelProvenanceMismatchException) {
                    responseCache.invalidate(key)
                }
            }
        }

        // Re-initialize messages list with history-injected content
        val messages = effectiveMessages.toMutableList()
        val initialTurnCount = history.size

        return executeStructuredRetryLoop(
            StructuredRetryContext(
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
            ),
        )
    }

    private suspend fun executeStructuredRetryLoop(
        context: StructuredRetryContext,
    ): Any {
        val operation = context.operation
        val maxAttempts = operation.operation.maxRetries + 1
        val targetType = requireNotNull(operation.returnType) {
            "Structured return type ${operation.returnTypeDescription} could not be inspected without Kotlin reflection metadata"
        }

        repeat(maxAttempts) { attemptIndex ->
            val value = executeStructuredAttempt(
                StructuredRetryAttemptContext(
                    retry = context,
                    targetType = targetType,
                    attemptIndex = attemptIndex,
                    maxAttempts = maxAttempts,
                ),
            )
            if (value != null) {
                return value
            }
        }

        error("Structured retry loop exited without returning or throwing")
    }

    private data class StructuredRetryContext(
        val operation: OperationDefinition,
        val cacheKey: OperationCacheKey?,
        val handler: StructuredOutputHandler,
        val messages: MutableList<Message>,
        val historySize: Int,
        val tokenBudgetTracker: TokenBudgetTracker,
        val conversationId: String?,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
        val identity: EngineExecutionIdentity,
    )

    private data class StructuredRetryAttemptContext(
        val retry: StructuredRetryContext,
        val targetType: kotlin.reflect.KType,
        val attemptIndex: Int,
        val maxAttempts: Int,
    )

    private suspend fun executeStructuredAttempt(
        context: StructuredRetryAttemptContext,
    ): Any? {
        val operation = context.retry.operation
        val cacheKey = context.retry.cacheKey
        val handler = context.retry.handler
        val messages = context.retry.messages
        val historySize = context.retry.historySize
        val tokenBudgetTracker = context.retry.tokenBudgetTracker
        val conversationId = context.retry.conversationId
        val targetType = context.targetType
        val attemptIndex = context.attemptIndex
        val maxAttempts = context.maxAttempts
        val correlationId = context.retry.correlationId
        val securityContext = context.retry.securityContext
        val identity = context.retry.identity
        val messagesBeforeCall = messages.size
        val result = executeWithTools(
            ToolLoopContext(
                operation = operation,
                messages = messages,
                tokenBudgetTracker = tokenBudgetTracker,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = identity,
                conversationId = conversationId,
                historySize = historySize,
            ),
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
                    operation.cacheValue(
                        key = key,
                        value = analysis.value,
                        providerId = result.providerId,
                        modelName = result.modelName,
                        securityContext = securityContext,
                        conversationId = conversationId,
                        approvedModel = result.approvedModel,
                    )
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
        messages: List<Message>,
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
        context: ToolLoopContext,
    ): ProviderCallResult {
        val operation = context.operation
        val messages = context.messages
        val tokenBudgetTracker = context.tokenBudgetTracker
        val correlationId = context.correlationId
        val securityContext = context.securityContext
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
                ToolCallsContext(
                    loop = context,
                    toolCalls = normalizedToolCalls,
                ),
            )
        }
        error("Exceeded maximum tool call loops ($maxToolLoops)")
    }

    private data class ToolLoopContext(
        val operation: OperationDefinition,
        val messages: MutableList<Message>,
        val tokenBudgetTracker: TokenBudgetTracker,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
        val identity: EngineExecutionIdentity,
        val conversationId: String? = null,
        val historySize: Int = 0,
        val resumingApproval: Boolean = false,
        val parentApprovalId: String? = null,
    )

    private data class ToolCallsContext(
        val loop: ToolLoopContext,
        val toolCalls: List<ToolCall>,
    )

    private suspend fun processToolCalls(
        context: ToolCallsContext,
    ) {
        val operation = context.loop.operation
        val toolCalls = context.toolCalls
        val messages = context.loop.messages
        val correlationId = context.loop.correlationId
        val securityContext = context.loop.securityContext
        val identity = context.loop.identity
        val tokenBudgetTracker = context.loop.tokenBudgetTracker
        val conversationId = context.loop.conversationId
        val historySize = context.loop.historySize
        val resumingApproval = context.loop.resumingApproval
        val parentApprovalId = context.loop.parentApprovalId
        for ((index, toolCall) in toolCalls.withIndex()) {
            val tool = toolRegistry.resolve(toolCall.name)
            val toolResult = if (tool == null) {
                ToolResult.PermanentFailure("Tool '<unregistered>' not found")
            } else {
                executeTool(
                    ToolExecutionRequest(
                        tool = tool,
                        toolCall = toolCall,
                        operation = operation,
                        correlationId = correlationId,
                        securityContext = securityContext,
                        identity = identity,
                        messages = messages,
                        toolCallIndex = index,
                        tokenBudgetTracker = tokenBudgetTracker,
                        conversationId = conversationId,
                        historySize = historySize,
                        resumingApproval = resumingApproval,
                        parentApprovalId = parentApprovalId,
                    ),
                )
            }

            // Enforce BEFORE_TOOL_RESULT_REINJECTION
            policyHelper.enforce(
                policyHelper.buildContext(
                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION,
                    correlationId = correlationId,
                ).toolName(tool?.name ?: UNREGISTERED_LABEL)
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

        val scope = toolReinjectionDlpScope(operation, toolName, correlationId, securityContext, engineEventObserver)
        return sanitizeToolMessageContent(message, scope)
    }

    private data class ToolReinjectionDlpScope(
        val canonicalToolName: String,
        val safeToolLabel: String,
        val dlpContext: DlpContext,
        val aggregateTextLimit: Long,
        val correlationId: String,
        val engineEventObserver: EngineEventObserver,
    )

    private fun toolReinjectionDlpScope(
        operation: OperationDefinition,
        toolName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        engineEventObserver: EngineEventObserver,
    ): ToolReinjectionDlpScope {
        val resolvedTool = toolRegistry.resolve(toolName)
        val canonicalToolName = resolvedTool?.name ?: UNREGISTERED_LABEL
        val safeToolLabel = canonicalToolName.take(MAX_SAFE_TOOL_NAME_LENGTH)
        return ToolReinjectionDlpScope(
            canonicalToolName = canonicalToolName,
            safeToolLabel = safeToolLabel,
            dlpContext = DlpContext(
            contentType = DlpContentType.TOOL_RESULT,
            contentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT,
            operationInterface = operation.method.declaringClass.name,
            operationMethod = operation.method.name,
            toolName = canonicalToolName,
            correlationId = correlationId,
            dataClassification = securityContext.dataClassification,
            classificationSource = securityContext.classificationSource,
            ),
            aggregateTextLimit = toolResultFilteringSettings.maxAggregateTextLengthForTool(toolName),
            correlationId = correlationId,
            engineEventObserver = engineEventObserver,
        )
    }

    private suspend fun sanitizeToolMessageContent(
        message: Message,
        scope: ToolReinjectionDlpScope,
    ): Message {
        val contentParts = message.contentParts
        if (contentParts.isNullOrEmpty()) {
            if (message.content.isEmpty()) {
                return message
            }
            accumulateToolTextLength(scope, 0L, message.content)
            return message.copy(
                content = sanitizeToolText(
                    scope = scope,
                    text = message.content,
                    contentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT,
                    authoritative = true,
                ),
            )
        }

        return sanitizeToolContentParts(message, contentParts, scope)
    }

    private fun emitEngineEventSafely(
        observer: EngineEventObserver,
        name: String,
        attributes: Map<String, Any?>,
    ) {
        try {
            observer.onEngineEvent(name, attributes)
        } catch (error: Exception) {
            System.getLogger("dev.tramai.engine.TramaiEngine")
                .log(System.Logger.Level.WARNING, "Engine event observer failed for '$name': ${error::class.simpleName}")
        }
    }

    private fun rejectAggregateTextLength(scope: ToolReinjectionDlpScope, actualLength: Long): Nothing {
        emitEngineEventSafely(
            observer = scope.engineEventObserver,
            name = DLP_TOOL_REJECTED_METRIC,
            attributes = mapOf(
                "reasonCode" to "aggregate_text_limit_exceeded",
                "aggregateTextLength" to actualLength,
                "configuredLimit" to scope.aggregateTextLimit,
                "correlationId" to scope.correlationId,
                "toolName" to scope.safeToolLabel,
            ),
        )
        throw dev.tramai.core.security.DlpInspectionException(
            message = "Tool result from '${scope.safeToolLabel}' exceeds aggregate input limit ($actualLength > ${scope.aggregateTextLimit})",
        )
    }

    private fun rejectSanitizedTextLimit(scope: ToolReinjectionDlpScope, actualLength: Long): Nothing {
        emitEngineEventSafely(
            observer = scope.engineEventObserver,
            name = DLP_TOOL_REJECTED_METRIC,
            attributes = mapOf(
                "reasonCode" to "sanitized_text_limit_exceeded",
                "aggregateTextLength" to actualLength,
                "configuredLimit" to scope.aggregateTextLimit,
                "correlationId" to scope.correlationId,
                "toolName" to scope.safeToolLabel,
            ),
        )
        throw dev.tramai.core.security.DlpInspectionException(
            message = "Sanitized tool result from '${scope.safeToolLabel}' exceeds aggregate limit ($actualLength > ${scope.aggregateTextLimit})",
        )
    }

    private fun rejectCrossBoundarySensitiveText(scope: ToolReinjectionDlpScope): Nothing {
        emitEngineEventSafely(
            observer = scope.engineEventObserver,
            name = DLP_TOOL_REJECTED_METRIC,
            attributes = mapOf(
                "reasonCode" to "cross_boundary_sensitive_text_detected",
                "correlationId" to scope.correlationId,
                "toolName" to scope.safeToolLabel,
            ),
        )
        throw dev.tramai.core.security.DlpInspectionException(
            message = "Tool result from '${scope.safeToolLabel}' contains sensitive text spanning non-text boundaries",
        )
    }

    private suspend fun sanitizeToolText(
        scope: ToolReinjectionDlpScope,
        text: String,
        contentLocation: DlpContentLocation,
        authoritative: Boolean,
    ): String = try {
        val effectiveContext = scope.dlpContext.copy(contentLocation = contentLocation)
        val result = if (authoritative) {
            inspectDlpAuthoritatively(effectiveContext, text)
        } else {
            inspectDlpForDetectionOnly(effectiveContext, text)
        }
        result.sanitizedText.also { sanitizedText ->
            if (sanitizedText.length.toLong() > scope.aggregateTextLimit) {
                rejectSanitizedTextLimit(scope, sanitizedText.length.toLong())
            }
        }
    } catch (e: DlpInspectionException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emitEngineEventSafely(
            observer = scope.engineEventObserver,
            name = "tramai.dlp.inspection_failed",
            attributes = mapOf("toolName" to scope.safeToolLabel, "correlationId" to scope.correlationId),
        )
        throw dev.tramai.core.security.DlpInspectionException(
            message = "DLP inspection failed for tool result from tool '${scope.safeToolLabel}'",
            cause = e,
        )
    }

    private fun accumulateToolTextLength(
        scope: ToolReinjectionDlpScope,
        currentLength: Long,
        text: String,
    ): Long {
        val nextLength = if (currentLength > Long.MAX_VALUE - text.length.toLong()) {
            Long.MAX_VALUE
        } else {
            currentLength + text.length.toLong()
        }
        if (nextLength > scope.aggregateTextLimit) {
            rejectAggregateTextLength(scope, nextLength)
        }
        return nextLength
    }

    private suspend fun sanitizeToolContentParts(
        message: Message,
        contentParts: List<ContentPart>,
        scope: ToolReinjectionDlpScope,
    ): Message {
        var aggregateLength = 0L
        val sanitizedParts = mutableListOf<ContentPart>()
        val textRun = mutableListOf<String>()
        val sanitizedTextRuns = mutableListOf<String>()
        var sanitizedAggregateLength = 0L

        suspend fun flushTextRun() {
            if (textRun.isEmpty()) {
                return
            }
            val combinedText = buildString {
                textRun.forEach(::append)
            }
            val sanitizedText = sanitizeToolText(
                scope = scope,
                text = combinedText,
                contentLocation = DlpContentLocation.TOOL_MESSAGE_TEXT_RUN,
                authoritative = true,
            )
            sanitizedAggregateLength = accumulateSanitizedToolTextLength(scope, sanitizedAggregateLength, sanitizedText)
            sanitizedTextRuns += sanitizedText
            if (sanitizedText.isNotEmpty()) {
                sanitizedParts += ContentPart.TextPart(sanitizedText)
            }
            textRun.clear()
        }

        contentParts.forEach { part ->
            when (part) {
                is ContentPart.TextPart -> {
                    aggregateLength = accumulateToolTextLength(scope, aggregateLength, part.text)
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
            val projectedResult = sanitizeToolText(
                scope = scope,
                text = projectedText,
                contentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT,
                authoritative = false,
            )
            val individualCombined = buildString {
                sanitizedTextRuns.forEach(::append)
            }
            val combinedResanitized = sanitizeToolText(
                scope = scope,
                text = individualCombined,
                contentLocation = DlpContentLocation.TOOL_MESSAGE_CONTENT,
                authoritative = false,
            )
            if (projectedResult != individualCombined && combinedResanitized != individualCombined) {
                rejectCrossBoundarySensitiveText(scope)
            }
        }

        return message.copy(
            content = "",
            contentParts = sanitizedParts.ifEmpty { null },
        )
    }

    private fun accumulateSanitizedToolTextLength(
        scope: ToolReinjectionDlpScope,
        currentLength: Long,
        text: String,
    ): Long {
        val nextLength = if (currentLength > Long.MAX_VALUE - text.length.toLong()) {
            Long.MAX_VALUE
        } else {
            currentLength + text.length.toLong()
        }
        if (nextLength > scope.aggregateTextLimit) {
            rejectSanitizedTextLimit(scope, nextLength)
        }
        return nextLength
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
        return if (!contentParts.isNullOrEmpty()) {
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

        enforceBeforeProviderResolution(operation, correlationId, securityContext)
        val candidates = providerRegistry.resolveCandidates(operation.operation)

        for ((routeIndex, route) in candidates.withIndex()) {
            val circuitOpen = handleCircuitBreakerOpenRoute(
                route = route,
                nextRoute = candidates.getOrNull(routeIndex + 1),
                correlationId = correlationId,
                securityContext = securityContext,
            )
            if (circuitOpen != null) {
                lastCircuitOpen = circuitOpen
                continue
            }

            try {
                enforceToolExposure(operation, correlationId, securityContext)
                return callProviderWithRetries(providerRetryRequest(route, routeIndex, operation, messages, attemptCounter, correlationId, securityContext))
            } catch (error: Throwable) {
                if (!shouldFallbackFrom(error)) {
                    throw error
                }
                enforceProviderFallbackAfterFailure(
                    error = error,
                    route = route,
                    nextRoute = candidates.getOrNull(routeIndex + 1),
                    correlationId = correlationId,
                    securityContext = securityContext,
                )
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

    private fun providerRetryRequest(
        route: ResolvedProviderRoute,
        routeIndex: Int,
        operation: OperationDefinition,
        messages: List<Message>,
        attemptCounter: AttemptCounter,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) = ProviderRetryRequest(
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

    private suspend fun enforceProviderFallbackAfterFailure(
        error: Throwable,
        route: ResolvedProviderRoute,
        nextRoute: ResolvedProviderRoute?,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        if (nextRoute == null) return
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
            policyError.addSuppressed(error)
            throw policyError
        }
    }

    private suspend fun callProviderWithRetries(retry: ProviderRetryRequest): ProviderCallResult {
        val maxAttempts = retry.operation.operation.providerRetries + 1

        repeat(maxAttempts) { retryIndex ->
            val attempt = startProviderRetryAttempt(retry)

            try {
                return executeProviderRetryAttempt(attempt, retry)
            } catch (error: dev.tramai.core.security.DlpInspectionException) {
                // DLP failures propagate directly — NOT a provider failure.
                // Do NOT call observation.onProviderFailure, circuitBreaker.onFailure,
                // or retry. Record call completion once.
                attempt.observation.onCallCompleted(parseSuccess = null)
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleProviderRetryFailure(error, retry, attempt.observation, retryIndex, maxAttempts)
            }
        }

        error("Provider retry loop exited without returning or throwing")
    }

    private data class ProviderRetryAttempt(
        val callContext: OperationCallContext,
        val interceptedRequest: ModelRequest,
        val observation: OperationObservation,
        val approvedModel: dev.tramai.core.model.RegisteredModel?,
    )

    private suspend fun startProviderRetryAttempt(retry: ProviderRetryRequest): ProviderRetryAttempt {
        val callContext = OperationCallContext(
            serviceInterface = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
            methodName = retry.operation.method.name,
            providerId = retry.providerId,
            requestedModel = retry.operation.operation.model,
            attempt = retry.attemptCounter.next(),
        )
        val interceptedRequest = retry.request.copy(
            messages = operationInterceptor.interceptRequest(callContext, retry.request.messages),
        )
        val observation = operationObserver.onCallStarted(callContext)
        observation.onEngineEvent(
            name = EVENT_ROUTE_SELECTED,
            attributes = routeSelectedAttributes(
                ResolvedProviderRoute(
                    providerName = retry.providerId,
                    provider = retry.provider,
                    requestedModelName = retry.operation.operation.model,
                    effectiveModelName = retry.request.model,
                ),
                routeIndex = retry.routeIndex,
            ),
        )
        val approvedModel = authorizeProviderRetryModel(retry.providerId, retry.request.model, observation)
        return ProviderRetryAttempt(callContext, interceptedRequest, observation, approvedModel)
    }

    private suspend fun authorizeProviderRetryModel(
        providerId: String,
        modelName: String,
        observation: OperationObservation,
    ): dev.tramai.core.model.RegisteredModel? = try {
        modelRegistryEnforcer.authorize(providerId, modelName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelRegistryException) {
        observation.onCallCompleted(parseSuccess = null)
        throw e
    }

    private suspend fun executeProviderRetryAttempt(
        attempt: ProviderRetryAttempt,
        retry: ProviderRetryRequest,
    ): ProviderCallResult {
        enforceBeforeProviderInvocation(
            providerId = retry.providerId,
            modelName = retry.request.model,
            correlationId = retry.correlationId,
            securityContext = retry.securityContext,
        )
        val rawResponse = callProviderOnce(retry.providerId, retry.provider, attempt.interceptedRequest, retry.operation)
        val interceptedResponse = operationInterceptor.interceptResponse(attempt.callContext, rawResponse)
        val sanitizedResponse = sanitizeProviderResponse(
            interceptedResponse = interceptedResponse,
            operation = retry.operation,
            providerId = retry.providerId,
            modelName = retry.request.model,
            correlationId = retry.correlationId,
            securityContext = retry.securityContext,
            observation = attempt.observation,
        )
        attempt.observation.onProviderResponse(sanitizedResponse)
        return ProviderCallResult(
            response = sanitizedResponse,
            observation = attempt.observation,
            providerId = retry.providerId,
            modelName = retry.request.model,
            approvedModel = attempt.approvedModel,
        )
    }

    private suspend fun handleProviderRetryFailure(
        error: Throwable,
        retry: ProviderRetryRequest,
        observation: OperationObservation,
        retryIndex: Int,
        maxAttempts: Int,
    ) {
        observation.onProviderFailure(error)
        observation.onCallCompleted(parseSuccess = null)

        if (!shouldRetryProviderCall(error, retryIndex, maxAttempts)) {
            val opened = circuitBreaker.onFailure(retry.providerId, error)
            if (opened) {
                observation.onEngineEvent(
                    name = EVENT_CIRCUIT_OPENED,
                    attributes = mapOf(ATTR_PROVIDER_ID to retry.providerId),
                )
            }
            throw error
        }

        val delayMillis = providerRetryDelayMillis(retryIndex, error)
        observation.onEngineEvent(
            name = "tramai.retry.scheduled",
            attributes = mapOf(
                ATTR_PROVIDER_ID to retry.providerId,
                ATTR_RETRY_INDEX to retryIndex,
                ATTR_DELAY_MILLIS to delayMillis,
                ATTR_DELAY_SOURCE to retryDelaySource(error),
            ),
        )
        delay(delayMillis)
    }

    private data class ProviderRetryRequest(
        val providerId: String,
        val provider: ModelProvider,
        val request: ModelRequest,
        val operation: OperationDefinition,
        val attemptCounter: AttemptCounter,
        val routeIndex: Int,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
    )

    /**
     * Applies authoritative DLP inspection to model output without marking failures as provider failures.
     */
    private suspend fun sanitizeProviderResponse(
        interceptedResponse: ModelResponse,
        operation: OperationDefinition,
        providerId: String,
        modelName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
        observation: OperationObservation,
    ): ModelResponse = try {
        if (dlpInterceptor === NoOpDlpInterceptor) {
            interceptedResponse
        } else {
            applyProviderOutputDlp(
                interceptedResponse = interceptedResponse,
                operation = operation,
                providerId = providerId,
                modelName = modelName,
                correlationId = correlationId,
                securityContext = securityContext,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: DlpInspectionException) {
        throw e
    } catch (e: Exception) {
        observation.onEngineEvent(
            name = "tramai.dlp.inspection_failed",
            attributes = mapOf("providerId" to providerId, "correlationId" to correlationId),
        )
        throw DlpInspectionException(
            message = "DLP inspection failed for provider '$providerId'",
            cause = e,
        )
    }

    /**
     * Builds the model-output DLP context and returns the sanitized response.
     */
    private suspend fun applyProviderOutputDlp(
        interceptedResponse: ModelResponse,
        operation: OperationDefinition,
        providerId: String,
        modelName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): ModelResponse {
        val dlpContext = DlpContext(
            contentType = DlpContentType.MODEL_OUTPUT,
            contentLocation = DlpContentLocation.MODEL_RESPONSE_CONTENT,
            operationInterface = serviceDefinition.serviceType.qualifiedName
                ?: serviceDefinition.serviceType.simpleName.orEmpty(),
            operationMethod = operation.method.name,
            providerId = providerId,
            modelName = modelName,
            correlationId = correlationId,
            dataClassification = securityContext.dataClassification,
            classificationSource = securityContext.classificationSource,
        )
        val dlpResult = inspectDlpAuthoritatively(dlpContext, interceptedResponse.content)
        return if (dlpResult.sanitizedText != interceptedResponse.content) {
            interceptedResponse.copy(content = dlpResult.sanitizedText)
        } else {
            interceptedResponse
        }
    }

    private suspend fun executeTool(request: ToolExecutionRequest): ToolResult {
        val tool = request.tool
        val toolCall = request.toolCall
        val operation = request.operation
        val correlationId = request.correlationId
        val securityContext = request.securityContext
        val conversationId = request.conversationId
        val input = toolCall.argumentsJson
        val maxAttempts = if (tool.idempotent) IDEMPOTENT_TOOL_MAX_ATTEMPTS else 1

        repeat(maxAttempts) { attemptIndex ->
            val context = ToolExecutionContext(
                operationName = operation.method.name,
                modelName = operation.operation.model,
                attemptNumber = attemptIndex,
                conversationId = conversationId,
                idempotencyKey = request.idempotencyKey,
                timeout = java.time.Duration.ofMillis(operation.operation.timeoutMillis),
            )

            handleToolExecutionPolicyDecision(
                request = request,
                policyDecision = evaluateBeforeToolExecution(tool, correlationId, securityContext),
                input = input,
            )

            val result = executeToolAttempt(tool, input, context)
            toolRetryTerminalResult(result, attemptIndex, maxAttempts)?.let { return it }
        }

        error("Tool retry loop exited without returning")
    }

    private suspend fun evaluateBeforeToolExecution(
        tool: ResolvedTool,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) = policyHelper.evaluate(
        policyHelper.buildContext(
            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_EXECUTION,
            correlationId = correlationId,
        ).toolName(tool.name)
            .toolSecurity(tool.security)
            .applySecurityContext(securityContext)
            .build()
    )

    private suspend fun handleToolExecutionPolicyDecision(
        request: ToolExecutionRequest,
        policyDecision: dev.tramai.core.policy.PolicyDecision,
        input: String,
    ) {
        when (policyDecision) {
            is dev.tramai.core.policy.PolicyDecision.RequireApproval ->
                handleToolApprovalRequirement(request, policyDecision, input)
            is dev.tramai.core.policy.PolicyDecision.Deny ->
                throw dev.tramai.core.exception.PolicyViolationException(policyDecision)
            else -> Unit
        }
    }

    private suspend fun handleToolApprovalRequirement(
        request: ToolExecutionRequest,
        policyDecision: dev.tramai.core.policy.PolicyDecision.RequireApproval,
        input: String,
    ) {
        if (request.resumingApproval) {
            validateRenewedApprovalRequirement(request, policyDecision, input)
            return
        }
        val rawDigest = validateInitialApprovalRequirement(request, policyDecision, input)
        suspendToolExecution(
            SuspendToolExecutionRequest(
                tool = request.tool,
                toolCall = request.toolCall,
                operation = request.operation,
                correlationId = request.correlationId,
                input = input,
                identity = request.identity,
                toolCallIndex = request.toolCallIndex,
                messages = request.messages,
                argumentsDigest = rawDigest,
                timeoutMillis = policyDecision.requirement.timeoutMillis,
                securityContext = request.securityContext,
                tokenBudgetTracker = request.tokenBudgetTracker,
                conversationId = request.conversationId,
                historySize = request.historySize,
            ),
        )
    }

    private fun validateRenewedApprovalRequirement(
        request: ToolExecutionRequest,
        policyDecision: dev.tramai.core.policy.PolicyDecision.RequireApproval,
        input: String,
    ) {
        if (!request.allowRenewedApprovedBindingDuringResume) {
            throw dev.tramai.core.exception.NestedApprovalNotSupportedException(
                approvalId = request.parentApprovalId ?: "unknown",
                message = "Nested approval not supported in v1: tool '${request.tool.name}' requires approval during a resumed workflow",
            )
        }
        val requirement = policyDecision.requirement
        val digester = toolArgumentsDigester
            ?: throw dev.tramai.core.exception.ConfigurationException(
                "ToolArgumentsDigester is required for renewed approval validation"
            )
        val renewedDigest = digester.digest(dev.tramai.core.approval.SensitiveToolArguments.of(input))
        require(requirement.toolName == request.tool.name) {
            "Renewed approval requirement tool name mismatch: '${requirement.toolName}' != '${request.tool.name}'"
        }
        require(
            requirement.argumentsDigest.isEmpty() ||
                dev.tramai.core.approval.Sha256Digest.of(requirement.argumentsDigest) == renewedDigest
        ) {
            "Renewed approval requirement digest mismatch"
        }
        require(requirement.timeoutMillis > 0) {
            "Renewed approval requirement must have positive timeout"
        }
    }

    private fun validateInitialApprovalRequirement(
        request: ToolExecutionRequest,
        policyDecision: dev.tramai.core.policy.PolicyDecision.RequireApproval,
        input: String,
    ): Sha256Digest {
        val requirement = policyDecision.requirement
        require(requirement.toolName == request.tool.name) {
            "Approval requirement tool binding mismatch: expected '${request.tool.name}', got '${requirement.toolName}'"
        }
        val rawDigest = (toolArgumentsDigester
            ?: throw dev.tramai.core.exception.ConfigurationException(
                "ToolArgumentsDigester is required for approval binding validation"
            )).digest(dev.tramai.core.approval.SensitiveToolArguments.of(input))
        if (requirement.argumentsDigest.isNotEmpty()) {
            val requiredDigest = dev.tramai.core.approval.Sha256Digest.of(requirement.argumentsDigest)
            require(requiredDigest == rawDigest) {
                "Approval requirement argument binding mismatch"
            }
        }
        require(requirement.timeoutMillis > 0) {
            "Approval requirement timeout must be positive"
        }
        return rawDigest
    }

    private suspend fun executeToolAttempt(
        tool: ResolvedTool,
        input: String,
        context: ToolExecutionContext,
    ): ToolResult = try {
        tool.execute(input, context)
    } catch (e: dev.tramai.core.exception.ToolInvalidInputException) {
        ToolResult.InvalidInput(e.message ?: "Invalid tool input")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (tool.idempotent) {
            ToolResult.TransientFailure(e)
        } else {
            ToolResult.PermanentFailure(e.message ?: "Tool execution failed")
        }
    }

    private fun toolRetryTerminalResult(
        result: ToolResult,
        attemptIndex: Int,
        maxAttempts: Int,
    ): ToolResult? {
        if (result !is ToolResult.TransientFailure) {
            return result
        }
        if (attemptIndex < maxAttempts - 1) {
            return null
        }
        return ToolResult.PermanentFailure(
            result.cause.message ?: "Tool execution failed after $maxAttempts attempt(s)",
        )
    }

    private data class ToolExecutionRequest(
        val tool: ResolvedTool,
        val toolCall: ToolCall,
        val operation: OperationDefinition,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
        val identity: EngineExecutionIdentity,
        val messages: List<Message>,
        val toolCallIndex: Int = -1,
        val tokenBudgetTracker: TokenBudgetTracker? = null,
        val conversationId: String? = null,
        val historySize: Int = 0,
        val resumingApproval: Boolean = false,
        val parentApprovalId: String? = null,
        val idempotencyKey: String? = null,
        val allowRenewedApprovedBindingDuringResume: Boolean = false,
    )

    /**
     * Suspends tool execution by creating an approval challenge, persisting
     * the continuation and suspended invocation, then throwing [ApprovalSuspendedException].
     */
    private suspend fun suspendToolExecution(
        request: SuspendToolExecutionRequest,
    ): Nothing {
        val tool = request.tool
        val toolCall = request.toolCall
        val operation = request.operation
        val correlationId = request.correlationId
        val input = request.input
        val identity = request.identity
        val toolCallIndex = request.toolCallIndex
        val messages = request.messages
        val argumentsDigest = request.argumentsDigest
        val timeoutMillis = request.timeoutMillis
        val securityContext = request.securityContext
        val tokenBudgetTracker = request.tokenBudgetTracker
        val conversationId = request.conversationId
        val historySize = request.historySize
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

            // Register operation in the trusted registry (idempotent for same definition)
            val opRef = resumeOperationRegistry.register(
                serviceDefinition = serviceDefinition,
                operation = operation,
                handler = this,
            )
            val prepared = ReplayEnvelopeFactory.prepareForSuspension(
                operationReference = opRef,
                messages = messages,
                toolCallId = toolCall.id,
                toolName = tool.name,
                toolCallIndex = toolCallIndex,
            )
            val toolRef = ResumeToolReference(tool.name, ResumeToolDeclarationDigestHelper.compute(tool))

            suspendedInvocationStore.create(
                metadata = SuspendedInvocationMetadata(
                    approvalId = challenge.approvalId,
                    toolCallId = toolCall.id,
                    toolName = tool.name,
                    toolCallIndex = toolCallIndex,
                    correlationId = correlationId,
                    identity = identity,
                    securityContext = securityContext,
                    operationReference = opRef,
                    replayEnvelopeDigest = prepared.digest,
                    conversationId = conversationId,
                    historySize = historySize,
                    tokenBudgetSnapshot = budgetSnapshot,
                    toolReference = toolRef,
                    toolSecurity = tool.security,
                ),
                replayEnvelope = prepared.envelope,
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
                try {
                    suspendedInvocationStore.remove(approvalId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // best-effort cleanup
                }
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

    private data class SuspendToolExecutionRequest(
        val tool: ResolvedTool,
        val toolCall: ToolCall,
        val operation: OperationDefinition,
        val correlationId: String,
        val input: String,
        val identity: EngineExecutionIdentity,
        val toolCallIndex: Int,
        val messages: List<Message>,
        val argumentsDigest: Sha256Digest,
        val timeoutMillis: Long,
        val securityContext: ExecutionSecurityContext,
        val tokenBudgetTracker: TokenBudgetTracker? = null,
        val conversationId: String? = null,
        val historySize: Int = 0,
    )

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
     * Ordering guarantees (Fix 1-3):
     * 1. Loads metadata + resolves continuation (read-only validation)
     * 2. Validates continuation is PENDING and version matches
     * 3. Resolves digester and other non-side-effecting dependencies
     * 4. Calls validateResume() — validates token and binding without consuming
     * 5. Evaluates BEFORE_WORKFLOW_RESUME
     * 6. IF Deny/RequireApproval: cancel continuation, remove suspended state, emit audit
     * 7. IF Allow: authorizeResume(), claimForExecution(), and continue
     */
    @Deprecated("Use the overload with metadata+registeredOperation")
    suspend fun resumeApprovalInternal(command: ResumeApprovalCommand): Any? {
        val meta = suspendedInvocationStore.get(command.approvalId)
            ?: throw dev.tramai.core.exception.ApprovalNotFoundException(command.approvalId)
        val registered = resumeOperationRegistry.resolve(meta.operationReference)
        return resumeApprovalInternal(command, meta, registered)
    }

    /**
     * Resume an approval-suspended tool execution using pre-loaded metadata and a pre-resolved registered operation.
     *
     * This overload bypasses the [SuspendedInvocationStore] lookup and [ResumeOperationRegistry] resolution,
     * allowing callers (e.g. [TramaiEngine.resumeApproval]) to supply already-fetched metadata and a
     * already-resolved registered operation for cross-store integrity and operation-definition-drift checks.
     *
     * @param command The approval resume command.
     * @param metadata Pre-loaded [SuspendedInvocationMetadata] for the approval.
     * @param registered Pre-resolved [RegisteredResumeOperation] for the operation.
     * @return The result of the resumed operation.
     */
    suspend fun resumeApprovalInternal(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        registered: RegisteredResumeOperation,
    ): Any? {
        val store = requireApprovalContinuationStore()
        val existingContinuation = loadPendingResumeContinuation(store, command, metadata, registered)
        val resolvedTool = resolveAndValidateResumeTool(command, metadata, existingContinuation)
        val coordinator = requireApprovalGateCoordinator()
        val digester = requireToolArgumentsDigester()

        validateResumeToken(command, metadata, existingContinuation, coordinator)
        enforceResumePolicy(command, metadata, resolvedTool, store)

        // 7. Authorize resume (token consumption)
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
        emitAuthorizationReplayed(authorization.replayed, command, metadata)

        // 8. Claim continuation
        val claimed = store.claimForExecution(
            approvalId = command.approvalId,
            expectedVersion = command.continuationExpectedVersion,
            claimedBy = command.resumedBy,
        )

        // Task 11: Post-claim — reveal replay envelope and verify digest
        val uncertainOutcome = ResumeUncertainOutcome()
        val resumeContext = ResumeExecutionContext(
            command = command,
            metadata = metadata,
            registered = registered,
            resolvedTool = resolvedTool,
            uncertainOutcome = uncertainOutcome,
        )
        return try {
            executeClaimedResume(
                context = resumeContext,
                claimed = claimed,
                digester = digester,
                store = store,
            )
        } catch (e: dev.tramai.core.exception.NestedApprovalNotSupportedException) {
            emitResumeUncertainOutcomeOnce(uncertainOutcome, command, metadata, "nested-approval-not-supported")
            throw e
        } catch (e: dev.tramai.core.exception.StructuredOutputException) {
            emitResumeUncertainOutcomeOnce(uncertainOutcome, command, metadata, "structured-parse-failed: ${e::class.simpleName ?: "unknown"}")
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emitResumeUncertainOutcomeOnce(uncertainOutcome, command, metadata, "resume-failed: ${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }

    private class ResumeUncertainOutcome(var emitted: Boolean = false)

    private data class ResumeExecutionContext(
        val command: ResumeApprovalCommand,
        val metadata: SuspendedInvocationMetadata,
        val registered: RegisteredResumeOperation,
        val resolvedTool: ResolvedTool,
        val uncertainOutcome: ResumeUncertainOutcome,
    )

    private suspend fun executeClaimedResume(
        context: ResumeExecutionContext,
        claimed: ClaimedApprovalContinuation,
        digester: ToolArgumentsDigester,
        store: ApprovalContinuationStore,
    ): Any? {
        val command = context.command
        val metadata = context.metadata
        val registered = context.registered
        val replayPayload = revealAndValidateReplayPayload(context.uncertainOutcome, command, metadata)
        val expectedArgsDigest = validateClaimedResumeArguments(context.uncertainOutcome, command, metadata, claimed, digester)
        val validatedInput = claimed.arguments.reveal()
        val rehydratedPayload = ReplayEnvelopeFactory.rehydrateAfterClaim(
            payload = replayPayload,
            metadata = metadata,
            claimedArgumentsJson = validatedInput,
        )
        val tokenBudgetTracker = restoredTokenBudgetTracker(metadata)
        val toolResult = executeResumedTool(
            context = context,
            rehydratedPayload = rehydratedPayload,
            validatedInput = validatedInput,
            expectedArgsDigest = expectedArgsDigest,
            tokenBudgetTracker = tokenBudgetTracker,
        )
        val messages = rehydratedPayload.messages.toMutableList()
        val loopResult = continueAfterToolResult(
            ContinueAfterToolResultRequest(
                operation = registered.operation,
                messages = messages,
                toolResult = toolResult,
                toolCallId = metadata.toolCallId,
                toolCallIndex = metadata.toolCallIndex,
                correlationId = metadata.correlationId,
                securityContext = metadata.securityContext,
                identity = metadata.identity,
                tokenBudgetTracker = tokenBudgetTracker,
                suspendedToolName = metadata.toolName,
                approvalId = command.approvalId,
                conversationId = metadata.conversationId,
                historySize = metadata.historySize,
                resumingApproval = true,
            ),
        )
        val result = finalizeResumedOperation(
            operation = registered.operation,
            loopResult = loopResult,
            messages = messages,
            correlationId = metadata.correlationId,
            securityContext = metadata.securityContext,
            conversationId = metadata.conversationId,
            historySize = metadata.historySize,
        )
        completeClaimedResume(command, metadata, claimed, store)
        return result
    }

    private suspend fun revealAndValidateReplayPayload(
        marker: ResumeUncertainOutcome,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
    ): ReplayPayload {
        val replayEnvelope = suspendedInvocationStore.revealReplayEnvelope(command.approvalId)
            ?: throw dev.tramai.core.exception.ConfigurationException("replay-envelope-not-found")
        val replayPayload = replayEnvelope.revealForResume()
        val actualDigest = ReplayEnvelopeDigestHelper.compute(metadata.operationReference, replayPayload.messages)
        if (actualDigest != metadata.replayEnvelopeDigest) {
            emitResumeUncertainOutcomeOnce(marker, command, metadata, "replay-envelope-digest-mismatch")
            throw dev.tramai.core.exception.ConfigurationException("Replay envelope digest mismatch")
        }
        return replayPayload
    }

    private suspend fun validateClaimedResumeArguments(
        marker: ResumeUncertainOutcome,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        claimed: ClaimedApprovalContinuation,
        digester: ToolArgumentsDigester,
    ): Sha256Digest {
        val actualArgsDigest = digester.digest(claimed.arguments)
        val expectedArgsDigest = claimed.continuation.argumentsDigest
        if (actualArgsDigest != expectedArgsDigest) {
            emitResumeUncertainOutcomeOnce(marker, command, metadata, "payload-integrity-mismatch")
            throw dev.tramai.core.exception.ConfigurationException("Claimed continuation payload integrity mismatch")
        }
        return expectedArgsDigest
    }

    private fun restoredTokenBudgetTracker(metadata: SuspendedInvocationMetadata): TokenBudgetTracker =
        TokenBudgetTracker(tokenBudgetSettings).also { tracker ->
            metadata.tokenBudgetSnapshot?.let { tracker.restore(it) }
        }

    private suspend fun executeResumedTool(
        context: ResumeExecutionContext,
        rehydratedPayload: RehydratedReplayPayload,
        validatedInput: String,
        expectedArgsDigest: Sha256Digest,
        tokenBudgetTracker: TokenBudgetTracker,
    ): ToolResult {
        val command = context.command
        val metadata = context.metadata
        val registered = context.registered
        val resolvedTool = context.resolvedTool
        val uncertainOutcome = context.uncertainOutcome
        val validatedToolCall = dev.tramai.core.model.ToolCall(
            id = metadata.toolCallId,
            name = metadata.toolName,
            argumentsJson = validatedInput,
        )
        approvalLifecycleAuditEmitter.onToolExecutionResumed(
            approvalId = command.approvalId,
            workflowRunId = metadata.identity.workflowRunId,
            toolName = metadata.toolName,
            resumedBy = command.resumedBy,
        )
        return try {
            executeTool(
                ToolExecutionRequest(
                    tool = resolvedTool,
                    toolCall = validatedToolCall,
                    operation = registered.operation,
                    correlationId = metadata.correlationId,
                    securityContext = metadata.securityContext,
                    identity = metadata.identity,
                    messages = rehydratedPayload.messages,
                    tokenBudgetTracker = tokenBudgetTracker,
                    conversationId = metadata.conversationId,
                    historySize = metadata.historySize,
                    resumingApproval = true,
                    parentApprovalId = command.approvalId,
                    idempotencyKey = IdempotencyKeyUtil.deriveApprovalKey(command.approvalId, metadata.toolCallId, expectedArgsDigest),
                    allowRenewedApprovedBindingDuringResume = true,
                ),
            )
        } catch (e: dev.tramai.core.exception.NestedApprovalNotSupportedException) {
            throw e
        } catch (e: ToolInvalidInputException) {
            emitResumeUncertainOutcomeOnce(uncertainOutcome, command, metadata, "tool-execution-failed: ${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }

    private suspend fun completeClaimedResume(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        claimed: ClaimedApprovalContinuation,
        store: ApprovalContinuationStore,
    ) {
        store.complete(
            approvalId = command.approvalId,
            expectedVersion = claimed.continuation.version,
            completedBy = command.resumedBy,
        )
        removeSuspendedInvocationAfterResume(command, metadata)
        emitResumeCompletionAudit(command, metadata)
    }

    private suspend fun removeSuspendedInvocationAfterResume(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
    ) {
        try {
            suspendedInvocationStore.remove(command.approvalId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching {
                engineEventObserver.onEngineEvent(
                    name = "resume-suspended-context-cleanup-failure",
                    attributes = mapOf("approvalId" to command.approvalId, "toolName" to metadata.toolName),
                )
            }
        }
    }

    private suspend fun emitResumeCompletionAudit(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
    ) {
        try {
            approvalLifecycleAuditEmitter.onToolExecutionCompleted(
                approvalId = command.approvalId,
                workflowRunId = metadata.identity.workflowRunId,
                toolName = metadata.toolName,
                completedBy = command.resumedBy,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching {
                engineEventObserver.onEngineEvent(
                    name = "resume-completion-audit-failure",
                    attributes = mapOf("approvalId" to command.approvalId, "toolName" to metadata.toolName),
                )
            }
        }
    }

    private suspend fun emitResumeUncertainOutcomeOnce(
        marker: ResumeUncertainOutcome,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        reason: String,
    ) {
        if (marker.emitted) return
        marker.emitted = true
        approvalLifecycleAuditEmitter.onUncertainOutcome(
            approvalId = command.approvalId,
            workflowRunId = metadata.identity.workflowRunId,
            toolName = metadata.toolName,
            reason = reason,
        )
    }

    /**
     * Resolves the continuation store required by approval resume.
     */
    private fun requireApprovalContinuationStore(): ApprovalContinuationStore =
        approvalContinuationStore
            ?: throw ConfigurationException("ApprovalContinuationStore is required for resume")

    /**
     * Resolves the approval coordinator required by approval resume.
     */
    private fun requireApprovalGateCoordinator(): ApprovalGateCoordinator =
        approvalGateCoordinator
            ?: throw ConfigurationException("ApprovalGateCoordinator is required for resume")

    /**
     * Resolves the tool-argument digester used for claimed payload integrity checks.
     */
    private fun requireToolArgumentsDigester(): ToolArgumentsDigester =
        requireNotNull(toolArgumentsDigester) {
            "ToolArgumentsDigester is required for payload integrity verification"
        }

    /**
     * Loads the continuation and validates all pre-token cross-store invariants.
     */
    private suspend fun loadPendingResumeContinuation(
        store: ApprovalContinuationStore,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        registered: RegisteredResumeOperation,
    ): ApprovalContinuation {
        val continuation = store.get(command.approvalId)
            ?: throw ApprovalNotFoundException(command.approvalId)
        if (continuation.status == ApprovalContinuationStatus.COMPLETED) {
            throw dev.tramai.core.exception.ApprovalTokenRejectedException(command.approvalId)
        }
        validateResumeContinuationBinding(continuation, command, metadata, registered)
        return continuation
    }

    /**
     * Validates persisted continuation metadata against suspended metadata and the registered operation.
     */
    private fun validateResumeContinuationBinding(
        continuation: ApprovalContinuation,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        registered: RegisteredResumeOperation,
    ) {
        require(continuation.workflowRunId == metadata.identity.workflowRunId) {
            "cross-store-mismatch-workflow-run-id"
        }
        require(continuation.correlationId == metadata.correlationId) {
            "cross-store-mismatch-correlation-id"
        }
        require(metadata.identity.correlationId == metadata.correlationId) {
            "metadata-identity-mismatch-correlation-id"
        }
        require(continuation.workflowDigest == metadata.identity.workflowDigest) {
            "cross-store-mismatch-workflow-digest"
        }
        require(continuation.policyVersion == metadata.identity.policyVersion) {
            "cross-store-mismatch-policy-version"
        }
        require(continuation.toolName == metadata.toolName) { "continuation-tool-name-mismatch" }
        require(continuation.toolCallId == metadata.toolCallId) { "continuation-tool-call-id-mismatch" }
        require(metadata.operationReference.resumeDefinitionDigest == registered.reference.resumeDefinitionDigest) {
            "resume-operation-definition-drift"
        }
        require(continuation.status == ApprovalContinuationStatus.PENDING) { "continuation-not-pending" }
        require(continuation.version == command.continuationExpectedVersion) { "continuation-version-mismatch" }
        require(metadata.approvalId == command.approvalId) { "metadata-approval-id-mismatch" }
        require(continuation.approvalId == command.approvalId) { "continuation-approval-id-mismatch" }
    }

    /**
     * Resolves the approved tool from the active registry and validates drift-sensitive bindings.
     */
    private fun resolveAndValidateResumeTool(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        continuation: ApprovalContinuation,
    ): ResolvedTool {
        val resolvedTool = toolRegistry.resolve(metadata.toolName)
            ?: throw ConfigurationException("approved-tool-not-registered")
        val activeDeclDigest = ResumeToolDeclarationDigestHelper.compute(resolvedTool)
        require(activeDeclDigest == metadata.toolReference.declarationDigest) {
            "resume-tool-declaration-drift"
        }
        require(metadata.toolReference.toolName == metadata.toolName) { "resume-tool-reference-name-mismatch" }
        require(metadata.toolReference.toolName == resolvedTool.name) { "resume-tool-reference-active-name-mismatch" }
        require(metadata.toolSecurity == resolvedTool.security) { "resume-tool-security-metadata-drift" }
        require(continuation.approvalId == command.approvalId) { "continuation-approval-id-mismatch" }
        return resolvedTool
    }

    /**
     * Performs read-only token and approval binding validation before one-time token consumption.
     */
    private suspend fun validateResumeToken(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        continuation: ApprovalContinuation,
        coordinator: ApprovalGateCoordinator,
    ) {
        coordinator.validateResume(
            ValidateResumeCommand(
                approvalId = command.approvalId,
                expectedVersion = command.approvalExpectedVersion,
                presentedToken = command.presentedToken,
                consumedBy = command.resumedBy,
                workflowRunId = metadata.identity.workflowRunId,
                toolName = metadata.toolName,
                argumentsDigest = continuation.argumentsDigest,
                policyVersion = metadata.identity.policyVersion,
                workflowDigest = metadata.identity.workflowDigest,
            )
        )
    }

    /**
     * Evaluates workflow-resume policy and cancels persisted state on fail-closed decisions.
     */
    private suspend fun enforceResumePolicy(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        resolvedTool: ResolvedTool,
        store: ApprovalContinuationStore,
    ) {
        when (val decision = evaluateResumePolicy(command, metadata, resolvedTool)) {
            is dev.tramai.core.policy.PolicyDecision.Deny -> cancelDeniedResume(command, metadata, store, decision)
            is dev.tramai.core.policy.PolicyDecision.RequireApproval -> cancelNestedResume(command, metadata, store)
            dev.tramai.core.policy.PolicyDecision.Allow -> Unit
        }
    }

    /**
     * Builds and evaluates the BEFORE_WORKFLOW_RESUME policy context.
     */
    private suspend fun evaluateResumePolicy(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        resolvedTool: ResolvedTool,
    ) = policyHelper.evaluate(
        policyHelper.buildContext(
            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_WORKFLOW_RESUME,
            correlationId = metadata.correlationId,
        ).toolName(metadata.toolName)
            .toolSecurity(resolvedTool.security)
            .applySecurityContext(metadata.securityContext)
            .workflowRunId(metadata.identity.workflowRunId)
            .workflowDigest(metadata.identity.workflowDigest.value)
            .actorId(command.resumedBy)
            .build()
    )

    /**
     * Cancels suspended state after a resume policy denial.
     */
    private suspend fun cancelDeniedResume(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        store: ApprovalContinuationStore,
        decision: dev.tramai.core.policy.PolicyDecision.Deny,
    ): Nothing {
        cancelResumeState(command, metadata, store, "workflow-resume-denied: ${decision.reasonCode}")
        throw PolicyViolationException(decision)
    }

    /**
     * Cancels suspended state when resume would require a nested approval.
     */
    private suspend fun cancelNestedResume(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        store: ApprovalContinuationStore,
    ): Nothing {
        cancelResumeState(command, metadata, store, "nested-approval-not-supported")
        throw dev.tramai.core.exception.NestedApprovalNotSupportedException(
            approvalId = command.approvalId,
            message = "Nested approval not supported",
        )
    }

    /**
     * Cancels continuation state, removes suspended metadata, and emits cancellation audit.
     */
    private suspend fun cancelResumeState(
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
        store: ApprovalContinuationStore,
        reason: String,
    ) {
        store.cancel(
            approvalId = command.approvalId,
            expectedVersion = command.continuationExpectedVersion,
        )
        suspendedInvocationStore.remove(command.approvalId)
        approvalLifecycleAuditEmitter.onSuspensionCancelled(
            approvalId = command.approvalId,
            workflowRunId = metadata.identity.workflowRunId,
            toolName = metadata.toolName,
            reason = reason,
        )
    }

    /**
     * Emits a best-effort engine event for idempotent authorization replay.
     */
    private fun emitAuthorizationReplayed(
        replayed: Boolean,
        command: ResumeApprovalCommand,
        metadata: SuspendedInvocationMetadata,
    ) {
        if (!replayed) {
            return
        }
        try {
            engineEventObserver.onEngineEvent(
                name = "tramai.approval.authorization_replayed",
                attributes = mapOf(
                    "approvalId" to command.approvalId,
                    "workflowRunId" to metadata.identity.workflowRunId,
                    "toolName" to metadata.toolName,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Engine-event observer failures must not prevent resume completion.
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
        messages: List<Message>,
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
    private suspend fun continueAfterToolResult(request: ContinueAfterToolResultRequest): ProviderCallResult {
        val operation = request.operation
        val messages = request.messages
        val toolResult = request.toolResult
        val toolCallId = request.toolCallId
        val correlationId = request.correlationId
        val securityContext = request.securityContext
        val suspendedToolName = request.suspendedToolName

        enforceToolResultReinjection(suspendedToolName, correlationId, securityContext)

        val toolMessage = formatToolResult(toolResult, toolCallId)
        messages += sanitizeReinjectedToolMessage(toolMessage, operation, suspendedToolName, correlationId, securityContext)
        processRemainingResumeToolCalls(request)

        return executeWithTools(
            ToolLoopContext(
                operation = operation,
                messages = messages,
                tokenBudgetTracker = request.tokenBudgetTracker,
                correlationId = correlationId,
                securityContext = securityContext,
                identity = request.identity,
                conversationId = request.conversationId,
                historySize = request.historySize,
                resumingApproval = request.resumingApproval,
                parentApprovalId = request.approvalId,
            ),
        )
    }

    private suspend fun enforceToolResultReinjection(
        toolName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ) {
        val resolvedTool = toolRegistry.resolve(toolName)
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION,
                correlationId = correlationId,
            ).toolName(toolName)
                .toolSecurity(resolvedTool?.security)
                .applySecurityContext(securityContext)
                .build()
        )
    }

    private suspend fun sanitizeReinjectedToolMessage(
        message: Message,
        operation: OperationDefinition,
        toolName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    ): Message = sanitizeToolMessageForReinjection(
        message = message,
        operation = operation,
        toolName = toolName,
        correlationId = correlationId,
        securityContext = securityContext,
        engineEventObserver = engineEventObserver,
    )

    private suspend fun processRemainingResumeToolCalls(request: ContinueAfterToolResultRequest) {
        if (request.toolCallIndex < 0) return
        val allToolCalls = request.messages
            .lastOrNull { it.role == MessageRole.ASSISTANT && it.toolCalls != null }
            ?.toolCalls
            ?: emptyList()
        val remainingToolCalls = allToolCalls.drop(request.toolCallIndex + 1)
        for ((remainingIdx, toolCall) in remainingToolCalls.withIndex()) {
            appendRemainingResumeToolResult(
                request = request,
                toolCall = toolCall,
                actualIndex = request.toolCallIndex + 1 + remainingIdx,
            )
        }
    }

    private suspend fun appendRemainingResumeToolResult(
        request: ContinueAfterToolResultRequest,
        toolCall: ToolCall,
        actualIndex: Int,
    ) {
        val tool = toolRegistry.resolve(toolCall.name)
        val toolResult = executeRemainingResumeTool(request, toolCall, tool, actualIndex)
        enforceToolResultReinjection(tool?.name ?: UNREGISTERED_LABEL, request.correlationId, request.securityContext)
        val message = formatToolResult(toolResult, toolCall.id)
        request.messages += sanitizeReinjectedToolMessage(
            message = message,
            operation = request.operation,
            toolName = toolCall.name,
            correlationId = request.correlationId,
            securityContext = request.securityContext,
        )
    }

    private suspend fun executeRemainingResumeTool(
        request: ContinueAfterToolResultRequest,
        toolCall: ToolCall,
        tool: ResolvedTool?,
        actualIndex: Int,
    ): ToolResult {
        if (tool == null) {
            return ToolResult.PermanentFailure("Tool '<unregistered>' not found")
        }
        return try {
            executeTool(
                ToolExecutionRequest(
                    tool = tool,
                    toolCall = toolCall,
                    operation = request.operation,
                    correlationId = request.correlationId,
                    securityContext = request.securityContext,
                    identity = request.identity,
                    messages = request.messages,
                    toolCallIndex = actualIndex,
                    tokenBudgetTracker = request.tokenBudgetTracker,
                    conversationId = request.conversationId,
                    historySize = request.historySize,
                    resumingApproval = request.resumingApproval,
                    parentApprovalId = request.approvalId,
                ),
            )
        } catch (e: dev.tramai.core.exception.NestedApprovalNotSupportedException) {
            throw dev.tramai.core.exception.NestedApprovalNotSupportedException(
                approvalId = request.approvalId,
                message = "Nested approval not supported in v1: sibling tool ${toolCall.name} requires approval",
            )
        } catch (e: ApprovalSuspendedException) {
            approvalLifecycleAuditEmitter.onUncertainOutcome(
                approvalId = request.approvalId,
                workflowRunId = request.identity.workflowRunId,
                toolName = tool.name,
                reason = "nested-approval-not-supported: sibling tool ${toolCall.name} requires approval",
            )
            throw ConfigurationException("Nested approval not supported in v1: sibling tool ${toolCall.name} requires approval")
        }
    }

    private data class ContinueAfterToolResultRequest(
        val operation: OperationDefinition,
        val messages: MutableList<Message>,
        val toolResult: ToolResult,
        val toolCallId: String,
        val toolCallIndex: Int,
        val correlationId: String,
        val securityContext: ExecutionSecurityContext,
        val identity: EngineExecutionIdentity,
        val tokenBudgetTracker: TokenBudgetTracker,
        val suspendedToolName: String = "",
        val approvalId: String = "",
        val conversationId: String? = null,
        val historySize: Int = 0,
        val resumingApproval: Boolean = false,
    )

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
                val argument = args[i]
                    ?: throw IllegalArgumentException("@ConversationId parameter '${parameters[i].name}' at index $i is null")
                return argument.toString()
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
        authorizeCachedModelProvenance(cached.provenance)
        enforceCacheReusePolicies(cacheKey, cached, securityContext, correlationId)
    }

    /**
     * Re-authorizes cached model provenance against the current registry entry.
     */
    private suspend fun authorizeCachedModelProvenance(provenance: CachedResponseProvenance) {
        if (!modelRegistrySettings.enabled) {
            return
        }
        val current = modelRegistryEnforcer.authorize(provenance.providerId, provenance.modelName)
            ?: error("ModelRegistryEnforcer.authorize returned null when registry is enabled")
        if (current.registryEntryId != provenance.modelRegistryEntryId ||
            current.revision != provenance.modelRevision ||
            current.artifactDigest != provenance.modelArtifactDigest
        ) {
            throw CachedModelProvenanceMismatchException()
        }
    }

    /**
     * Applies the same policy gates that a fresh provider call would cross on a cache hit.
     */
    private suspend fun enforceCacheReusePolicies(
        cacheKey: OperationCacheKey,
        cached: CachedOperationResult,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        enforceCacheReuseProviderResolution(cacheKey, securityContext, correlationId)
        enforceCacheReuseProviderInvocation(cached.provenance, securityContext, correlationId)
        enforceCacheReuseResponseReturn(cached.provenance, securityContext, correlationId)
    }

    /**
     * Enforces provider-resolution policy for a reused cached response.
     */
    private suspend fun enforceCacheReuseProviderResolution(
        cacheKey: OperationCacheKey,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_RESOLUTION,
                correlationId = correlationId,
            ).modelName(cacheKey.requestedModel)
                .applySecurityContext(securityContext)
                .attribute("cacheReuse", "true")
                .build()
        )
    }

    /**
     * Enforces provider-invocation policy for a reused cached response.
     */
    private suspend fun enforceCacheReuseProviderInvocation(
        provenance: CachedResponseProvenance,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                correlationId = correlationId,
            ).providerId(provenance.providerId)
                .modelName(provenance.modelName)
                .applySecurityContext(securityContext)
                .attribute("cacheReuse", "true")
                .build()
        )
    }

    /**
     * Enforces response-return policy for a reused cached response.
     */
    private suspend fun enforceCacheReuseResponseReturn(
        provenance: CachedResponseProvenance,
        securityContext: ExecutionSecurityContext,
        correlationId: String,
    ) {
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = correlationId,
            ).providerId(provenance.providerId)
                .modelName(provenance.modelName)
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
        check(provenance.hasProviderEnvelope()) { "Cached entry envelope has blank provider provenance" }
        check(provenance.matchesSecurityPartition(key.securityPartition)) {
            cachedPartitionMismatchMessage(key.securityPartition, provenance)
        }
    }

    /**
     * Checks that cached provenance carries the provider identity needed for reuse policy gates.
     */
    private fun CachedResponseProvenance.hasProviderEnvelope(): Boolean =
        providerId.isNotBlank() && modelName.isNotBlank()

    /**
     * Checks that cached data cannot cross security classification partitions.
     */
    private fun CachedResponseProvenance.matchesSecurityPartition(partition: CacheSecurityPartition): Boolean =
        dataClassification == partition.dataClassification &&
            classificationSource == partition.classificationSource

    /**
     * Builds the explicit cache partition mismatch diagnostic.
     */
    private fun cachedPartitionMismatchMessage(
        partition: CacheSecurityPartition,
        provenance: CachedResponseProvenance,
    ): String =
        "Cached entry envelope mismatch: key partition " +
            "$partition != cached provenance partition " +
            "(${provenance.dataClassification}, ${provenance.classificationSource})"

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
        approvedModel: RegisteredModel?,
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
                    modelRegistryEntryId = approvedModel?.registryEntryId,
                    modelRevision = approvedModel?.revision,
                    modelArtifactDigest = approvedModel?.artifactDigest,
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
            val javaType = validateServiceType(serviceType)

            val systemPrompt = serviceType.java.getAnnotation(SystemPrompt::class.java)?.value?.takeIf { it.isNotBlank() }
            val operations = javaType.methods
                .filterNot { it.declaringClass == Any::class.java }
                .associateWith { method ->
                    createOperationDefinition(javaType, method, systemPrompt, toolRegistry, promptSanitizer)
                }

            return ServiceDefinition(
                serviceType = serviceType,
                systemPrompt = systemPrompt,
                operations = operations,
            )
        }

        /**
         * Validates that a service type can be proxied by the runtime.
         */
        private fun validateServiceType(serviceType: KClass<*>): Class<*> {
            val javaType = serviceType.java
            if (!javaType.isInterface) {
                throw ConfigurationException("${javaType.name} must be an interface")
            }
            if (!javaType.isAnnotationPresent(AiService::class.java)) {
                throw ConfigurationException("${javaType.name} must be annotated with @AiService")
            }
            return javaType
        }

        /**
         * Builds an operation definition from method annotations and resolved tool metadata.
         */
        private fun createOperationDefinition(
            javaType: Class<*>,
            method: Method,
            systemPrompt: String?,
            toolRegistry: ToolRegistry,
            promptSanitizer: PromptSanitizer?,
        ): OperationDefinition {
            val operation = method.getAnnotation(Operation::class.java)
                ?: throw ConfigurationException("${javaType.name}.${method.name} must be annotated with @Operation")
            return OperationDefinition.create(
                method = method,
                operation = operation,
                classLevelSystemPrompt = systemPrompt,
                systemAnnotations = method.getAnnotationsByType(SystemMessage::class.java).map { it.value },
                userAnnotations = method.getAnnotationsByType(UserMessage::class.java).map { it.value },
                toolDefinitions = resolveToolDefinitions(method, operation, toolRegistry),
                promptSanitizer = promptSanitizer,
            )
        }

        /**
         * Converts declared tool names into provider-facing definitions.
         */
        private fun resolveToolDefinitions(
            method: Method,
            operation: Operation,
            toolRegistry: ToolRegistry,
        ): List<ToolDefinition> = operation.tools.map { toolName ->
            val tool = toolRegistry.resolve(toolName)
                ?: throw ConfigurationException("Tool '$toolName' requested by ${method.name} is not registered in the engine")
            ToolDefinition(tool.name, tool.description, tool.inputSchemaJson)
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
    ): List<Message> = buildList {
        add(annotationSystemMessage(arguments))
        addAll(annotationUserMessages(arguments))
        appendSchemaConstraint(schemaJson)
    }

    /**
     * Builds the system message used by multi-message annotations.
     */
    private fun annotationSystemMessage(arguments: List<String>): Message {
        val system = effectiveSystemMessage
        return if (!system.isNullOrBlank()) {
            Message(role = MessageRole.SYSTEM, content = interpolate(system, arguments))
        } else {
            Message(role = MessageRole.SYSTEM, content = defaultSystemMessage())
        }
    }

    /**
     * Builds user messages from @User annotations, @Operation.prompt, or the default operation text.
     */
    private fun annotationUserMessages(arguments: List<String>): List<Message> =
        when {
            userAnnotations.isNotEmpty() -> userAnnotations.map { template ->
                Message(role = MessageRole.USER, content = interpolate(template, arguments))
            }
            operation.prompt.isNotBlank() -> listOf(
                Message(role = MessageRole.USER, content = interpolate(operation.prompt, arguments))
            )
            else -> listOf(
                Message(
                    role = MessageRole.USER,
                    content = "Execute the operation ${method.name} with the provided parameters.",
                )
            )
        }

    /**
     * Adds the structured-output schema constraint to the final user message.
     */
    private fun MutableList<Message>.appendSchemaConstraint(schemaJson: String?) {
        if (schemaJson.isNullOrBlank()) {
            return
        }
        val lastUserIndex = indexOfLast { it.role == MessageRole.USER }
        if (lastUserIndex < 0) {
            return
        }
        val last = this[lastUserIndex]
        this[lastUserIndex] = last.copy(
            content = last.content + "\n\nRespond only with valid JSON matching this schema:\n$schemaJson",
        )
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
        requestDigest = sha256Hex(CanonicalMessageEncoder.encode(digestSource)),
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
            validateOperationAnnotation(method, operation)
            warnOnSystemPromptShadowing(method, systemAnnotations, classLevelSystemPrompt)

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

        /**
         * Validates operation annotation values before building executable metadata.
         */
        private fun validateOperationAnnotation(method: Method, operation: Operation) {
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
        }

        /**
         * Emits the precedence warning when method-level system messages shadow the class prompt.
         */
        private fun warnOnSystemPromptShadowing(
            method: Method,
            systemAnnotations: List<String>,
            classLevelSystemPrompt: String?,
        ) {
            if (systemAnnotations.isEmpty() || classLevelSystemPrompt.isNullOrBlank()) {
                return
            }
            val logger = System.getLogger("dev.tramai.engine.OperationDefinition")
            logger.log(
                System.Logger.Level.WARNING,
                "@System on ${method.declaringClass.name}.${method.name} takes precedence over @SystemPrompt on the class",
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
    val approvedModel: RegisteredModel?,
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
    private var totalInputTokensObserved: Long = 0
    private var totalOutputTokensObserved: Long = 0
    private var totalInputCostObserved: Double = 0.0
    private var totalOutputCostObserved: Double = 0.0
    private var softLimitReported: Boolean = false

    /**
     * Capture a snapshot of the current budget state for suspension.
     */
    fun snapshot(): TokenBudgetSnapshot = TokenBudgetSnapshot(
        totalInputTokens = totalInputTokensObserved,
        totalOutputTokens = totalOutputTokensObserved,
        totalInputCost = totalInputCostObserved,
        totalOutputCost = totalOutputCostObserved,
        warnIfExceeded = !softLimitReported,
    )

    /**
     * Restore budget state from a snapshot taken at suspension time.
     */
    fun restore(snapshot: TokenBudgetSnapshot) {
        totalInputTokensObserved = snapshot.totalInputTokens
        totalOutputTokensObserved = snapshot.totalOutputTokens
        totalInputCostObserved = snapshot.totalInputCost
        totalOutputCostObserved = snapshot.totalOutputCost
        softLimitReported = !snapshot.warnIfExceeded
    }

    fun observe(response: ModelResponse): TokenBudgetCheckResult {
        if (!isEnabled()) {
            return TokenBudgetCheckResult.Ok
        }

        val attemptInputTokens = response.inputTokens?.toLong() ?: return TokenBudgetCheckResult.UsageUnavailable
        val attemptOutputTokens = response.outputTokens?.toLong() ?: return TokenBudgetCheckResult.UsageUnavailable
        val attemptTokens = attemptInputTokens + attemptOutputTokens
        totalInputTokensObserved += attemptInputTokens
        totalOutputTokensObserved += attemptOutputTokens

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
            val totalTokensObserved = totalInputTokensObserved + totalOutputTokensObserved
            if (totalTokensObserved > limit) {
                return TokenBudgetCheckResult.HardLimitExceeded(
                    scope = "operation",
                    limitTokens = limit,
                    observedTokens = totalTokensObserved,
                )
            }
        }

        settings.softMaxTokensPerOperation?.let { limit ->
            val totalTokensObserved = totalInputTokensObserved + totalOutputTokensObserved
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

internal fun buildRequestDigest(messages: List<Message>): String = sha256Hex(CanonicalMessageEncoder.encode(messages))

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

/** @see TramaiEngine */
private const val UNREGISTERED_LABEL = "<unregistered>"

/** @see TramaiEngine */
private const val DLP_TOOL_REJECTED_METRIC = "tramai.dlp.tool_result_rejected"
