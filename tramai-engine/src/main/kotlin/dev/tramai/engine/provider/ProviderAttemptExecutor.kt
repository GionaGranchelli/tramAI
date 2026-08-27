package dev.tramai.engine.provider

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.engine.emitRuntimeEvent

import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.security.DlpInspectionException
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.core.coroutines.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal data class ProviderCallResult(val response: ModelResponse, val observation: OperationObservation, val providerId: String, val modelName: String, val approvedModel: RegisteredModel?)
internal class AttemptCounter {
    private var attempt = 0

    fun next(): Int = attempt++
}
internal fun interface ProviderInvocationGate { suspend fun invoke(providerId: String, modelName: String, correlationId: String, securityContext: ExecutionSecurityContext) }
internal fun interface ProviderResponseSanitizer { suspend fun sanitize(response: ModelResponse, operation: OperationDefinition, providerId: String, modelName: String, correlationId: String, securityContext: ExecutionSecurityContext, observation: OperationObservation): ModelResponse }

internal class ProviderAttemptExecutor(
    private val serviceInterface: String,
    private val operationObserver: OperationObserver,
    private val operationInterceptor: OperationInterceptor,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val retryPolicy: ProviderRetryPolicy,
    private val authorizationService: ProviderAuthorizationService,
    private val beforeProviderInvocation: ProviderInvocationGate,
    private val responseSanitizer: ProviderResponseSanitizer,
) {
    suspend fun execute(request: ProviderRetryRequest): ProviderCallResult {
        val maxAttempts = request.operation.operation.providerRetries + 1
        repeat(maxAttempts) { retryIndex ->
            val attempt = startAttempt(request)
            try {
                beforeProviderInvocation.invoke(request.providerId, request.request.model, request.correlationId, request.securityContext)
                val response = operationInterceptor.interceptResponse(attempt.context, callOnce(request.providerId, request.provider, attempt.request, request.operation))
                val sanitized = responseSanitizer.sanitize(response, request.operation, request.providerId, request.request.model, request.correlationId, request.securityContext, attempt.observation)
                attempt.observation.onProviderResponse(sanitized)
                // Synchronous success must reach the breaker symmetrically with the
                // streaming path: a healthy completion resets the failure history.
                circuitBreaker.onSuccess(request.permit)
                return ProviderCallResult(sanitized, attempt.observation, request.providerId, request.request.model, attempt.approvedModel)
            } catch (error: DlpInspectionException) {
                attempt.observation.onCallCompleted(parseSuccess = null)
                circuitBreaker.onAbandoned(request.permit)
                throw error
            } catch (error: CancellationException) {
                attempt.observation.completeCancellation(error)
                circuitBreaker.onAbandoned(request.permit)
                throw error
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                attempt.observation.onProviderFailure(error)
                attempt.observation.onCallCompleted(parseSuccess = null)
                when (val decision = retryPolicy.decide(error, retryIndex, maxAttempts)) {
                    is ProviderRetryDecision.Retry -> {
                        attempt.observation.emitRuntimeEvent(
                            RuntimeEvent.of(RuntimeEvents.RETRY_SCHEDULED) {
                                set(RuntimeAttributes.PROVIDER_ID, request.providerId)
                                set(RuntimeAttributes.RETRY_INDEX, retryIndex.toLong())
                                set(RuntimeAttributes.DELAY_MILLIS, decision.delayMillis)
                                set(RuntimeAttributes.DELAY_SOURCE, decision.delaySource)
                            },
                        )
                        delay(decision.delayMillis)
                    }
                    ProviderRetryDecision.Stop -> {
                        if (circuitBreaker.onFailure(request.permit, error)) {
                            attempt.observation.emitRuntimeEvent(
                                RuntimeEvent.of(RuntimeEvents.CIRCUIT_OPENED) {
                                    set(RuntimeAttributes.PROVIDER_ID, request.providerId)
                                },
                            )
                        } else {
                            // Non-qualifying terminal failure: never a breaker
                            // failure, but if this was the HALF_OPEN probe the
                            // permit must still be released or recovery strands.
                            circuitBreaker.onAbandoned(request.permit)
                        }
                        throw error
                    }
                }
            }
        }
        error("Provider retry loop exited without returning or throwing")
    }

    private suspend fun startAttempt(request: ProviderRetryRequest): ProviderRetryAttempt {
        val context = OperationCallContext(serviceInterface, request.operation.method.name, request.providerId, request.operation.operation.model, request.attemptCounter.next())
        val intercepted = request.request.copy(messages = operationInterceptor.interceptRequest(context, request.request.messages))
        val observation = operationObserver.onCallStarted(context)
        observation.emitRuntimeEvent(
            RuntimeEvent.of(RuntimeEvents.ROUTE_SELECTED) {
                set(RuntimeAttributes.PROVIDER_ID, request.providerId)
                set(RuntimeAttributes.EFFECTIVE_MODEL, request.request.model)
                set(RuntimeAttributes.ROUTE_INDEX, request.routeIndex.toLong())
                set(RuntimeAttributes.IS_FALLBACK, request.routeIndex > 0)
            },
        )
        val approvedModel = try {
            authorizationService.authorize(request.providerId, request.request.model)
        } catch (error: CancellationException) {
            observation.completeCancellation(error)
            circuitBreaker.onAbandoned(request.permit)
            throw error
        } catch (error: ModelRegistryException) {
            observation.onCallCompleted(parseSuccess = null)
            circuitBreaker.onAbandoned(request.permit)
            throw error
        }
        return ProviderRetryAttempt(context, intercepted, observation, approvedModel)
    }

    private suspend fun callOnce(providerId: String, provider: ModelProvider, request: ModelRequest, operation: OperationDefinition): ModelResponse = try {
        val timeout = request.timeoutMillis ?: operation.operation.timeoutMillis
        if (request.messages.any { it.hasImage() } && !provider.supportsCapability(ProviderCapability.VISION)) throw ProviderCapabilityException(provider.providerId(), "VISION")
        withTimeout(timeout) { provider.complete(request) }
    } catch (error: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw TimeoutException("Provider $providerId timed out after ${request.timeoutMillis ?: operation.operation.timeoutMillis}ms while invoking $serviceInterface.${operation.method.name}", error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        error.rethrowIfCancellation()
        throw if (error is ProviderException) error else ProviderException("Provider $providerId failed while invoking $serviceInterface.${operation.method.name}", error)
    }
}

internal data class ProviderRetryRequest(val providerId: String, val provider: ModelProvider, val request: ModelRequest, val operation: OperationDefinition, val attemptCounter: AttemptCounter, val routeIndex: Int, val correlationId: String, val securityContext: ExecutionSecurityContext, val permit: CircuitBreakerPermit)
private data class ProviderRetryAttempt(val context: OperationCallContext, val request: ModelRequest, val observation: OperationObservation, val approvedModel: RegisteredModel?)

private fun OperationObservation.completeCancellation(cancellation: CancellationException) {
    try {
        onCallCancelled()
    } catch (observerError: Throwable) {
        cancellation.addSuppressed(observerError)
    }
}
