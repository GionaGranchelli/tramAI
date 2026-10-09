package dev.tramai.engine.provider

import dev.tramai.core.coroutines.rethrowIfCancellation
import dev.tramai.core.exception.CircuitBreakerOpenException
import dev.tramai.core.exception.PolicyViolationException
import dev.tramai.core.exception.ProviderException
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.model.Message
import dev.tramai.core.model.ModelRequest
import dev.tramai.core.provider.ProviderCapability
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

internal fun interface ProviderRouteGate {
    suspend fun beforeRoute()
}

internal fun interface ProviderResolutionGate {
    suspend fun beforeResolution(
        operation: OperationDefinition,
        correlationId: String,
        securityContext: ExecutionSecurityContext,
    )
}

internal fun interface ProviderFallbackGate {
    suspend fun transition(
        correlationId: String,
        previousProviderId: String?,
        previousModelName: String?,
        nextProviderId: String,
        reason: String,
        securityContext: ExecutionSecurityContext,
    )
}

/**
 * How the viable envelope is turned into one preferred candidate.
 *
 * This is a preference, never a source of authority: [dev.tramai.security.governance.CandidateSelection]
 * refuses any answer outside the viable set, so a preference can only ever choose among candidates
 * that authorization permitted and viability found usable.
 */
internal fun interface ProviderSelectionPreference {
    fun preferred(
        viable: ViableCandidates,
        configuredOrder: List<ProviderCandidate>,
    ): ProviderCandidate?

    companion object {
        /** The configured route order, filtered through the viable envelope. */
        val CONFIGURED_ORDER: ProviderSelectionPreference =
            ProviderSelectionPreference { viable, configuredOrder -> viable.orderedBy(configuredOrder).firstOrNull() }
    }
}

