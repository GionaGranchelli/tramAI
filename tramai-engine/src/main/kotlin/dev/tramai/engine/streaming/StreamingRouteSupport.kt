package dev.tramai.engine.streaming

import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.ModelRegistryException
import dev.tramai.core.exception.ProviderCapabilityException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.exception.TimeoutException
import dev.tramai.core.exception.TramaiException
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.engine.emitRuntimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * The leaf operations a streaming route's execution needs and no other part of the path owns:
 * the call context and observation a route starts with, its authorization, the timeout failure
 * shape, and the runtime events a breaker failure emits. Split out of
 * StreamingExecutionCoordinator so that coordinator keeps only the declarations the certified
 * detekt baseline pins there; behaviour is byte-for-byte the same code.
 */
internal class StreamingRouteSupport(
    runtime: StreamingEngineRuntime,
    services: StreamingCoordinationServices,
    failurePolicy: StreamingFailurePolicy,
) {
    private val serviceTypeName = runtime.serviceTypeName
    private val qualifiedServiceName = runtime.qualifiedServiceName
    private val operationObserver = services.operationObserver
    private val modelRegistryEnforcer = services.modelRegistryEnforcer
    private val circuitBreaker = failurePolicy.circuitBreaker

    /** A provider without streaming support is refused, releasing its permit first. */
    fun failStreamingCapability(
        route: ResolvedProviderRoute,
        request: StreamingExecutionRoute,
        circuitBreaker: ProviderCircuitBreaker,
    ): Nothing {
        circuitBreaker.onAbandoned(request.permit)
        throw ProviderCapabilityException(route.providerName, "streaming")
    }

    suspend fun authorizeStreamingRoute(
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

    fun streamingCallContext(
        operation: OperationDefinition,
        providerId: String,
        attempt: Int,
    ) = OperationCallContext(
        serviceInterface = serviceTypeName,
        methodName = operation.method.name,
        providerId = providerId,
        requestedModel = operation.operation.model,
        attempt = attempt,
    )

    fun startStreamingObservation(
        route: ResolvedProviderRoute,
        operation: OperationDefinition,
        attempt: Int,
        routeIndex: Int,
    ): OperationObservation =
        operationObserver
            .onCallStarted(
                OperationCallContext(
                    serviceInterface = serviceTypeName,
                    methodName = operation.method.name,
                    providerId = route.providerName,
                    requestedModel = operation.operation.model,
                    attempt = attempt,
                ),
            ).also { observation ->
                observation.emitRuntimeEvent(
                    RuntimeEvent.of(RuntimeEvents.ROUTE_SELECTED) {
                        set(RuntimeAttributes.PROVIDER_ID, route.providerName)
                        set(RuntimeAttributes.EFFECTIVE_MODEL, route.effectiveModelName)
                        set(RuntimeAttributes.ROUTE_INDEX, routeIndex.toLong())
                        set(RuntimeAttributes.IS_FALLBACK, routeIndex > 0)
                    },
                )
            }

    fun normalizeStreamingError(
        error: Throwable,
        providerName: String,
        operation: OperationDefinition,
    ): TramaiException =
        when (error) {
            is TramaiException -> {
                error
            }

            else -> {
                ProviderException(
                    message =
                        "Provider $providerName failed while streaming " +
                            "$qualifiedServiceName.${operation.method.name}",
                    cause = error,
                )
            }
        }

    fun recordCircuitBreakerFailure(
        permit: CircuitBreakerPermit,
        error: Throwable,
        observation: OperationObservation,
    ) {
        val opened = circuitBreaker.onFailure(permit, error)
        if (opened) {
            observation.emitRuntimeEvent(
                RuntimeEvent.of(RuntimeEvents.CIRCUIT_OPENED) {
                    set(RuntimeAttributes.PROVIDER_ID, permit.providerId)
                },
            )
        } else {
            // Non-qualifying failure: never a breaker failure, but a HALF_OPEN
            // probe permit must still be released or recovery strands forever.
            circuitBreaker.onAbandoned(permit)
        }
    }

    fun shouldFallbackFrom(error: Throwable): Boolean =
        when (error) {
            is CircuitBreakerOpenException -> true
            is TimeoutException -> true
            is ProviderException -> error.retryable
            else -> false
        }

    fun timeoutMillisOf(
        request: ModelRequest,
        operation: OperationDefinition,
    ): Long = request.timeoutMillis ?: operation.operation.timeoutMillis

    /** The timeout failure for a route that ran out of time while streaming. */
    fun streamingTimeout(
        route: ResolvedProviderRoute,
        operation: OperationDefinition,
        request: ModelRequest,
        error: TimeoutCancellationException,
    ): TimeoutException =
        TimeoutException(
            message = buildTimeoutMessage(route.providerName, operation, timeoutMillisOf(request, operation)),
            cause = error,
        )

    fun buildTimeoutMessage(
        providerId: String,
        operation: OperationDefinition,
        timeoutMillis: Long,
    ): String =
        "Provider $providerId timed out after ${timeoutMillis}ms while invoking " +
            "$qualifiedServiceName.${operation.method.name}"
}

internal fun finishStreamingRoute(result: StreamingRouteResult): Nothing = throw StreamingRouteFinished(result)

internal fun noAvailableStreamingRouteChunk(
    operation: OperationDefinition,
    lastFailure: Throwable?,
    lastCircuitOpen: CircuitBreakerOpenException?,
): StreamChunk.Error =
    StreamChunk.Error(
        (
            lastFailure
                ?: lastCircuitOpen
                ?: ProviderException(
                    message = "No available streaming provider route for model '${operation.operation.model}'",
                    retryable = true,
                )
        ) as TramaiException,
    )
