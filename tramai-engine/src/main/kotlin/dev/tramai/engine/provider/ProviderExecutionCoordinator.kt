package dev.tramai.engine.provider

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.core.provider.resolveCandidates
import dev.tramai.engine.CircuitBreakerAdmission
import dev.tramai.engine.CircuitBreakerPermit
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.security.governance.CandidateAuthorization
import dev.tramai.security.governance.CandidateSelection
import dev.tramai.security.governance.CandidateSelectionDecision
import dev.tramai.security.governance.CandidateSelectionStrategy
import dev.tramai.security.governance.CandidateViability
import dev.tramai.security.governance.ProviderCandidate
import dev.tramai.security.governance.ProviderInputRelease
import dev.tramai.security.governance.SelectionRefusal
import dev.tramai.security.governance.ViabilityRefusal
import dev.tramai.security.governance.ViableCandidates
import dev.tramai.security.governance.WorkloadClassificationSignal
import dev.tramai.security.governance.WorkloadGovernanceResolution
import dev.tramai.security.governance.WorkloadGovernanceResolver

internal fun interface ProviderRouteGate { suspend fun beforeRoute() }
internal fun interface ProviderResolutionGate { suspend fun beforeResolution(operation: OperationDefinition, correlationId: String, securityContext: ExecutionSecurityContext) }
internal fun interface ProviderFallbackGate { suspend fun transition(correlationId: String, previousProviderId: String?, previousModelName: String?, nextProviderId: String, reason: String, securityContext: ExecutionSecurityContext) }

/**
 * How the viable envelope is turned into one preferred candidate.
 *
 * This is a preference, never a source of authority: [dev.tramai.security.governance.CandidateSelection]
 * refuses any answer outside the viable set, so a preference can only ever choose among candidates
 * that authorization permitted and viability found usable.
 */
internal fun interface ProviderSelectionPreference {
    fun preferred(viable: ViableCandidates, configuredOrder: List<ProviderCandidate>): ProviderCandidate?

    companion object {
        /** The configured route order, filtered through the viable envelope. */
        val CONFIGURED_ORDER: ProviderSelectionPreference =
            ProviderSelectionPreference { viable, configuredOrder -> viable.orderedBy(configuredOrder).firstOrNull() }
    }
}
internal data class ProviderExecutionRequest(val operation: OperationDefinition, val messages: List<Message>, val attemptCounter: AttemptCounter, val correlationId: String, val securityContext: ExecutionSecurityContext, val beforeRoute: ProviderRouteGate, val governance: ProviderRunGovernance? = null)

/**
 * Drives provider execution through the 0.7.3 authority chain.
 *
 * Configured routes propose; they do not authorize. Each resolved route becomes a
 * [ProviderCandidate] only through its authoritative deployment, and only a candidate that
 * authorization permitted, viability found usable and selection chose may be invoked. Fallback
 * and circuit-breaker rejection narrow the envelope and reselect from it; they never advance to
 * the next configured route.
 */