internal data class ProviderExecutionRequest(
    val operation: OperationDefinition,
    val messages: List<Message>,
    val attemptCounter: AttemptCounter,
    val correlationId: String,
    val securityContext: ExecutionSecurityContext,
    val beforeRoute: ProviderRouteGate,
    val governedRun: GovernedRunIdentity? = null,
)

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
    private val walk =
        GovernedCandidateWalk(routingPlan, circuitBreaker, fallbackGate, fallbackPolicy)

    suspend fun execute(request: ProviderExecutionRequest): ProviderCallResult {
        beforeResolution.beforeResolution(request.operation, request.correlationId, request.securityContext)

        val resolvedRoutes = routingPlan.resolveCandidates(request.operation.operation)

        // Structural branch on authoritative configuration state. `governance` is derived from the
        // engine's configured routing topology, so its absence means no governed routing topology
        // exists for this execution — not that a caller omitted a request field. Such an execution
        // keeps the pre-0.7.3h path. There is deliberately no recovery from a governed refusal into
        // that path: once a governed topology exists, every missing input fails closed below.
        //
        // The topology is keyed by WorkloadDeploymentIdentity, so it is only addressable for an
        // execution that carries an admitted run identity; without one there is no governed routing
        // topology *for this execution* and it keeps the pre-0.7.3h path. That identity is resolved
        // from the authoritative run scope by the construction sites above, never taken from a
        // caller-supplied request field, so a governed execution cannot opt out of governance by
        // omitting one. Inside the governed branch every missing fact still fails closed.
        val configuration = governance
        val run = request.governedRun
        if (configuration == null || run == null) {
            return executeLegacy(request, resolvedRoutes)
        }

        var lastFailure: Throwable? = null
        var lastCircuitOpen: CircuitBreakerOpenException? = null
        val candidateSet = walk.governedCandidateSet(request, resolvedRoutes, configuration, run)
        val authorizedSet = candidateSet.authorizedSet
        val configuredOrder = candidateSet.configuredOrder
        val routeOf = candidateSet.routeOf
        var remaining = candidateSet.remaining

        lastCircuitOpen =
            walk.announceAvailabilityTransitions(authorizedSet, remaining, configuredOrder, routeOf, request)
                ?: lastCircuitOpen

        while (true) {
            // Configured order is a preference over the viable envelope — never a source of membership.
            val strategy = CandidateSelectionStrategy { preference.preferred(remaining, configuredOrder) }
            when (val decision = selection.select(remaining, strategy)) {
                is CandidateSelectionDecision.NoSelection -> {
                    walk.failExhausted(decision.reason, authorizedSet.isEmpty(), lastFailure, lastCircuitOpen, request)
                }

                is CandidateSelectionDecision.Selected -> {
                    val candidate = decision.candidate
                    val route = routeOf.getValue(candidate)
                    val admission = circuitBreaker.beforeCall(candidate.providerId)
                    if (admission is CircuitBreakerAdmission.Rejected) {
                        val error = CircuitBreakerOpenException(candidate.providerId, admission.blockedUntilMillis)
                        lastCircuitOpen = error
                        remaining = remaining.without(candidate)
                        walk.transition(
                            error,
                            route,
                            walk.nextSelected(remaining, configuredOrder, routeOf),
                            ProviderFallbackReason.CIRCUIT_BREAKER_OPEN,
                            request,
                        )
                        continue
                    }
                    val permit = (admission as CircuitBreakerAdmission.Allowed).permit
                    try {
                        request.beforeRoute.beforeRoute()
                        return attemptExecutor.execute(
                            routeRequest(route, resolvedRoutes.indexOf(route), request, permit),
                        )
                    } catch (error: Throwable) {
                        error.rethrowIfCancellation()
                        lastFailure = error
                        remaining =
                            walk.continuationAfter(candidateSet, remaining, candidate, error, request)
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
     * The pre-0.7.3h execution path, preserved unchanged for engines with no governed routing
     * topology configured. Route walking, retry policy and circuit-breaker accounting behave exactly
     * as before; this path is unreachable once a governed topology exists, because the branch above
     * returns here only when no governed configuration is present.
     */
    private suspend fun executeLegacy(
        request: ProviderExecutionRequest,
        candidates: List<ResolvedProviderRoute>,
    ): ProviderCallResult {
        var lastFailure: Throwable? = null
        var lastCircuitOpen: CircuitBreakerOpenException? = null
        for ((index, route) in candidates.withIndex()) {
            val next = candidates.getOrNull(index + 1)
            val admission = circuitBreaker.beforeCall(route.providerName)
            if (admission is CircuitBreakerAdmission.Rejected) {
                val error = CircuitBreakerOpenException(route.providerName, admission.blockedUntilMillis)
                walk.transition(error, route, next, ProviderFallbackReason.CIRCUIT_BREAKER_OPEN, request)
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
                    ProviderFallbackDecision.Stop -> {
                        throw error
                    }

                    is ProviderFallbackDecision.Continue -> {
                        walk.transition(error, route, next, decision.reason, request)
                        lastFailure = error
                    }
                }
            } finally {
                // Structural permit relinquishment: scope exit always discharges the admission.
                circuitBreaker.onAbandoned(permit)
            }
        }
        throw lastFailure ?: lastCircuitOpen ?: ProviderException(
            "No available provider route for model '${request.operation.operation.model}'",
            retryable = true,
        )
    }

    private fun routeRequest(
        route: ResolvedProviderRoute,
        routeIndex: Int,
        request: ProviderExecutionRequest,
        permit: CircuitBreakerPermit,
    ) = ProviderRetryRequest(
        providerId = route.providerName,
        provider = route.provider,
        request =
            ModelRequest(
                model = route.effectiveModelName,
                messages = request.messages.toList(),
                tools = request.operation.toolDefinitions.takeIf { it.isNotEmpty() },
                timeoutMillis = request.operation.operation.timeoutMillis,
                operationInterface = request.operation.method.declaringClass.name,
                operationMethod = request.operation.method.name,
            ),
        operation = request.operation,
        attemptCounter = request.attemptCounter,
        routeIndex = routeIndex,
        correlationId = request.correlationId,
        securityContext = request.securityContext,
        permit =
        permit,
    )
}

/**
 * The governed candidate walk for one execution: which candidates may run, in what order, how a
 * route maps back to its candidate, and which fallback transitions that walk must announce.
 *
 * A collaborator rather than part of the coordinator because the coordinator's job is admission
 * and dispatch: the walk owns candidate-set derivation, narrowing and exhaustion reporting.
 * The class is file-private, so its members are not reachable outside this file.
 */
private class GovernedCandidateWalk(
    private val routingPlan: ProviderRoutingPlan,
    private val circuitBreaker: ProviderCircuitBreaker,
    private val fallbackGate: ProviderFallbackGate,
    private val fallbackPolicy: ProviderFallbackPolicy,
) {
    /**
     * The governed candidate set for one execution: the authorized envelope, the configured order,
     * the candidate-to-route lookup and the viability snapshot it starts from. Authorization and
     * workload resolution happen here, so a refused workload fails before any provider is reached.
     */
    fun governedCandidateSet(
        request: ProviderExecutionRequest,
        resolvedRoutes: List<ResolvedProviderRoute>,
        configuration: ProviderGovernanceConfiguration,
        run: GovernedRunIdentity,
    ): GovernedCandidateSet {
        // The workload's zone is looked up by its exact deployment identity: it is never inferred
        // from an environment convention, and an unconfigured deployment has no zone at all.
        val workloadZone = workloadZoneOf(configuration, run)

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
        val workload =
            WorkloadGovernanceResolver.resolve(
                identity = run.deployment,
                signals = signalsOf(request.securityContext),
                deploymentZone = workloadZone,
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

        // Required capabilities come from the actual request the provider will receive, so capability
        // refusal happens in authorization rather than after selection. Nothing is inferred from a
        // return type: a structured service still prompts, parses and repairs without a native
        // structured-output capability, so STRUCTURED_OUTPUT is not required here.
        val requiredCapabilities = deriveRequiredCapabilities(request.operation, request.messages, streaming = false)

        // The engine reads authorization through the public set view: an authorization envelope
        // cannot be inspected or fabricated from outside the security module, which is the point.
        val authorizedSet =
            authorization.authorizedSet(
                configuredOrder,
                governanceFacts.trustZone,
                governanceFacts.classification,
                requiredCapabilities,
            )
        val authorized =
            authorization.authorizedCandidates(
                configuredOrder,
                governanceFacts.trustZone,
                governanceFacts.classification,
                requiredCapabilities,
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
        return GovernedCandidateSet(
            authorizedSet = authorizedSet,
            configuredOrder = configuredOrder,
            routeOf = routeOf,
            remaining = viability.viableCandidates(authorized),
        )
    }

    /**
     * Hands the fallback policy every exclusion execution actually advances past. Availability answers
     * "can this execute now?", never "may execution continue?", so each such transition asks the same
     * continuation question a rejection at beforeCall asks below. A gate approval permits continuation
     * only: it cannot restore the excluded candidate, widen the envelope, or reach a route the envelope
     * does not carry. Only an exclusion positioned before the candidate about to run gates it, so no
     * transition exists for exclusions after it and the gate is never consulted on their behalf.
     *
     * @return the last circuit-open failure announced, or null when no transition was made.
     */
    suspend fun announceAvailabilityTransitions(
        authorizedSet: Set<ProviderCandidate>,
        remaining: ViableCandidates,
        configuredOrder: List<ProviderCandidate>,
        routeOf: Map<ProviderCandidate, ResolvedProviderRoute>,
        request: ProviderExecutionRequest,
    ): CircuitBreakerOpenException? {
        val continuationCandidate = remaining.orderedBy(configuredOrder).firstOrNull() ?: return null
        val continuationPosition = configuredOrder.indexOf(continuationCandidate)
        val outsideEnvelope =
            excludedByAvailability(authorizedSet, remaining, configuredOrder, circuitBreaker)
                .filter { configuredOrder.indexOf(it) < continuationPosition }
        var lastCircuitOpen: CircuitBreakerOpenException? = null
        for (excluded in outsideEnvelope) {
            val error =
                CircuitBreakerOpenException(
                    excluded.providerId,
                    circuitBreaker.openUntilMillis(excluded.providerId) ?: 0L,
                )
            lastCircuitOpen = error
            transition(
                error,
                routeOf.getValue(excluded),
                routeOf.getValue(continuationCandidate),
                ProviderFallbackReason.CIRCUIT_BREAKER_OPEN,
                request,
            )
        }
        return lastCircuitOpen
    }

    /**
     * Maps each resolved route to its exact candidate through the route's authoritative deployment.
     * A route with no configured deployment yields no candidate, so it can never be authorized,
     * viable, selected or invoked. Two routes collapsing onto one candidate is ambiguous, and
     * ambiguity fails closed rather than being guessed by provider or model name.
     */
    fun mapRoutesToCandidates(
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

    /**
     * Decides whether the walk goes on after a candidate failed. The failure policy is authoritative:
     * Stop rethrows the original error unchanged, Continue narrows the CURRENT remainder (never the
     * envelope snapshot) and announces the transition. Returns the narrowed remainder.
     */
    suspend fun continuationAfter(
        set: GovernedCandidateSet,
        remaining: ViableCandidates,
        candidate: ProviderCandidate,
        failure: Throwable,
        request: ProviderExecutionRequest,
    ): ViableCandidates =
        when (val fallback = fallbackPolicy.decide(failure)) {
            ProviderFallbackDecision.Stop -> {
                throw failure
            }

            is ProviderFallbackDecision.Continue -> {
                val narrowed = remaining.without(candidate)
                transition(
                    failure,
                    set.routeOf.getValue(candidate),
                    nextSelected(narrowed, set.configuredOrder, set.routeOf),
                    fallback.reason,
                    request,
                )
                narrowed
            }
        }

    /** The candidate governance would select next from the narrowed envelope, or null. */
    fun nextSelected(
        remaining: ViableCandidates,
        configuredOrder: List<ProviderCandidate>,
        routeOf: Map<ProviderCandidate, ResolvedProviderRoute>,
    ): ResolvedProviderRoute? = remaining.orderedBy(configuredOrder).firstOrNull()?.let { routeOf[it] }

    fun signalsOf(context: ExecutionSecurityContext): List<WorkloadClassificationSignal> {
        val classification = context.dataClassification
        val source = context.classificationSource
        if (classification == null || source == null) return emptyList()
        return listOf(WorkloadClassificationSignal(classification, source))
    }

    /** Terminal refusal: no candidate remains selectable. Fails closed rather than returning empty. */
    fun failExhausted(
        reason: SelectionRefusal,
        nothingAuthorized: Boolean,
        lastFailure: Throwable?,
        lastCircuitOpen: CircuitBreakerOpenException?,
        request: ProviderExecutionRequest,
    ): Nothing = throw exhausted(reason, nothingAuthorized, lastFailure, lastCircuitOpen, request)

    fun exhausted(
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
                    ?: ProviderException(
                        "No available provider route for model '${request.operation.operation.model}'",
                        retryable = true,
                    )
            }

            else -> {
                ProviderException("Selection refused: ${reason.name}", retryable = false)
            }
        }

    suspend fun transition(
        error: Throwable,
        route: ResolvedProviderRoute,
        next: ResolvedProviderRoute?,
        reason: ProviderFallbackReason,
        request: ProviderExecutionRequest,
    ) {
        if (next == null) return
        try {
            fallbackGate.transition(
                request.correlationId,
                route.providerName,
                route.effectiveModelName,
                next.providerName,
                fallbackPolicy.reasonString(reason),
                request.securityContext,
            )
        } catch (policyError: PolicyViolationException) {
            policyError.addSuppressed(error)
            throw policyError
        }
    }
}

/** What one governed execution may run, in what order, and where each candidate's route is. */
private class GovernedCandidateSet(
    val authorizedSet: Set<ProviderCandidate>,
    val configuredOrder: List<ProviderCandidate>,
    val routeOf: Map<ProviderCandidate, ResolvedProviderRoute>,
    val remaining: ViableCandidates,
)
