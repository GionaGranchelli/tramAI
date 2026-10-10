package dev.tramai.engine.provider

import dev.tramai.core.exception.ProviderException
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.model.Message
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.core.provider.ResolvedProviderRoute
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.OperationDefinition
import dev.tramai.engine.ProviderCircuitBreaker
import dev.tramai.security.governance.AuthorizedCandidates
import dev.tramai.security.governance.CandidateAuthorization
import dev.tramai.security.governance.CandidateSelection
import dev.tramai.security.governance.CandidateSelectionStrategy
import dev.tramai.security.governance.CandidateViability
import dev.tramai.security.governance.ProviderCandidate
import dev.tramai.security.governance.ProviderInputRelease
import dev.tramai.security.governance.ViabilityRefusal
import dev.tramai.security.governance.ViableCandidates
import dev.tramai.security.governance.WorkloadClassificationSignal
import dev.tramai.security.governance.WorkloadGovernanceResolution
import dev.tramai.security.governance.WorkloadGovernanceResolver

/**
 * The authority envelope for one execution request: the viable candidates plus the exact mapping back
 * to the configured route each came from.
 *
 * `invoked ⇒ selected ⇒ viable ⇒ authorized` is the point of this type: neither the synchronous nor
 * the streaming path may reach a provider except through a candidate obtained here.
 */
internal class GovernedProviderEnvelope(
    /**
     * Authorization's admitted set, read-only. Availability is observed separately, so a candidate
     * here that is absent from [viable] was excluded for a runtime reason rather than a refusal.
     */
    val authorized: Set<ProviderCandidate>,
    /** The envelope authority: retry and fallback narrow it with [narrowedAfter] and reselect from it. */
    val viable: ViableCandidates,
    /** True when authorization admitted nothing: a governance refusal, not an exhausted attempt. */
    val authorizedNothing: Boolean,
    private val routesByCandidate: Map<ProviderCandidate, ResolvedProviderRoute>,
    private val indexByRoute: Map<ResolvedProviderRoute, Int>,
    /** Configured route order: a preference over the envelope, never a source of membership. */
    val configuredOrder: List<ProviderCandidate>,
) {
    val selection = CandidateSelection()

    /** The exactly one configured route this candidate came from. */
    fun routeOf(candidate: ProviderCandidate): ResolvedProviderRoute = routesByCandidate.getValue(candidate)

    /** The configured position of a route, preserved so route observation and attempt numbering do not move. */
    fun routeIndexOf(route: ResolvedProviderRoute): Int = indexByRoute.getValue(route)

    /** The envelope after an attempted candidate is removed. Narrowing only, by construction. */
    fun narrowedAfter(candidate: ProviderCandidate): ViableCandidates = viable.without(candidate)

    /** The next governance-selected route in an envelope, or null when it holds nothing selectable. */
    fun nextSelected(remaining: ViableCandidates): ResolvedProviderRoute? =
        remaining.orderedBy(configuredOrder).firstOrNull()?.let { routesByCandidate.getValue(it) }

    /**
     * Configured-order preference over the eligible set. The strategy receives the defensive view
     * [CandidateSelection] hands out, so it filters the configured order through that view: it can
     * only ever choose among candidates already in the envelope.
     */
    val preferConfiguredOrder: CandidateSelectionStrategy =
        CandidateSelectionStrategy { eligible -> configuredOrder.firstOrNull { it in eligible } }
}

/**
 * Authorized candidates excluded from the viable envelope because their circuit is already open, in
 * configured order.
 *
 * These are candidates execution advances past for an availability reason, so each transition past
 * one has to pass through the continuation policy before another candidate may execute. A candidate
 * authorization refused is deliberately not one of these: a refusal is not an availability
 * condition, and the continuation question is never asked on its behalf.
 *
 * Observed read-only: `beforeCall` both grants and consumes a permit, so using it here would change
 * the state it is reading and could revive an expired circuit.
 */
internal fun excludedByAvailability(
    authorized: Set<ProviderCandidate>,
    viable: ViableCandidates,
    configuredOrder: List<ProviderCandidate>,
    circuitBreaker: ProviderCircuitBreaker,
): List<ProviderCandidate> =
    configuredOrder.filter { candidate ->
        candidate in authorized &&
            !viable.contains(candidate) &&
            circuitBreaker.openUntilMillis(candidate.providerId) != null
    }

/**
 * What governance must evaluate for one execution: the resolved routes, the routing plan they came
 * from, the security context that supplies classification signals, and the capabilities this
 * execution requires. They travel together because they describe one execution's evaluation input.
 */
internal data class GovernedExecutionInput(
    val routes: List<ResolvedProviderRoute>,
    val routingPlan: ProviderRoutingPlan,
    val securityContext: ExecutionSecurityContext,
    val requiredCapabilities: Set<ProviderCapability>,
)

/**
 * Derives the authority envelope for one execution request from the configured topology and observed
 * runtime availability. Shared by the synchronous and streaming paths so the two cannot drift.
 *
 * Fails closed on every absent fact: no governed configuration, no governed run, an unconfigured
 * workload zone, a refused workload resolution, or a route whose provider has no configured
 * deployment. Nothing is inferred — not a zone from an environment convention, not a deployment from
 * a provider name, and not a capability from a provider's identity.
 */
