package dev.tramai.engine.provider

import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.provider.resolveCandidates

internal fun interface ProviderRouteGate { suspend fun beforeRoute() }
internal fun interface ProviderResolutionGate { suspend fun beforeResolution(operation: OperationDefinition, correlationId: String, securityContext: ExecutionSecurityContext) }
internal fun interface ProviderFallbackGate { suspend fun transition(correlationId: String, previousProviderId: String?, previousModelName: String?, nextProviderId: String, reason: String, securityContext: ExecutionSecurityContext) }
internal data class ProviderExecutionRequest(val operation: OperationDefinition, val messages: List<Message>, val attemptCounter: AttemptCounter, val correlationId: String, val securityContext: ExecutionSecurityContext, val beforeRoute: ProviderRouteGate)

internal class ProviderExecutionCoordinator(
    private val routingPlan: ProviderRoutingPlan,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val attemptExecutor: ProviderAttemptExecutor,
    private val fallbackPolicy: ProviderFallbackPolicy,
    private val beforeResolution: ProviderResolutionGate,
    private val fallbackGate: ProviderFallbackGate,
) {
    suspend fun execute(request: ProviderExecutionRequest): ProviderCallResult {
        var lastFailure: Throwable? = null
        var lastCircuitOpen: CircuitBreakerOpenException? = null
        beforeResolution.beforeResolution(request.operation, request.correlationId, request.securityContext)
        val candidates = routingPlan.resolveCandidates(request.operation.operation)
        for ((index, route) in candidates.withIndex()) {
            val next = candidates.getOrNull(index + 1)
            val admission = circuitBreaker.beforeCall(route.providerName)
            if (admission is CircuitBreakerAdmission.Rejected) {
                val error = CircuitBreakerOpenException(route.providerName, admission.blockedUntilMillis)
                transition(error, route, next, ProviderFallbackReason.CIRCUIT_BREAKER_OPEN, request)
                lastCircuitOpen = error
                continue
            }
            val permit = (admission as CircuitBreakerAdmission.Allowed).permit
            try {
                request.beforeRoute.beforeRoute()
                return attemptExecutor.execute(routeRequest(route, index, request, permit))
            } catch (error: Throwable) {
                error.rethrowIfCancellation()
                when (val decision = fallbackPolicy.decide(error)) {
                    ProviderFallbackDecision.Stop -> throw error
                    is ProviderFallbackDecision.Continue -> {
                        transition(error, route, next, decision.reason, request)
                        lastFailure = error
                    }
                }
            } finally {
                // Structural permit relinquishment: admission creates an
                // obligation and scope exit ALWAYS discharges it. This closes
                // every post-admission escape that a manual call-site cleanup
                // could miss — beforeRoute throwing policy/cancellation,
                // startAttempt observer/interceptor failures, cancellation
                // during the retry delay — without double-completing:
                //   success            -> CLOSED (same gen)      -> no-op
                //   qualifying failure -> OPEN (gen+1)           -> stale no-op
                //   neutral CLOSED     -> CLOSED (same gen)      -> no-op
                //   neutral HALF_OPEN  -> OPEN (gen+1) released  -> stale no-op
                circuitBreaker.onAbandoned(permit)
            }
        }
        throw lastFailure ?: lastCircuitOpen ?: ProviderException("No available provider route for model '${request.operation.operation.model}'", retryable = true)
    }

    private suspend fun transition(error: Throwable, route: ResolvedProviderRoute, next: ResolvedProviderRoute?, reason: ProviderFallbackReason, request: ProviderExecutionRequest) {
        if (next == null) return
        try {
            fallbackGate.transition(request.correlationId, route.providerName, route.effectiveModelName, next.providerName, fallbackPolicy.reasonString(reason), request.securityContext)
        } catch (policyError: PolicyViolationException) {
            policyError.addSuppressed(error)
            throw policyError
        }
    }

    private fun routeRequest(route: ResolvedProviderRoute, routeIndex: Int, request: ProviderExecutionRequest, permit: CircuitBreakerPermit) = ProviderRetryRequest(
        providerId = route.providerName, provider = route.provider,
        request = ModelRequest(model = route.effectiveModelName, messages = request.messages.toList(), tools = request.operation.toolDefinitions.takeIf { it.isNotEmpty() }, timeoutMillis = request.operation.operation.timeoutMillis, operationInterface = request.operation.method.declaringClass.name, operationMethod = request.operation.method.name),
        operation = request.operation, attemptCounter = request.attemptCounter, routeIndex = routeIndex, correlationId = request.correlationId, securityContext = request.securityContext, permit = permit,
    )
}
