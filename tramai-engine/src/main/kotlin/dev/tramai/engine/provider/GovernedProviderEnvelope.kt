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
 * Derives the authority envelope for one execution request from the configured topology and observed
 * runtime availability. Shared by the synchronous and streaming paths so the two cannot drift.
 *
 * Fails closed on every absent fact: no governed configuration, no governed run, an unconfigured
 * workload zone, a refused workload resolution, or a route whose provider has no configured
 * deployment. Nothing is inferred — not a zone from an environment convention, not a deployment from
 * a provider name, and not a capability from a provider's identity.
 */
internal fun governProviderExecution(
    routes: List<ResolvedProviderRoute>,
    routingPlan: ProviderRoutingPlan,
    configuration: ProviderGovernanceConfiguration?,
    governedRun: GovernedRunIdentity?,
    securityContext: ExecutionSecurityContext,
    circuitBreaker: ProviderCircuitBreaker,
    requiredCapabilities: Set<ProviderCapability>,
): GovernedProviderEnvelope {
    val governed = configuration ?: throw governanceAbsent()
    val run = governedRun ?: throw governanceAbsent()
    val workloadZone =
        governed.workloadZones[run.deployment]
            ?: throw ProviderException(
                "Workload deployment '${run.deployment.deploymentId}' has no configured trust zone",
                retryable = false,
            )

    val mappings = routeCandidates(routes, governed)
    val configuredOrder = mappings.map { it.second }
    val routesByCandidate = mappings.associate { (route, candidate) -> candidate to route }
    val indexByRoute = mappings.mapIndexed { index, (route, _) -> route to index }.toMap()

    val workload =
        WorkloadGovernanceResolver.resolve(
            identity = run.deployment,
            signals = signalsOf(securityContext),
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
        CandidateAuthorization(ProviderInputRelease(governed.trustZonePolicy, governed.rules), routingPlan)
    val authorizedSet =
        authorization.authorizedSet(configuredOrder, resolved.trustZone, resolved.classification, requiredCapabilities)
    val authorized =
        authorization.authorizedCandidates(configuredOrder, resolved.trustZone, resolved.classification, requiredCapabilities)

    // Availability is observed, never consumed: beforeCall grants a permit and can revive an expired
    // circuit, so evaluating viability with it would change the state it is reading.
    val viability = CandidateViability { candidate ->
        if (circuitBreaker.openUntilMillis(candidate.providerId) != null) ViabilityRefusal.AVAILABILITY else null
    }
    val viable = viability.viableCandidates(authorized)
    return GovernedProviderEnvelope(viable, authorizedSet.isEmpty(), routesByCandidate, indexByRoute, configuredOrder)
}

/** Maps each resolved route to its exact candidate through the route's authoritative deployment. */
private fun routeCandidates(
    routes: List<ResolvedProviderRoute>,
    configuration: ProviderGovernanceConfiguration,
): List<Pair<ResolvedProviderRoute, ProviderCandidate>> {
    val mappings = ArrayList<Pair<ResolvedProviderRoute, ProviderCandidate>>(routes.size)
    val seen = LinkedHashMap<ProviderCandidate, ResolvedProviderRoute>()
    routes.forEach { route ->
        val deployment = configuration.deploymentOf(route.providerName) ?: return@forEach
        val candidate = ProviderCandidate(route.providerName, route.effectiveModelName, deployment)
        val existing = seen.put(candidate, route)
        if (existing != null && existing != route) {
            throw ProviderException(
                "Configured routes ${existing.providerName}/${existing.effectiveModelName} and " +
                    "${route.providerName}/${route.effectiveModelName} map to one candidate: ambiguous candidate identity",
                retryable = false,
            )
        }
        mappings += route to candidate
    }
    return mappings
}

/** The governed run's classification claim, or none: a missing claim refuses rather than defaulting. */
internal fun signalsOf(context: ExecutionSecurityContext): List<WorkloadClassificationSignal> {
    val classification = context.dataClassification ?: return emptyList()
    val source = context.classificationSource ?: return emptyList()
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
    val required = LinkedHashSet<ProviderCapability>(3)
    if (messages.any { it.hasImage() }) required += ProviderCapability.VISION
    if (operation.toolDefinitions.isNotEmpty()) required += ProviderCapability.TOOL_CALLING
    if (streaming) required += ProviderCapability.STREAMING
    return required
}

internal fun governanceAbsent() =
    ProviderException(
        "Provider execution requires the configured routing topology and a governed run; refusing to invoke a provider without them",
        retryable = false,
    )
