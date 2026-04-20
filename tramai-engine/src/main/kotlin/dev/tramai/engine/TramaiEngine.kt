package dev.tramai.engine

import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.ConfigurationException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.exception.TramaiException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
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
import dev.tramai.core.provider.ProviderRegistry
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.core.provider.StreamCapable
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
    private val job: Job = SupervisorJob(),
    private val scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
) : AutoCloseable {
    private val circuitBreaker = ProviderCircuitBreaker(circuitBreakerSettings)
    private val retryDelayPolicy = ProviderRetryDelayPolicy(retryPolicySettings)

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
        job: Job = SupervisorJob(),
        scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
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
        job = job,
        scope = scope,
    )

    /**
     * Creates a proxy implementation for the given Tramai service interface.
     */
    fun <T : Any> create(serviceType: KClass<T>): T {
        val definition = ServiceDefinition.create(serviceType, toolRegistry)
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
            scope = scope,
            serviceDefinition = definition,
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
    private val scope: CoroutineScope,
    private val serviceDefinition: ServiceDefinition,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) {
            return handleObjectMethod(proxy, method, args.orEmpty())
        }

        val operation = serviceDefinition.operations[method]
            ?: throw ConfigurationException("No operation metadata registered for ${method.name}")

        return if (operation.isSuspend) {
            invokeSuspend(operation, args.orEmpty())
        } else {
            runBlocking {
                execute(operation, args.orEmpty().toList())
            }
        }
    }

    private fun invokeSuspend(
        operation: OperationDefinition,
        args: Array<out Any?>,
    ): Any {
        // Kotlin suspend proxies receive the continuation as the last JVM argument.
        @Suppress("UNCHECKED_CAST")
        val continuation = args.lastOrNull() as? Continuation<Any?>
            ?: throw ConfigurationException("Suspend invocation for ${operation.method.name} is missing its continuation")

        val callArguments = args.dropLast(1)
        scope.launch(continuation.context) {
            runCatching { execute(operation, callArguments) }
                .onSuccess { continuation.resumeWith(Result.success(it)) }
                .onFailure { continuation.resumeWith(Result.failure(it)) }
        }
        return COROUTINE_SUSPENDED
    }

    private suspend fun execute(
        operation: OperationDefinition,
        arguments: List<Any?>,
    ): Any? {
        val tokenBudgetTracker = TokenBudgetTracker(tokenBudgetSettings)
        return when (operation.returnKind) {
            ReturnKind.STRING -> executeRaw(operation, arguments, tokenBudgetTracker)
            ReturnKind.UNIT -> {
                executeRaw(operation, arguments, tokenBudgetTracker)
                Unit
            }
            ReturnKind.STRUCTURED -> executeStructured(operation, arguments, tokenBudgetTracker)
            ReturnKind.STREAMING -> executeStreaming(operation, arguments, tokenBudgetTracker)
        }
    }

    private suspend fun executeStreaming(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
    ): Flow<StreamChunk> {
        return flow {
            var lastFailure: Throwable? = null
            var lastCircuitOpen: CircuitBreakerOpenException? = null
            val attemptCounter = AttemptCounter()

            for ((routeIndex, route) in providerRegistry.resolveCandidates(operation.operation).withIndex()) {
                val blockedUntil = circuitBreaker.beforeCall(route.providerName)
                if (blockedUntil != null) {
                    lastCircuitOpen = CircuitBreakerOpenException(route.providerName, blockedUntil)
                    continue
                }

                val streamCapable = route.provider as? StreamCapable
                    ?: throw ProviderCapabilityException(route.providerName, "streaming")
                val request = operation.toRequest(arguments, modelName = route.effectiveModelName)

                when (
                    val result = collectStreamingRoute(
                        streamCapable = streamCapable,
                        request = request,
                        operation = operation,
                        route = route,
                        attempt = attemptCounter.next(),
                        routeIndex = routeIndex,
                        tokenBudgetTracker = tokenBudgetTracker,
                        emitChunk = { emit(it) },
                    )
                ) {
                    is StreamingRouteResult.Completed -> return@flow
                    is StreamingRouteResult.StartupFailure -> lastFailure = result.error
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

        val callContext = OperationCallContext(
            serviceInterface = serviceDefinition.serviceType.qualifiedName ?: serviceDefinition.serviceType.simpleName.orEmpty(),
            methodName = operation.method.name,
            providerId = route.providerName,
            requestedModel = operation.operation.model,
            attempt = attempt,
        )

        val interceptedMessages = operationInterceptor.interceptRequest(callContext, request.messages)
        val interceptedRequest = request.copy(messages = interceptedMessages)

        val observation = startObservation(
            providerId = route.providerName,
            operation = operation,
            attempt = attempt,
        )

        observation.onEngineEvent(
            name = "tramai.route.selected",
            attributes = routeSelectedAttributes(route, routeIndex),
        )

        return try {
            withTimeout(request.timeoutMillis ?: operation.operation.timeoutMillis) {
                streamCapable.stream(interceptedRequest).collect { chunk ->
                    when (chunk) {
                        is StreamChunk.Token -> {
                            emittedAnyTokens = true
                            emitChunk(chunk)
                        }
                        is StreamChunk.Complete -> {
                            val response = ModelResponse(
                                content = chunk.fullText,
                                inputTokens = chunk.usage.inputTokens,
                                outputTokens = chunk.usage.outputTokens,
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

                            // Emit potentially modified terminal chunk
                            emitChunk(
                                if (interceptedResponse.content != chunk.fullText) {
                                    chunk.copy(fullText = interceptedResponse.content)
                                } else {
                                    chunk
                                }
                            )
                            throw StreamingRouteFinished(StreamingRouteResult.Completed)
                        }
                        is StreamChunk.Error -> {
                            observation.onProviderFailure(chunk.cause)
                            if (!emittedAnyTokens && shouldFallbackFrom(chunk.cause)) {
                                observation.onEngineEvent(
                                    name = "tramai.streaming.startup_retry",
                                    attributes = mapOf(
                                        "provider_id" to route.providerName,
                                        "failure_type" to (chunk.cause::class.simpleName ?: "unknown"),
                                    ),
                                )
                                val opened = circuitBreaker.onFailure(route.providerName, chunk.cause)
                                if (opened) {
                                    observation.onEngineEvent(
                                        name = "tramai.circuit.opened",
                                        attributes = mapOf("provider_id" to route.providerName),
                                    )
                                }
                                observation.onCallCompleted(parseSuccess = null)
                                throw StreamingRouteFinished(StreamingRouteResult.StartupFailure(chunk.cause))
                            }

                            val opened = circuitBreaker.onFailure(route.providerName, chunk.cause)
                            if (opened) {
                                observation.onEngineEvent(
                                    name = "tramai.circuit.opened",
                                    attributes = mapOf("provider_id" to route.providerName),
                                )
                            }
                            observation.onCallCompleted(parseSuccess = null)
                            throw StreamingRouteFinished(StreamingRouteResult.TerminalError(chunk))
                        }
                    }
                }

                val error = ProviderException(
                    message = "Provider ${route.providerName} ended streaming without a terminal chunk while invoking ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}",
                )
                observation.onProviderFailure(error)
                if (!emittedAnyTokens && shouldFallbackFrom(error)) {
                    observation.onEngineEvent(
                        name = "tramai.streaming.startup_retry",
                        attributes = mapOf(
                            "provider_id" to route.providerName,
                            "failure_type" to "ProviderException",
                        ),
                    )
                    val opened = circuitBreaker.onFailure(route.providerName, error)
                    if (opened) {
                        observation.onEngineEvent(
                            name = "tramai.circuit.opened",
                            attributes = mapOf("provider_id" to route.providerName),
                        )
                    }
                    observation.onCallCompleted(parseSuccess = null)
                    throw StreamingRouteFinished(StreamingRouteResult.StartupFailure(error))
                }

                val opened = circuitBreaker.onFailure(route.providerName, error)
                if (opened) {
                    observation.onEngineEvent(
                        name = "tramai.circuit.opened",
                        attributes = mapOf("provider_id" to route.providerName),
                    )
                }
                observation.onCallCompleted(parseSuccess = null)
                throw StreamingRouteFinished(StreamingRouteResult.TerminalError(StreamChunk.Error(error)))
            }
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
            if (!emittedAnyTokens && shouldFallbackFrom(timeout)) {
                observation.onEngineEvent(
                    name = "tramai.streaming.startup_retry",
                    attributes = mapOf(
                        "provider_id" to route.providerName,
                        "failure_type" to "TimeoutException",
                    ),
                )
                val opened = circuitBreaker.onFailure(route.providerName, timeout)
                if (opened) {
                    observation.onEngineEvent(
                        name = "tramai.circuit.opened",
                        attributes = mapOf("provider_id" to route.providerName),
                    )
                }
                observation.onCallCompleted(parseSuccess = null)
                StreamingRouteResult.StartupFailure(timeout)
            } else {
                val opened = circuitBreaker.onFailure(route.providerName, timeout)
                if (opened) {
                    observation.onEngineEvent(
                        name = "tramai.circuit.opened",
                        attributes = mapOf("provider_id" to route.providerName),
                    )
                }
                observation.onCallCompleted(parseSuccess = null)
                StreamingRouteResult.TerminalError(StreamChunk.Error(timeout))
            }
        } catch (error: CancellationException) {
            val cancellation = CancellationException("Streaming operation was cancelled by the consumer")
            cancellation.initCause(error)
            observation.onProviderFailure(cancellation)
            observation.onCallCompleted(parseSuccess = null)
            throw error
        } catch (error: Throwable) {
            val normalized = when (error) {
                is TramaiException -> error
                else -> ProviderException(
                    message = "Provider ${route.providerName} failed while streaming ${serviceDefinition.serviceType.qualifiedName}.${operation.method.name}",
                    cause = error,
                )
            }
            observation.onProviderFailure(normalized)
            if (!emittedAnyTokens && shouldFallbackFrom(normalized)) {
                observation.onEngineEvent(
                    name = "tramai.streaming.startup_retry",
                    attributes = mapOf(
                        "provider_id" to route.providerName,
                        "failure_type" to (normalized::class.simpleName ?: "unknown"),
                    ),
                )
                val opened = circuitBreaker.onFailure(route.providerName, normalized)
                if (opened) {
                    observation.onEngineEvent(
                        name = "tramai.circuit.opened",
                        attributes = mapOf("provider_id" to route.providerName),
                    )
                }
                observation.onCallCompleted(parseSuccess = null)
                StreamingRouteResult.StartupFailure(normalized)
            } else {
                val opened = circuitBreaker.onFailure(route.providerName, normalized)
                if (opened) {
                    observation.onEngineEvent(
                        name = "tramai.circuit.opened",
                        attributes = mapOf("provider_id" to route.providerName),
                    )
                }
                observation.onCallCompleted(parseSuccess = null)
                StreamingRouteResult.TerminalError(StreamChunk.Error(normalized))
            }
        }
    }

    private suspend fun executeRaw(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
    ): String {
        operation.cachedValue(arguments)?.let { return it as String }
        val messages = operation.initialMessages(arguments).toMutableList()
        val result = executeWithTools(operation, messages, tokenBudgetTracker)
        result.observation.onCallCompleted(parseSuccess = null)
        return result.response.content.also { operation.cacheValue(arguments, it) }
    }

    private suspend fun executeStructured(
        operation: OperationDefinition,
        arguments: List<Any?>,
        tokenBudgetTracker: TokenBudgetTracker,
    ): Any {
        val handler = structuredOutputHandler ?: throw ConfigurationException(
            "Structured return type ${operation.returnTypeDescription} requires a StructuredOutputHandler implementation from tramai-structured",
        )
        val contract = operation.structuredContract(handler)
        operation.cachedValue(arguments, contract.schemaJson)?.let { return it }
        val messages = operation.initialMessages(arguments, contract.schemaJson).toMutableList()
        val maxAttempts = operation.operation.maxRetries + 1

        repeat(maxAttempts) { attemptIndex ->
            val result = executeWithTools(operation, messages, tokenBudgetTracker)
            when (
                val analysis = handler.analyze(
                    rawResponse = result.response.content,
                    targetType = operation.returnType,
                )
            ) {
                is StructuredOutputResult.Success -> {
                    result.observation.onCallCompleted(parseSuccess = true)
                    operation.cacheValue(arguments, analysis.value, contract.schemaJson)
                    return analysis.value
                }
                is StructuredOutputResult.Failure -> {
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
            }
        }

        error("Structured retry loop exited without returning or throwing")
    }

    private suspend fun executeWithTools(
        operation: OperationDefinition,
        messages: MutableList<Message>,
        tokenBudgetTracker: TokenBudgetTracker,
    ): ProviderCallResult {
        val maxToolLoops = 5 // Guard against infinite tool loops
        val attemptCounter = AttemptCounter()
        repeat(maxToolLoops) {
            val result = callProviderWithFallbacks(
                operation = operation,
                messages = messages,
                attemptCounter = attemptCounter,
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

            // Execute each tool and append results
            for (toolCall in toolCalls) {
                val tool = toolRegistry.resolve(toolCall.name)
                val toolResult = if (tool == null) {
                    ToolResult.PermanentFailure("Tool '${toolCall.name}' not found")
                } else {
                    executeTool(tool, toolCall, operation)
                }

                messages += when (toolResult) {
                    is ToolResult.Success -> Message(
                        role = MessageRole.TOOL,
                        content = toolResult.value.toString(),
                        toolCallId = toolCall.id,
                    )
                    is ToolResult.InvalidInput -> Message(
                        role = MessageRole.TOOL,
                        content = "Error: ${toolResult.message}",
                        toolCallId = toolCall.id,
                    )
                    is ToolResult.PermanentFailure -> Message(
                        role = MessageRole.TOOL,
                        content = "Permanent error: ${toolResult.message}",
                        toolCallId = toolCall.id,
                    )
                    is ToolResult.TransientFailure -> error("TransientFailure should be resolved inside executeTool")
                }
            }
        }
        error("Exceeded maximum tool call loops ($maxToolLoops)")
    }

    private suspend fun callProviderWithFallbacks(
        operation: OperationDefinition,
        messages: List<Message>,
        attemptCounter: AttemptCounter,
    ): ProviderCallResult {
        var lastFallbackFailure: Throwable? = null
        var lastCircuitOpen: CircuitBreakerOpenException? = null

        for ((routeIndex, route) in providerRegistry.resolveCandidates(operation.operation).withIndex()) {
            val blockedUntil = circuitBreaker.beforeCall(route.providerName)
            if (blockedUntil != null) {
                lastCircuitOpen = CircuitBreakerOpenException(route.providerName, blockedUntil)
                continue
            }

            try {
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
                )
            } catch (error: Throwable) {
                if (!shouldFallbackFrom(error)) {
                    throw error
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
                name = "tramai.route.selected",
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
                            name = "tramai.circuit.opened",
                            attributes = mapOf("provider_id" to providerId),
                        )
                    }
                    throw error
                }

                val delayMillis = providerRetryDelayMillis(retryIndex, error)
                observation.onEngineEvent(
                    name = "tramai.retry.scheduled",
                    attributes = mapOf(
                        "provider_id" to providerId,
                        "retry_index" to retryIndex,
                        "delay_millis" to delayMillis,
                        "delay_source" to retryDelaySource(error),
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
                    "provider_id" to providerId,
                    "effective_model" to modelName,
                ),
            )
            is TokenBudgetCheckResult.SoftLimitExceeded -> observation.onEngineEvent(
                name = "tramai.token_budget.soft_limit_exceeded",
                attributes = mapOf(
                    "provider_id" to providerId,
                    "effective_model" to modelName,
                    "limit_tokens" to result.limitTokens,
                    "observed_tokens" to result.observedTokens,
                    "scope" to "operation",
                ),
            )
            is TokenBudgetCheckResult.HardLimitExceeded -> {
                observation.onEngineEvent(
                    name = "tramai.token_budget.hard_limit_exceeded",
                    attributes = mapOf(
                        "provider_id" to providerId,
                        "effective_model" to modelName,
                        "limit_tokens" to result.limitTokens,
                        "observed_tokens" to result.observedTokens,
                        "scope" to result.scope,
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
        "provider_id" to route.providerName,
        "effective_model" to route.effectiveModelName,
        "route_index" to routeIndex,
        "is_fallback" to (routeIndex > 0),
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
        fun create(serviceType: KClass<*>, toolRegistry: ToolRegistry): ServiceDefinition {
            val javaType = serviceType.java
            if (!javaType.isInterface) {
                throw ConfigurationException("${serviceType.qualifiedName} must be an interface")
            }
            if (serviceType.annotations.none { it is AiService }) {
                throw ConfigurationException("${serviceType.qualifiedName} must be annotated with @AiService")
            }

            val systemPrompt = serviceType.java.getAnnotation(SystemPrompt::class.java)?.value?.takeIf { it.isNotBlank() }
            val operations = javaType.methods
                .filterNot { it.declaringClass == Any::class.java }
                .associateWith { method ->
                    val operation = method.getAnnotation(Operation::class.java)
                        ?: throw ConfigurationException("${serviceType.qualifiedName}.${method.name} must be annotated with @Operation")

                    val toolDefinitions = operation.tools.map { toolName ->
                        val tool = toolRegistry.resolve(toolName)
                            ?: throw ConfigurationException("Tool '$toolName' requested by ${method.name} is not registered in the engine")
                        ToolDefinition(tool.name, tool.description, tool.inputSchemaJson)
                    }

                    OperationDefinition.create(method, operation, systemPrompt, toolDefinitions)
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
    val systemPrompt: String?,
    val isSuspend: Boolean,
    val parameterNames: List<String>,
    val returnKind: ReturnKind,
    val returnType: kotlin.reflect.KType,
    val returnTypeDescription: String,
    val toolDefinitions: List<ToolDefinition>,
) {
    val isCacheEligible: Boolean
        get() = operation.cacheable && returnKind != ReturnKind.STREAMING && toolDefinitions.isEmpty()

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
        val messages = buildList {
            if (!systemPrompt.isNullOrBlank()) {
                add(Message(role = MessageRole.SYSTEM, content = systemPrompt))
            }

            val userMessage = buildString {
                append(operation.prompt)
                if (!schemaJson.isNullOrBlank()) {
                    append("\n\nRespond only with valid JSON matching this schema:")
                    append("\n")
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

    fun structuredContract(handler: StructuredOutputHandler) = handler.createContract(returnType)

    companion object {
        fun create(
            method: Method,
            operation: Operation,
            systemPrompt: String?,
            toolDefinitions: List<ToolDefinition> = emptyList(),
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

            val kotlinFunction = method.kotlinFunction
            val isSuspend = kotlinFunction?.isSuspend == true
            val parameterNames = resolveParameterNames(method, kotlinFunction)
            val returnType = resolveReturnType(method, kotlinFunction)
            val returnKind = resolveReturnKind(method, kotlinFunction)
            val returnTypeDescription = resolveReturnTypeDescription(method, kotlinFunction)

            return OperationDefinition(
                method = method,
                operation = operation,
                systemPrompt = systemPrompt,
                isSuspend = isSuspend,
                parameterNames = parameterNames,
                returnKind = returnKind,
                returnType = returnType,
                returnTypeDescription = returnTypeDescription,
                toolDefinitions = toolDefinitions,
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
            kotlinFunction: KFunction<*>?,
        ): ReturnKind {
            val type = resolveReturnType(method, kotlinFunction)
            val classifier = type.classifier
            return when (classifier) {
                String::class -> ReturnKind.STRING
                Unit::class -> ReturnKind.UNIT
                kotlinx.coroutines.flow.Flow::class -> ReturnKind.STREAMING
                null -> when (method.returnType) {
                    String::class.java -> ReturnKind.STRING
                    Void.TYPE -> ReturnKind.UNIT
                    else -> ReturnKind.STRUCTURED
                }
                else -> ReturnKind.STRUCTURED
            }
        }

        private fun resolveReturnType(
            method: Method,
            kotlinFunction: KFunction<*>?,
        ) = kotlinFunction?.returnType
            ?: throw ConfigurationException("Method ${method.name} must be a Kotlin-declared function so Tramai can inspect its return type")

        private fun resolveReturnTypeDescription(
            method: Method,
            kotlinFunction: KFunction<*>?,
        ): String = resolveReturnType(method, kotlinFunction).toString()
    }
}

private data class ProviderCallResult(
    val response: ModelResponse,
    val observation: OperationObservation,
    val providerId: String,
    val modelName: String,
)

private sealed class StreamingRouteResult {
    data object Completed : StreamingRouteResult()

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