internal class ProviderExecutionCoordinator(
    private val routingPlan: ProviderRoutingPlan,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val attemptExecutor: ProviderAttemptExecutor,
    private val fallbackPolicy: ProviderFallbackPolicy,
    private val beforeResolution: ProviderResolutionGate,
    private val fallbackGate: ProviderFallbackGate,
    private val governance: ProviderGovernanceConfiguration? = null,
    private val preference: ProviderSelectionPreference = ProviderSelectionPreference.CONFIGURED_ORDER,
) {
    private val selection = CandidateSelection()

    suspend fun execute(request: ProviderExecutionRequest): ProviderCallResult {
        var lastFailure: Throwable? = null
        var lastCircuitOpen: CircuitBreakerOpenException? = null
        beforeResolution.beforeResolution(request.operation, request.correlationId, request.securityContext)

        val resolvedRoutes = routingPlan.resolveCandidates(request.operation.operation)

        // Configuration resolution reports its own errors unchanged and first: a missing or unknown
        // route is a configuration fault, not a governance refusal.
        val configuration = governance ?: throw governanceAbsent()
        val run = request.governance ?: throw governanceAbsent()

        // The release predicate is built from the governed configuration: a default release instance
        // carries an empty rule map and would release nothing, so authorization would refuse every
        // candidate for a reason the configuration never expressed.
        val authorization =
            CandidateAuthorization(
                ProviderInputRelease(configuration.trustZonePolicy, configuration.rules),
                routingPlan,
            )

        // Classification and the workload's own trust zone are resolved, not assumed: a missing
        // claim or an unestablished deployment zone refuses rather than defaulting to a wider zone.
        val workload = WorkloadGovernanceResolver.resolve(
            identity = run.workloadIdentity,
            signals = signalsOf(request.securityContext),
            deploymentZone = run.workloadZone,
            rules = configuration.rules,
        )
        if (workload is WorkloadGovernanceResolution.Refused) {
            throw ProviderException("Provider execution is refused: ${workload.failure.name}", retryable = false)
        }
        val governanceFacts = workload as WorkloadGovernanceResolution.Resolved

        val mappings = mapRoutesToCandidates(resolvedRoutes, configuration)
        // Configured order over the candidate universe, preserved for preference only.
        val configuredOrder = mappings.map { it.second }
        val routeOf = mappings.associate { (route, candidate) -> candidate to route }

        // The engine reads authorization through the public set view: an authorization envelope
        // cannot be inspected or fabricated from outside the security module, which is the point.
        val authorizedSet =
            authorization.authorizedSet(
                configuredOrder,
                governanceFacts.trustZone,
                governanceFacts.classification,
                configuration.requiredCapabilities,
            )
        val authorized =
            authorization.authorizedCandidates(
                configuredOrder,
                governanceFacts.trustZone,
                governanceFacts.classification,
                configuration.requiredCapabilities,
            )

        // Availability is observed, never consumed: beforeCall grants a permit and can revive a
        // circuit, so it must not be used to populate the viable set.
        val viability =
            CandidateViability { candidate ->
                if (circuitBreaker.openUntilMillis(candidate.providerId) != null) {
                    ViabilityRefusal.AVAILABILITY
                } else {
                    null
                }
            }
        var remaining = viability.viableCandidates(authorized)

        while (true) {
            // Configured order is a preference over the viable envelope — never a source of membership.
            val strategy = CandidateSelectionStrategy { preference.preferred(remaining, configuredOrder) }
            when (val decision = selection.select(remaining, strategy)) {
                is CandidateSelectionDecision.NoSelection -> throw exhausted(decision.reason, authorizedSet.isEmpty(), lastFailure, lastCircuitOpen, request)
                is CandidateSelectionDecision.Selected -> {
                    val candidate = decision.candidate
                    val route = routeOf.getValue(candidate)
                    val admission = circuitBreaker.beforeCall(candidate.providerId)
                    if (admission is CircuitBreakerAdmission.Rejected) {
                        val error = CircuitBreakerOpenException(candidate.providerId, admission.blockedUntilMillis)
                        lastCircuitOpen = error
                        remaining = remaining.without(candidate)
                        transition(error, route, nextSelected(remaining, configuredOrder, routeOf), ProviderFallbackReason.CIRCUIT_BREAKER_OPEN, request)
                        continue
                    }
                    val permit = (admission as CircuitBreakerAdmission.Allowed).permit
                    try {
                        request.beforeRoute.beforeRoute()
                        return attemptExecutor.execute(routeRequest(route, resolvedRoutes.indexOf(route), request, permit))
                    } catch (error: Throwable) {
                        error.rethrowIfCancellation()
                        when (val fallback = fallbackPolicy.decide(error)) {
                            ProviderFallbackDecision.Stop -> throw error
                            is ProviderFallbackDecision.Continue -> {
                                lastFailure = error
                                remaining = remaining.without(candidate)
                                transition(error, route, nextSelected(remaining, configuredOrder, routeOf), fallback.reason, request)
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
            }
        }
    }

    /**
     * One candidate per resolved route, keyed by the route so the mapping back is exact.
     *
     * A route whose provider has no authoritative deployment yields no candidate: it can never be
     * authorized, viable, selected or invoked. Two routes collapsing onto one candidate is
     * ambiguous, and ambiguity fails closed rather than being guessed by provider or model name.
     */
    private fun mapRoutesToCandidates(
        resolvedRoutes: List<ResolvedProviderRoute>,
        configuration: ProviderGovernanceConfiguration,
    ): List<Pair<ResolvedProviderRoute, ProviderCandidate>> {
        val mappings = ArrayList<Pair<ResolvedProviderRoute, ProviderCandidate>>(resolvedRoutes.size)
        val seen = HashMap<ProviderCandidate, ResolvedProviderRoute>()
        resolvedRoutes.forEach { route ->
            val deployment = configuration.deploymentOf(route.providerName) ?: return@forEach
            val candidate = ProviderCandidate(route.providerName, route.effectiveModelName, deployment)
            val previous = seen.put(candidate, route)
            if (previous != null && previous != route) {
                throw ProviderException("Provider candidate has more than one resolved route", retryable = false)
            }
            mappings += route to candidate
        }
        return mappings
    }

    /** The candidate governance would select next from the narrowed envelope, or null. */
    private fun nextSelected(
        remaining: ViableCandidates,
        configuredOrder: List<ProviderCandidate>,
        routeOf: Map<ProviderCandidate, ResolvedProviderRoute>,
    ): ResolvedProviderRoute? = remaining.orderedBy(configuredOrder).firstOrNull()?.let { routeOf[it] }

    private fun signalsOf(context: ExecutionSecurityContext): List<WorkloadClassificationSignal> {
        val classification = context.dataClassification ?: return emptyList()
        val source = context.classificationSource ?: return emptyList()
        return listOf(WorkloadClassificationSignal(classification, source))
    }

    private fun governanceAbsent() =
        ProviderException("Provider execution requires governance inputs and none were supplied", retryable = false)

    private fun exhausted(
        reason: SelectionRefusal,
        nothingAuthorized: Boolean,
        lastFailure: Throwable?,
        lastCircuitOpen: CircuitBreakerOpenException?,
        request: ProviderExecutionRequest,
    ): Throwable =
        when {
            nothingAuthorized -> {
                // An identity, zone, classification, registration or capability refusal is not a
                // transient condition: retrying cannot change it, so it reports as non-retryable.
                ProviderException("No provider candidate is authorized for this execution", retryable = false)
            }

            reason == SelectionRefusal.NO_VIABLE_CANDIDATES -> {
                lastFailure
                    ?: lastCircuitOpen
                    ?: ProviderException("No available provider route for model '${request.operation.operation.model}'", retryable = true)
            }

            else -> ProviderException("Selection refused: ${reason.name}", retryable = false)
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
