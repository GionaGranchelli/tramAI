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
import dev.tramai.core.exception.ApprovalRequiredException
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
import dev.tramai.security.DefaultPolicyEngine
import dev.tramai.security.PolicyConfiguration
import dev.tramai.core.security.PromptSanitizer
import dev.tramai.core.structured.StructuredOutputHandler
import dev.tramai.core.structured.StructuredOutputResult
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction

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
) : AutoCloseable {
    private val circuitBreaker = ProviderCircuitBreaker(circuitBreakerSettings)
    private val retryDelayPolicy = ProviderRetryDelayPolicy(retryPolicySettings)
    private val migrationWarningGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private val resolvedPolicyEngine: PolicyEngine = policyEngine
        ?: DefaultPolicyEngine(PolicyConfiguration.preview())

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
        )

        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(
            serviceType.java.classLoader,
            arrayOf(serviceType.java),
            handler,
        ) as T
    }

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

private class TramaiInvocationHandler(
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
) : InvocationHandler {

    private val policyHelper = PolicyEnforcementHelper(policyEngine, migrationWarningGuard)

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
        return when (operation.returnKind) {
            ReturnKind.STRING -> executeRaw(operation, arguments, tokenBudgetTracker, conversationId)
            ReturnKind.UNIT -> {
                executeRaw(operation, arguments, tokenBudgetTracker, conversationId)
                Unit
            }
            ReturnKind.STRUCTURED -> executeStructured(operation, arguments, tokenBudgetTracker, conversationId)
            ReturnKind.STREAMING -> executeStreaming(operation, arguments, tokenBudgetTracker, conversationId)
        }
    }

    private suspend fun executeStreaming(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
    ): Flow<StreamChunk> {
        val memoryInjection = injectMemoryMessages(operation, arguments, conversationId)
        val historySize = memoryInjection?.first?.size ?: 0
        val memoryMessages = memoryInjection?.second
        val correlationId = java.util.UUID.randomUUID().toString()

        // Enforce BEFORE_PROVIDER_RESOLUTION before resolving routes
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_RESOLUTION,
                correlationId = correlationId,
            ).modelName(operation.operation.model).build()
        )

        // Enforce BEFORE_RESPONSE_RETURN before any token is emitted.
        // Streaming tokens are externally visible immediately, so this is a preemptive check.
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = correlationId,
            ).build()
        )

        val candidates = providerRegistry.resolveCandidates(operation.operation)

        return flow {
            var lastFailure: Throwable? = null
            var lastCircuitOpen: CircuitBreakerOpenException? = null
            val attemptCounter = AttemptCounter()

            for ((routeIndex, route) in candidates.withIndex()) {
                val blockedUntil = circuitBreaker.beforeCall(route.providerName)
                if (blockedUntil != null) {
                    lastCircuitOpen = CircuitBreakerOpenException(route.providerName, blockedUntil)
                    continue
                }

                // Enforce BEFORE_TOOL_EXPOSURE per tool definition
                operation.toolDefinitions.forEach { toolDef ->
                    policyHelper.enforce(
                        policyHelper.buildContext(
                            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_EXPOSURE,
                            correlationId = correlationId,
                        ).toolName(toolDef.name).build()
                    )
                }

                // Enforce BEFORE_PROVIDER_INVOCATION
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_INVOCATION,
                        correlationId = correlationId,
                    ).providerId(route.providerName).modelName(route.effectiveModelName).build()
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
                            policyHelper.enforce(
                                policyHelper.buildContext(
                                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_FALLBACK,
                                    correlationId = correlationId,
                                ).providerId(route.providerName)
                                    .modelName(route.effectiveModelName)
                                    .fallbackProviderId(nextRoute.providerName)
                                    .build()
                            )
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
    ): String {
        val correlationId = java.util.UUID.randomUUID().toString()

        // Enforce BEFORE_RESPONSE_RETURN before returning cached value
        operation.cachedValue(arguments)?.let { cached ->
            policyHelper.enforce(
                policyHelper.buildContext(
                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    correlationId = correlationId,
                ).build()
            )
            return cached as String
        }

        val messages = operation.initialMessages(arguments).toMutableList()
        val (history, effectiveMessages) = injectMemoryMessages(messages, conversationId)
            ?: (emptyList<Message>() to messages.toList())
        val effectiveMutableMessages = effectiveMessages.toMutableList()

        val result = executeWithTools(operation, effectiveMutableMessages, tokenBudgetTracker, correlationId)

        // Enforce BEFORE_RESPONSE_RETURN
        policyHelper.enforce(
            policyHelper.buildContext(
                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                correlationId = correlationId,
            ).build()
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
        return result.response.content.also { operation.cacheValue(arguments, it) }
    }

    private suspend fun executeStructured(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
    ): Any {
        val handler = structuredOutputHandler ?: throw ConfigurationException(
            "Structured return type ${operation.returnTypeDescription} requires a StructuredOutputHandler implementation from tramai-structured",
        )
        val contract = operation.structuredContract(handler)
        val correlationId = java.util.UUID.randomUUID().toString()

        // Enforce BEFORE_RESPONSE_RETURN before returning cached value
        operation.cachedValue(arguments, contract.schemaJson)?.let { cached ->
            policyHelper.enforce(
                policyHelper.buildContext(
                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                    correlationId = correlationId,
                ).build()
            )
            return cached
        }

        val messages = operation.initialMessages(arguments, contract.schemaJson).toMutableList()
        val (history, effectiveMessages) = injectMemoryMessages(messages, conversationId)
            ?: (emptyList<Message>() to messages.toList())

        // Re-initialize messages list with history-injected content
        val initialTurnCount = history.size
        messages.clear()
        messages.addAll(effectiveMessages)

        return executeStructuredRetryLoop(
            operation = operation,
            arguments = arguments,
            schemaJson = contract.schemaJson,
            handler = handler,
            messages = messages,
            historySize = initialTurnCount,
            tokenBudgetTracker = tokenBudgetTracker,
            conversationId = conversationId,
            correlationId = correlationId,
        )
    }

    private suspend fun executeStructuredRetryLoop(
        operation: OperationDefinition,
        arguments: List<Any?>,
        schemaJson: String,
        handler: StructuredOutputHandler,
        messages: MutableList<Message>,
        historySize: Int,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
        correlationId: String,
    ): Any {
        val maxAttempts = operation.operation.maxRetries + 1
        val targetType = requireNotNull(operation.returnType) {
            "Structured return type ${operation.returnTypeDescription} could not be inspected without Kotlin reflection metadata"
        }

        repeat(maxAttempts) { attemptIndex ->
            val value = executeStructuredAttempt(
                operation = operation,
                arguments = arguments,
                schemaJson = schemaJson,
                handler = handler,
                messages = messages,
                historySize = historySize,
                tokenBudgetTracker = tokenBudgetTracker,
                conversationId = conversationId,
                targetType = targetType,
                attemptIndex = attemptIndex,
                maxAttempts = maxAttempts,
                correlationId = correlationId,
            )
            if (value != null) {
                return value
            }
        }

        error("Structured retry loop exited without returning or throwing")
    }

    private suspend fun executeStructuredAttempt(
        operation: OperationDefinition,
        arguments: List<Any?>,
        schemaJson: String,
        handler: StructuredOutputHandler,
        messages: MutableList<Message>,
        historySize: Int,
        tokenBudgetTracker: TokenBudgetTracker,
        conversationId: String?,
        targetType: kotlin.reflect.KType,
        attemptIndex: Int,
        maxAttempts: Int,
        correlationId: String,
    ): Any? {
        val messagesBeforeCall = messages.size
        val result = executeWithTools(operation, messages, tokenBudgetTracker, correlationId)
        return when (
            val analysis = handler.analyze(
                rawResponse = result.response.content,
                targetType = targetType,
            )
        ) {
            is StructuredOutputResult.Success -> {
                result.observation.onCallCompleted(parseSuccess = true)
                persistStructuredSuccess(
                    result = result,
                    messages = messages,
                    historySize = historySize,
                    conversationId = conversationId,
                    messagesBeforeCall = messagesBeforeCall,
                )
                operation.cacheValue(arguments, analysis.value, schemaJson)

                // Enforce BEFORE_RESPONSE_RETURN before returning parsed value
                policyHelper.enforce(
                    policyHelper.buildContext(
                        enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_RESPONSE_RETURN,
                        correlationId = correlationId,
                    ).build()
                )

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
        val assistantMessage = Message(
            role = MessageRole.ASSISTANT,
            content = result.response.content,
            toolCalls = result.response.toolCalls,
        )
        val userPrompt = messages.subList(historySize, messagesBeforeCall)
            .filter { it.role != MessageRole.SYSTEM }
        val toolMessages = messages.drop(messagesBeforeCall)
        chatMemory.add(conversationId, userPrompt + toolMessages + assistantMessage)
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
    ): ProviderCallResult {
        val maxToolLoops = 5 // Guard against infinite tool loops
        val attemptCounter = AttemptCounter()
        repeat(maxToolLoops) {
            val result = callProviderWithFallbacks(
                operation = operation,
                messages = messages,
                attemptCounter = attemptCounter,
                correlationId = correlationId,
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

            // End the observation of the call that produced tool calls
            result.observation.onCallCompleted(parseSuccess = null)

            // Append assistant message with tool calls
            messages += Message(
                role = MessageRole.ASSISTANT,
                content = result.response.content,
                toolCalls = toolCalls,
            )
            processToolCalls(operation, toolCalls, messages, correlationId)
        }
        error("Exceeded maximum tool call loops ($maxToolLoops)")
    }

    private suspend fun processToolCalls(
        operation: OperationDefinition,
        toolCalls: List<ToolCall>,
        messages: MutableList<Message>,
        correlationId: String,
    ) {
        for (toolCall in toolCalls) {
            val tool = toolRegistry.resolve(toolCall.name)
            val toolResult = if (tool == null) {
                ToolResult.PermanentFailure("Tool '${toolCall.name}' not found")
            } else {
                executeTool(tool, toolCall, operation, correlationId)
            }

            // Enforce BEFORE_TOOL_RESULT_REINJECTION
            policyHelper.enforce(
                policyHelper.buildContext(
                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_RESULT_REINJECTION,
                    correlationId = correlationId,
                ).toolName(toolCall.name).build()
            )

            messages += formatToolResult(toolResult, toolCall.id)
        }
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
    ): ProviderCallResult {
        var lastFallbackFailure: Throwable? = null
        var lastCircuitOpen: CircuitBreakerOpenException? = null

        val base = policyHelper.buildContext(
            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_PROVIDER_RESOLUTION,
            correlationId = correlationId,
        ).providerId(null).modelName(operation.operation.model).build()
        policyHelper.enforce(base)

        val candidates = providerRegistry.resolveCandidates(operation.operation)

        for ((routeIndex, route) in candidates.withIndex()) {
            val blockedUntil = circuitBreaker.beforeCall(route.providerName)
            if (blockedUntil != null) {
                lastCircuitOpen = CircuitBreakerOpenException(route.providerName, blockedUntil)
                continue
            }

            try {
                // Enforce BEFORE_TOOL_EXPOSURE per tool definition
                operation.toolDefinitions.forEach { toolDef ->
                    policyHelper.enforce(
                        policyHelper.buildContext(
                            enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_EXPOSURE,
                            correlationId = correlationId,
                        ).toolName(toolDef.name).build()
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
                )
            } catch (error: Throwable) {
                if (!shouldFallbackFrom(error)) {
                    throw error
                }
                // Only enforce BEFORE_FALLBACK when another candidate exists
                val nextRoute = candidates.getOrNull(routeIndex + 1)
                if (nextRoute != null) {
                    try {
                        policyHelper.enforce(
                            policyHelper.buildContext(
                                enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_FALLBACK,
                                correlationId = correlationId,
                            ).providerId(route.providerName)
                                .modelName(route.effectiveModelName)
                                .fallbackProviderId(nextRoute.providerName)
                                .build()
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
                    ).providerId(providerId).modelName(request.model).build()
                )

                val rawResponse = callProviderOnce(providerId, provider, interceptedRequest, operation)
                val interceptedResponse = operationInterceptor.interceptResponse(callContext, rawResponse)

                observation.onProviderResponse(interceptedResponse)
                return ProviderCallResult(
                    response = interceptedResponse,
                    observation = observation,
                    providerId = providerId,
                    modelName = request.model,
                )
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

            // Enforce BEFORE_TOOL_EXECUTION
            policyHelper.enforce(
                policyHelper.buildContext(
                    enforcementPoint = dev.tramai.core.policy.EnforcementPoint.BEFORE_TOOL_EXECUTION,
                    correlationId = correlationId,
                ).toolName(tool.name).build()
            )

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

    private fun OperationDefinition.cachedValue(
        arguments: List<Any?>,
        schemaJson: String? = null,
    ): Any? = if (isCacheEligible) {
        responseCache.get(cacheKey(arguments, schemaJson))
    } else {
        null
    }

    private fun OperationDefinition.cacheValue(
        arguments: List<Any?>,
        value: Any,
        schemaJson: String? = null,
    ) {
        if (!isCacheEligible) {
            return
        }
        responseCache.put(
            key = cacheKey(arguments, schemaJson),
            value = value,
            ttlMillis = operation.cacheTtlMillis,
        )
    }
}

private data class ServiceDefinition(
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

private data class OperationDefinition(
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
    val isCacheEligible: Boolean
        get() = operation.cacheable && returnKind != ReturnKind.STREAMING && toolDefinitions.isEmpty()

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
        val rendered = argument?.toString() ?: ""
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
    ): OperationCacheKey = OperationCacheKey(
        serviceInterface = method.declaringClass.name,
        methodName = method.name,
        requestedModel = operation.model,
        explicitProvider = operation.provider.takeIf { it.isNotBlank() },
        messages = initialMessages(arguments, schemaJson).map { message ->
            CachedMessage(
                role = message.role.name,
                content = message.content,
            )
        },
    )

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

private class ProviderCircuitBreaker(
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

private class ProviderRetryDelayPolicy(
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

private enum class ReturnKind {
    STRING,
    UNIT,
    STRUCTURED,
    STREAMING,
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
