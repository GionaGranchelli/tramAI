package dev.tramai.engine.streaming

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.exception.TokenBudgetExceededException
import dev.tramai.core.exception.TramaiException
import dev.tramai.core.model.FinishReason
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.core.provider.StreamCapable
import dev.tramai.core.provider.resolveCandidates
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ModelRegistryEnforcer
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.emitRuntimeEvent
import dev.tramai.engine.budget.TokenBudgetCoordinator
import dev.tramai.engine.budget.TokenBudgetTracker
import dev.tramai.engine.memory.ConversationMemoryCoordinator
import dev.tramai.engine.memory.PersistConversationTurnRequest
import dev.tramai.engine.provider.AttemptCounter
import dev.tramai.engine.provider.ProviderFallbackGate
import dev.tramai.engine.provider.ProviderInvocationGate
import dev.tramai.engine.provider.ProviderResolutionGate
import dev.tramai.engine.tool.ToolExposureCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface StreamingBeforeResponseReturnGate {
    suspend fun enforce(route: ResolvedProviderRoute, correlationId: String, securityContext: ExecutionSecurityContext)
}

internal class StreamingExecutionCoordinator(
    private val routingPlan: ProviderRoutingPlan,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val lifecycleScope: CoroutineScope,
    private val isClosed: AtomicBoolean,
    private val serviceTypeName: String,
    private val qualifiedServiceName: String?,
    private val operationObserver: OperationObserver,
    private val operationInterceptor: OperationInterceptor,
    private val toolExposureCoordinator: ToolExposureCoordinator,
    private val conversationMemoryCoordinator: ConversationMemoryCoordinator,
    private val tokenBudgetCoordinator: TokenBudgetCoordinator,
    private val modelRegistryEnforcer: ModelRegistryEnforcer,
    private val beforeResolution: ProviderResolutionGate,
    private val beforeInvocation: ProviderInvocationGate,
    private val fallbackGate: ProviderFallbackGate,
    private val beforeResponseReturn: StreamingBeforeResponseReturnGate,
) {
    fun execute(request: StreamingExecutionRequest): Flow<StreamChunk> {
        val operation = request.operation
        val arguments = request.arguments
        val tokenBudgetTracker = request.tokenBudgetTracker
        val conversationId = request.conversationId
        val securityContext = ExecutionSecurityContext.fromArguments(arguments.toTypedArray())
        val initialMessages = operation.initialMessages(arguments)
        val prepared = conversationMemoryCoordinator.prepareMessages(initialMessages, conversationId)
        val history = prepared?.history ?: emptyList()
        val effectiveMessages = prepared?.effectiveMessages ?: initialMessages

        return flow {
            check(!isClosed.get()) { "Tramai runtime is closed" }
            // The provider collection runs as a child of the engine's OWN
            // lifecycle job (lifecycleScope), NOT the collector's job: close()
            // cancels lifecycleJob, which cancels an in-flight collection
            // (including the provider stream's cleanup), and close() joins
            // lifecycleJob — so the collection has terminated before close()
            // returns. Chunks are bridged to the caller's emit through a
            // RENDEZVOUS channel: emit must stay in the collector's coroutine
            // (SafeCollector invariant), and a rendezvous keeps Flow
            // backpressure semantics — a slow caller blocks the provider
            // instead of letting it race ahead into unbounded buffering.
            val chunks = Channel<StreamChunk>(Channel.RENDEZVOUS)
            val collectFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val collectJob = lifecycleScope.launch {
                try {
                    val correlationId = java.util.UUID.randomUUID().toString()
                    beforeResolution.beforeResolution(operation, correlationId, securityContext)
                    val candidates = routingPlan.resolveCandidates(operation.operation)
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
                                    memoryMessages = effectiveMessages,
                                    historySize = history.size,
                                    conversationId = conversationId,
                                    emitChunk = { chunks.send(it) },
                                ),
                                correlationId = correlationId,
                                securityContext = securityContext,
                                arguments = arguments,
                            )
                        ) {
                            is StreamingRouteResult.Completed -> {
                                if (conversationId != null) {
                                    val assistantMessage = Message(
                                        role = MessageRole.ASSISTANT,
                                        content = result.fullText,
                                    )
                                    conversationMemoryCoordinator.persistTurn(
                                        PersistConversationTurnRequest(conversationId, effectiveMessages, history.size, assistantMessage),
                                    )
                                }
                                return@launch
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
                                chunks.send(result.errorChunk)
                                return@launch
                            }
                        }
                    }

                    chunks.send(noAvailableStreamingRouteChunk(operation, lastFailure, lastCircuitOpen))
                } catch (e: CancellationException) {
                    // The engine closed (or the collector stopped): terminate
                    // the collection job normally; the invokeOnCompletion
                    // below closes the channel with the cancellation cause.
                    throw e
                } catch (failure: Throwable) {
                    failure.rethrowIfCancellation()
                    // Surface the failure to the collector WITHOUT rethrowing
                    // it here: rethrowing would let an arbitrary (possibly
                    // sensitive, externally supplied) throwable reach the
                    // lifecycle scope's CoroutineExceptionHandler and the
                    // normal logger. The collector rethrows it after the
                    // channel drains.
                    collectFailure.set(failure)
                }
            }
            // Channel termination depends on JOB completion, not on the
            // coroutine body having started: if close() cancels lifecycleJob
            // after the flow's open check but before this launch's body runs,
            // the body's finally never executes — but invokeOnCompletion still
            // fires, closing the channel so the collector terminates instead
            // of hanging forever on receive.
            collectJob.invokeOnCompletion { cause -> chunks.close(cause) }
            try {
                for (chunk in chunks) {
                    check(!isClosed.get()) { "Tramai runtime is closed" }
                    emit(chunk)
                }
                // The collection job may have failed (e.g. provider does not
                // support streaming, or a route error aborted the loop): the
                // channel closes either way, so rethrow the job's failure here
                // instead of silently completing the flow.
                collectFailure.get()?.let { throw it }
            } finally {
                // If the caller stops collecting (or the engine closed), the
                // engine-owned collection job must not keep running.
                collectJob.cancel()
                collectJob.join()
            }
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

    private suspend fun executeStreamingRoute(request: StreamingExecutionRoute, correlationId: String, securityContext: ExecutionSecurityContext, arguments: List<Any?>): StreamingRouteResult {
        val route = request.route
        val observation = startStreamingObservation(route, request.operation, request.attempt, request.routeIndex)
        authorizeStreamingRoute(route, observation)
        beforeResponseReturn.enforce(route, correlationId, securityContext)
        toolExposureCoordinator.enforce(request.operation, correlationId, securityContext)
        beforeInvocation.invoke(route.providerName, route.effectiveModelName, correlationId, securityContext)

        val streamCapable = route.provider as? StreamCapable ?: throw ProviderCapabilityException(route.providerName, "streaming")
        val modelRequest = request.operation.toRequest(arguments, modelName = route.effectiveModelName)
        val memoryInjectedRequest = request.memoryMessages?.let { modelRequest.copy(messages = it) } ?: modelRequest
        return collectStreamingRoute(StreamingRouteCall(streamCapable, memoryInjectedRequest, request.operation, route, request.attempt, observation, request.tokenBudgetTracker, request.emitChunk))
    }

    private suspend fun authorizeStreamingRoute(route: ResolvedProviderRoute, observation: OperationObservation) {
        try {
            modelRegistryEnforcer.authorize(route.providerName, route.effectiveModelName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelRegistryException) {
            observation.onCallCompleted(parseSuccess = null)
            throw e
        }
    }

    private suspend fun handleCircuitBreakerOpenRoute(route: ResolvedProviderRoute, nextRoute: ResolvedProviderRoute?, correlationId: String, securityContext: ExecutionSecurityContext): CircuitBreakerOpenException? {
        val blockedUntil = circuitBreaker.beforeCall(route.providerName) ?: return null
        val circuitOpen = CircuitBreakerOpenException(route.providerName, blockedUntil)
        if (nextRoute != null) {
            try {
                fallbackGate.transition(correlationId, route.providerName, route.effectiveModelName, nextRoute.providerName, "circuit-breaker-open", securityContext)
            } catch (policyError: PolicyViolationException) {
                policyError.addSuppressed(circuitOpen)
                throw policyError
            }
        }
        return circuitOpen
    }

    private suspend fun enforceStreamingFallbackAfterFailure(error: Throwable, route: ResolvedProviderRoute, nextRoute: ResolvedProviderRoute?, correlationId: String, securityContext: ExecutionSecurityContext) {
        if (nextRoute == null) return
        try {
            fallbackGate.transition(correlationId, route.providerName, route.effectiveModelName, nextRoute.providerName, "streaming-startup-failure", securityContext)
        } catch (policyError: PolicyViolationException) {
            policyError.addSuppressed(error)
            throw policyError
        }
    }

    private fun noAvailableStreamingRouteChunk(operation: OperationDefinition, lastFailure: Throwable?, lastCircuitOpen: CircuitBreakerOpenException?): StreamChunk.Error = StreamChunk.Error((lastFailure ?: lastCircuitOpen ?: ProviderException(message = "No available streaming provider route for model '${operation.operation.model}'", retryable = true)) as TramaiException)

    private suspend fun collectStreamingRoute(call: StreamingRouteCall): StreamingRouteResult {
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
        val interceptedRequest = request.copy(messages = operationInterceptor.interceptRequest(callContext, request.messages))
        return try {
            collectStreamingRouteChunks(streamCapable, interceptedRequest, request.timeoutMillis ?: operation.operation.timeoutMillis, StreamingRouteContext(route, operation, tokenBudgetTracker, callContext, observation, emitChunk), { emittedAnyTokens }, { chunk -> emittedAnyTokens = true; emitChunk(chunk) })
            error("Streaming route completed without a terminal result")
        } catch (finished: StreamingRouteFinished) {
            finished.result
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val timeout = TimeoutException(message = buildTimeoutMessage(route.providerName, operation, request.timeoutMillis ?: operation.operation.timeoutMillis), cause = error)
            observation.onProviderFailure(timeout)
            handleFallbackResult(timeout, emittedAnyTokens, route.providerName, observation)
        } catch (error: CancellationException) {
            observation.completeCancellation(error)
            throw error
        } catch (error: Throwable) {
            error.rethrowIfCancellation()
            val normalized = normalizeStreamingError(error, route.providerName, operation)
            observation.onProviderFailure(normalized)
            handleFallbackResult(normalized, emittedAnyTokens, route.providerName, observation)
        }
    }

    private data class StreamingRouteCall(val streamCapable: StreamCapable, val request: ModelRequest, val operation: OperationDefinition, val route: ResolvedProviderRoute, val attempt: Int, val observation: OperationObservation, val tokenBudgetTracker: TokenBudgetTracker, val emitChunk: suspend (StreamChunk) -> Unit)

    private fun streamingCallContext(operation: OperationDefinition, providerId: String, attempt: Int) = OperationCallContext(serviceInterface = serviceTypeName, methodName = operation.method.name, providerId = providerId, requestedModel = operation.operation.model, attempt = attempt)

    private fun startStreamingObservation(route: ResolvedProviderRoute, operation: OperationDefinition, attempt: Int, routeIndex: Int): OperationObservation = operationObserver.onCallStarted(OperationCallContext(serviceInterface = serviceTypeName, methodName = operation.method.name, providerId = route.providerName, requestedModel = operation.operation.model, attempt = attempt)).also { observation ->
        observation.emitRuntimeEvent(
            RuntimeEvent.of(RuntimeEvents.ROUTE_SELECTED) {
                set(RuntimeAttributes.PROVIDER_ID, route.providerName)
                set(RuntimeAttributes.EFFECTIVE_MODEL, route.effectiveModelName)
                set(RuntimeAttributes.ROUTE_INDEX, routeIndex.toLong())
                set(RuntimeAttributes.IS_FALLBACK, routeIndex > 0)
            },
        )
    }

    private data class StreamingRouteContext(val route: ResolvedProviderRoute, val operation: OperationDefinition, val tokenBudgetTracker: TokenBudgetTracker, val callContext: OperationCallContext, val observation: OperationObservation, val emitChunk: suspend (StreamChunk) -> Unit)

    private suspend fun collectStreamingRouteChunks(streamCapable: StreamCapable, request: ModelRequest, timeoutMillis: Long, ctx: StreamingRouteContext, hasEmittedTokens: () -> Boolean, onToken: suspend (StreamChunk.Token) -> Unit) {
        withTimeout(timeoutMillis) {
            streamCapable.stream(request).collect { chunk -> handleStreamingChunk(chunk, ctx, hasEmittedTokens(), onToken) }
            handleStreamingTerminationWithoutTerminalChunk(ctx.route, ctx.operation, ctx.observation, hasEmittedTokens())
        }
    }

    private suspend fun handleStreamingChunk(chunk: StreamChunk, ctx: StreamingRouteContext, emittedAnyTokens: Boolean, onToken: suspend (StreamChunk.Token) -> Unit) {
        when (chunk) {
            is StreamChunk.Token -> onToken(chunk)
            is StreamChunk.Complete -> handleStreamingComplete(chunk, ctx.route, ctx.tokenBudgetTracker, ctx.callContext, ctx.observation, ctx.emitChunk)
            is StreamChunk.Error -> { ctx.observation.onProviderFailure(chunk.cause); finishStreamingRoute(handleFallbackResult(chunk.cause, emittedAnyTokens, ctx.route.providerName, ctx.observation, chunk)) }
        }
    }

    private suspend fun handleStreamingComplete(chunk: StreamChunk.Complete, route: ResolvedProviderRoute, tokenBudgetTracker: TokenBudgetTracker, callContext: OperationCallContext, observation: OperationObservation, emitChunk: suspend (StreamChunk) -> Unit) {
        val response = ModelResponse(content = chunk.fullText, inputTokens = chunk.usage.inputTokens, outputTokens = chunk.usage.outputTokens, thinkingTokens = chunk.usage.thinkingTokens, modelUsed = route.effectiveModelName, finishReason = FinishReason.STOP)
        val interceptedResponse = operationInterceptor.interceptResponse(callContext, response)
        observation.onProviderResponse(interceptedResponse)
        try { tokenBudgetCoordinator.enforce(tokenBudgetTracker, interceptedResponse, observation, route.providerName, route.effectiveModelName) } catch (error: TokenBudgetExceededException) { observation.onCallCompleted(parseSuccess = null); throw StreamingRouteFinished(StreamingRouteResult.TerminalError(StreamChunk.Error(error))) }
        observation.onCallCompleted(parseSuccess = null)
        circuitBreaker.onSuccess(route.providerName)
        emitChunk(if (interceptedResponse.content != chunk.fullText) chunk.copy(fullText = interceptedResponse.content) else chunk)
        throw StreamingRouteFinished(StreamingRouteResult.Completed(interceptedResponse.content))
    }

    private fun handleStreamingTerminationWithoutTerminalChunk(route: ResolvedProviderRoute, operation: OperationDefinition, observation: OperationObservation, emittedAnyTokens: Boolean): Nothing {
        val error = ProviderException(message = "Provider ${route.providerName} ended streaming without a terminal chunk while invoking $qualifiedServiceName.${operation.method.name}")
        observation.onProviderFailure(error)
        finishStreamingRoute(handleFallbackResult(error, emittedAnyTokens, route.providerName, observation))
    }

    private fun normalizeStreamingError(error: Throwable, providerName: String, operation: OperationDefinition): TramaiException = when (error) {
        is TramaiException -> error
        else -> ProviderException(message = "Provider $providerName failed while streaming $qualifiedServiceName.${operation.method.name}", cause = error)
    }

    private fun recordCircuitBreakerFailure(providerName: String, error: Throwable, observation: OperationObservation) {
        val opened = circuitBreaker.onFailure(providerName, error)
        if (opened) observation.emitRuntimeEvent(
            RuntimeEvent.of(RuntimeEvents.CIRCUIT_OPENED) {
                set(RuntimeAttributes.PROVIDER_ID, providerName)
            },
        )
    }

    private fun recordStartupRetryEvent(providerName: String, failureType: String, observation: OperationObservation) {
        observation.emitRuntimeEvent(
            RuntimeEvent.of(RuntimeEvents.STREAMING_STARTUP_RETRY) {
                set(RuntimeAttributes.PROVIDER_ID, providerName)
                set(RuntimeAttributes.FAILURE_TYPE, failureType)
            },
        )
    }

    private fun handleFallbackResult(error: TramaiException, emittedAnyTokens: Boolean, providerName: String, observation: OperationObservation, terminalChunk: StreamChunk.Error = StreamChunk.Error(error)): StreamingRouteResult {
        val result = if (!emittedAnyTokens && shouldFallbackFrom(error)) { recordStartupRetryEvent(providerName, error::class.simpleName ?: "unknown", observation); StreamingRouteResult.StartupFailure(error) } else StreamingRouteResult.TerminalError(terminalChunk)
        recordCircuitBreakerFailure(providerName, error, observation)
        observation.onCallCompleted(parseSuccess = null)
        return result
    }

    private fun finishStreamingRoute(result: StreamingRouteResult): Nothing { throw StreamingRouteFinished(result) }

    private fun shouldFallbackFrom(error: Throwable): Boolean = when (error) {
        is CircuitBreakerOpenException -> true
        is TimeoutException -> true
        is ProviderException -> error.retryable
        else -> false
    }

    private fun buildTimeoutMessage(providerId: String, operation: OperationDefinition, timeoutMillis: Long): String = "Provider $providerId timed out after ${timeoutMillis}ms while invoking $qualifiedServiceName.${operation.method.name}"

    private fun OperationObservation.completeCancellation(cancellation: CancellationException) {
        try {
            onCallCancelled()
        } catch (observerError: Throwable) {
            cancellation.addSuppressed(observerError)
        }
    }
}