internal fun governProviderExecution(
    input: GovernedExecutionInput,
    configuration: ProviderGovernanceConfiguration?,
    governedRun: GovernedRunIdentity?,
    circuitBreaker: ProviderCircuitBreaker,
): GovernedProviderEnvelope {
    val governed = configuration
    val run = governedRun
    if (governed == null || run == null) {
        throw governanceAbsent()
    }
    val workloadZone = workloadZoneOf(governed, run)

    val mappings = routeCandidates(input.routes, governed)
    val configuredOrder = mappings.map { it.second }
    val routesByCandidate = mappings.associate { (route, candidate) -> candidate to route }
    val indexByRoute = mappings.mapIndexed { index, (route, _) -> route to index }.toMap()

    val workload =
        WorkloadGovernanceResolver.resolve(
            identity = run.deployment,
            signals = signalsOf(input.securityContext),
            deploymentZone = workloadZone,
            rules = governed.rules,
        )
    if (workload is WorkloadGovernanceResolution.Refused) {
        throw ProviderException("Provider execution is refused: ${workload.failure}", retryable = false)
    }
    val resolved = workload as WorkloadGovernanceResolution.Resolved

    // The release predicate is built from the governed configuration: a default release instance
    // carries an empty rule map, which would release nothing and refuse every candidate for a reason
    // the configuration never expressed.
    val authorization =
        CandidateAuthorization(ProviderInputRelease(governed.trustZonePolicy, governed.rules), input.routingPlan)
    val authorizedSet =
        authorization.authorizedSet(
            configuredOrder,
            resolved.trustZone,
            resolved.classification,
            input.requiredCapabilities,
        )
    val authorized =
        authorization.authorizedCandidates(
            configuredOrder,
            resolved.trustZone,
            resolved.classification,
            input.requiredCapabilities,
        )

    // Availability is observed, never consumed: beforeCall grants a permit and can revive an expired
    // circuit, so evaluating viability with it would change the state it is reading.
    val viability =
        CandidateViability { candidate ->
            if (circuitBreaker.openUntilMillis(candidate.providerId) != null) ViabilityRefusal.AVAILABILITY else null
        }
    val viable = viability.viableCandidates(authorized)
    return GovernedProviderEnvelope(
        authorizedSet,
        viable,
        authorizedSet.isEmpty(),
        routesByCandidate,
        indexByRoute,
        configuredOrder,
    )
}

/** Maps each resolved route to its exact candidate through the route's authoritative deployment. */
private fun routeCandidates(
    routes: List<ResolvedProviderRoute>,
    configuration: ProviderGovernanceConfiguration,
): List<Pair<ResolvedProviderRoute, ProviderCandidate>> {
    val mappings =
        routes.mapNotNull { route ->
            val deployment = configuration.deploymentOf(route.providerName) ?: return@mapNotNull null
            route to ProviderCandidate(route.providerName, route.effectiveModelName, deployment)
        }
    val collision =
        mappings
            .withIndex()
            .groupBy { it.value.second }
            .values
            .mapNotNull { group ->
                group.zipWithNext().firstOrNull { (previous, current) ->
                    previous.value.first != current.value.first
                }
            }.minByOrNull { it.second.index }
    val (previous, current) = collision ?: return mappings
    val existing = previous.value.first
    val route = current.value.first
    throw ProviderException(
        "Configured routes ${existing.providerName}/${existing.effectiveModelName} and " +
            "${route.providerName}/${route.effectiveModelName} map to one candidate: " +
            "ambiguous candidate identity",
        retryable = false,
    )
    return mappings
}

/** The governed run's classification claim, or none: a missing claim refuses rather than defaulting. */
internal fun signalsOf(context: ExecutionSecurityContext): List<WorkloadClassificationSignal> {
    val classification = context.dataClassification
    val source = context.classificationSource
    if (classification == null || source == null) return emptyList()
    return listOf(WorkloadClassificationSignal(classification, source))
}

/**
 * The capabilities the actual request requires, derived from the facts the provider request is built
 * from: image content needs VISION, exposed tool definitions need TOOL_CALLING, and a streaming
 * request needs STREAMING. Shared by both execution paths so they cannot drift.
 *
 * Nothing is inferred from a return type: a structured service still prompts, parses and repairs
 * without a native structured-output capability, so STRUCTURED_OUTPUT is never required here.
 */
internal fun deriveRequiredCapabilities(
    operation: OperationDefinition,
    messages: List<Message>,
    streaming: Boolean,
): Set<ProviderCapability> {
    val required = LinkedHashSet<ProviderCapability>()
    if (messages.any { it.hasImage() }) required += ProviderCapability.VISION
    if (operation.toolDefinitions.isNotEmpty()) required += ProviderCapability.TOOL_CALLING
    if (streaming) required += ProviderCapability.STREAMING
    return required
}

internal fun governanceAbsent() =
    ProviderException(
        "Provider execution requires the configured routing topology and a governed run; " +
            "refusing to invoke a provider without them",
        retryable = false,
    )

/**
 * The workload's zone, looked up by its exact deployment identity. An unconfigured deployment has no
 * zone at all: the absence is a configuration defect and fails closed rather than being inferred from
 * an environment convention. Shared by the synchronous and streaming governed paths.
 */
internal fun workloadZoneOf(
    configuration: ProviderGovernanceConfiguration,
    run: GovernedRunIdentity,
) = configuration.workloadZones[run.deployment]
    ?: throw ProviderException(
        "Workload deployment '${run.deployment.deploymentId}' has no configured trust zone",
        retryable = false,
    )
