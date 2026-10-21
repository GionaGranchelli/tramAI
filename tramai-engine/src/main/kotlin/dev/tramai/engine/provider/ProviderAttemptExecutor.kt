package dev.tramai.engine.provider

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.model.RegisteredModel
import dev.tramai.core.observation.AttemptIdentity
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationInterceptor
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.security.DlpInspectionException
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.emitRuntimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

internal data class ProviderCallResult(
    val response: ModelResponse,
    val observation: OperationObservation,
    val providerId: String,
    val modelName: String,
    val approvedModel: RegisteredModel?,
)

internal class AttemptCounter {
    private var attempt = 0

    fun next(): Int = attempt++
}

internal fun interface ProviderInvocationGate {
    suspend fun invoke(
        providerId: String,
        modelName: String,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    )
}

internal data class ProviderCallRef(
    val providerId: String,
    val modelName: String,
    val correlationId: String,
)

internal fun interface ProviderResponseSanitizer {
    suspend fun sanitize(
        response: ModelResponse,
        operation: OperationDefinition,
        ref: ProviderCallRef,
        securityContext: ExecutionSecurityContext,
        observation: OperationObservation,
    ): ModelResponse
}

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
                val response =
                    operationInterceptor.interceptResponse(
                        attempt.context,
                        callOnce(request.providerId, request.provider, attempt.request, request.operation),
                    )
                val sanitized =
                    responseSanitizer.sanitize(
                        response = response,
                        operation = request.operation,
                        ref = ProviderCallRef(request.providerId, request.request.model, request.correlationId),
                        securityContext = request.securityContext,
                        observation = attempt.observation,
                    )
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
                        emitRetryScheduled(attempt.observation, request.providerId, retryIndex, decision)
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

    private fun emitRetryScheduled(
        observation: OperationObservation,
        providerId: String,
        retryIndex: Int,
        decision: ProviderRetryDecision.Retry,
    ) {
        observation.emitRuntimeEvent(
            RuntimeEvent.of(RuntimeEvents.RETRY_SCHEDULED) {
                set(RuntimeAttributes.PROVIDER_ID, providerId)
                set(RuntimeAttributes.RETRY_INDEX, retryIndex.toLong())
                set(RuntimeAttributes.DELAY_MILLIS, decision.delayMillis)
                set(RuntimeAttributes.DELAY_SOURCE, decision.delaySource)
            },
        )
    }

    private suspend fun startAttempt(request: ProviderRetryRequest): ProviderRetryAttempt {
        val context =
            OperationCallContext(
                serviceInterface = serviceInterface,
                methodName = request.operation.method.name,
                providerId = request.providerId,
                requestedModel = request.operation.operation.model,
                attempt = request.attemptCounter.next(),
                identity =
                    AttemptIdentity(
                        invocationId = request.invocationId,
                        workflowRunId = request.governedRun?.runId?.value,
                        correlationId = request.correlationId,
                        routeIndex = request.routeIndex,
                    ),
            )
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
        val approvedModel =
            try {
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

    private fun rethrowAsProviderFailure(
        error: Throwable,
        providerId: String,
        operation: OperationDefinition,
    ): Nothing {
        error.rethrowIfCancellation()
        if (error is ProviderException) throw error
        throw ProviderException(failureMessage(providerId, operation), error)
    }

    private fun failureMessage(
        providerId: String,
        operation: OperationDefinition,
    ): String = "Provider $providerId failed while invoking $serviceInterface.${operation.method.name}"

    private suspend fun callOnce(
        providerId: String,
        provider: ModelProvider,
        request: ModelRequest,
        operation: OperationDefinition,
    ): ModelResponse {
        val timeoutMillis = request.timeoutMillis ?: operation.operation.timeoutMillis
        return try {
            if (request.messages.any { it.hasImage() } && !provider.supportsCapability(ProviderCapability.VISION)) {
                throw ProviderCapabilityException(provider.providerId(), "VISION")
            }
            withTimeout(timeoutMillis) { provider.complete(request) }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw TimeoutException(
                "Provider $providerId timed out after ${timeoutMillis}ms while invoking " +
                    "$serviceInterface.${operation.method.name}",
                error,
            )
        } catch (error: Throwable) {
            rethrowAsProviderFailure(error, providerId, operation)
        }
    }
}

internal data class ProviderRetryRequest(
    val providerId: String,
    val provider: ModelProvider,
    val request: ModelRequest,
    val operation: OperationDefinition,
    val attemptCounter: AttemptCounter,
    val routeIndex: Int,
    val correlationId: String,
    val securityContext: ExecutionSecurityContext,
    val permit: CircuitBreakerPermit,
    /** Identity of the invocation this attempt belongs to; `(invocationId, attempt)` is unique. */
    val invocationId: String,
    /** Governed run the invocation belongs to, when one exists. */
    val governedRun: GovernedRunIdentity? = null,
)

private data class ProviderRetryAttempt(
    val context: OperationCallContext,
    val request: ModelRequest,
    val observation: OperationObservation,
    val approvedModel: RegisteredModel?,
)

private fun OperationObservation.completeCancellation(cancellation: CancellationException) {
    try {
        onCallCancelled()
    } catch (observerError: Throwable) {
        cancellation.addSuppressed(observerError)
    }
}
