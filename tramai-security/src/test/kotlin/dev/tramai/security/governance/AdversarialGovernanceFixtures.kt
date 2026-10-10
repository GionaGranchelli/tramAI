package dev.tramai.security.governance

import dev.tramai.core.model.ModelRequest
import dev.tramai.core.model.ModelResponse
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.provider.ModelProvider
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.core.provider.ProviderRoutingPlan
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone

/*
 * Shared 0.7.3g fixtures. Every candidate here flows through the real stages —
 * [CandidateAuthorization], [CandidateViability] and [CandidateSelection] — so a proof is measured
 * against the composed decision path and not against hand-built [AuthorizedCandidates] or
 * [ViableCandidates] values.
 *
 * The six provider shapes are the epic's named ones: A passes everything, B is governed but
 * unavailable, C is missing a required capability, D is unregistered, E is in an ineligible zone
 * and F is identity-inconsistent.
 */

/** The workload zone every fixture authorizes from. */
internal val WORKLOAD_ZONE = ProviderTrustZone.LOCAL

/** The deployment zone every governed fixture serves from: the one permitted pair's target. */
internal val GOVERNED_ZONE = ProviderTrustZone.EU_CLOUD

/**
 * A provider that records whether it was ever asked to generate, and fails loudly if it was.
 *
 * `complete` is unreachable in a decision-only slice; the counter exists so the proof can assert
 * zero invocation rather than assert that an exception did not happen to be thrown.
 */
internal class RecordingProvider(
    private val supported: Set<ProviderCapability>,
) : ModelProvider {
    var invocations: Int = 0
        private set

    override fun supportsCapability(capability: ProviderCapability): Boolean = capability in supported

    override suspend fun complete(request: ModelRequest): ModelResponse {
        invocations++
        error("0.7.3g is a decision-only proof: no provider invocation may occur")
    }
}

/** Records each candidate the viability stage evaluated, so "never evaluated" is provable. */
internal class ViabilityProbe(
    private val unavailable: Set<ProviderCandidate> = emptySet(),
) {
    val evaluated: MutableList<ProviderCandidate> = mutableListOf()

    val evaluator: (ProviderCandidate) -> ViabilityRefusal? = { candidate ->
        evaluated += candidate
        if (candidate in unavailable) ViabilityRefusal.AVAILABILITY else null
    }
}

/**
 * The 0.7.3g fixture world: real registration, real policy, real stages.
 *
 * Zones: the only permitted workload→deployment pair is LOCAL → EU_CLOUD. INTERNAL and CONFIDENTIAL
 * permit EU_CLOUD; RESTRICTED permits LOCAL only.
 */
internal class GovernanceWorld(
    /** Providers the registration snapshot names, and the capabilities each supports. */
    private val registered: Map<String, Set<ProviderCapability>> = DEFAULT_REGISTERED,
    /** Whether the routing plan also names a default provider, as a configured fallback would. */
    configuredDefault: String? = null,
    /** Whether the workload→deployment zone pair is listed at all. */
    private val listZonePair: Boolean = true,
    /** Whether the classification rules permit the governed deployment zone. */
    private val classificationsPermitEu: Boolean = true,
) {
    val providerStubs: Map<String, RecordingProvider> =
        registered.mapValues { (_, capabilities) -> RecordingProvider(capabilities) }

    val registration: ProviderRoutingPlan =
        ProviderRoutingPlan
            .builder()
            .apply {
                providerStubs.forEach { (name, stub) -> provider(name, stub) }
                if (providerStubs.isNotEmpty()) {
                    // Only route models to providers this snapshot actually registers: the routing plan
                    // is fail-closed about a route naming an unknown provider, and a tamper fixture that
                    // removed a registration must not become an invalid plan instead of a refused
                    // candidate.
                    if (ALPHA_PROVIDER in providerStubs) model(ALPHA_MODEL, ALPHA_PROVIDER)
                    if (BETA_PROVIDER in providerStubs) model(BETA_MODEL, BETA_PROVIDER)
                }
                configuredDefault?.takeIf { it in providerStubs.keys }?.let { defaultProvider(it) }
            }.build()

    val trustZonePolicy: TrustZonePolicy =
        TrustZonePolicy(
            if (listZonePair) {
                setOf(WORKLOAD_ZONE to GOVERNED_ZONE)
            } else {
                emptySet()
            },
        )

    val classificationRules: Map<DataClassification, ClassificationRoutingRule> =
        mapOf(
            DataClassification.INTERNAL to
                ClassificationRoutingRule(
                    allowedZones = if (classificationsPermitEu) setOf(GOVERNED_ZONE) else setOf(WORKLOAD_ZONE),
                    allowedFallbackZones = emptySet(),
                ),
            DataClassification.CONFIDENTIAL to
                ClassificationRoutingRule(
                    allowedZones = if (classificationsPermitEu) setOf(GOVERNED_ZONE) else setOf(WORKLOAD_ZONE),
                    allowedFallbackZones = emptySet(),
                ),
            DataClassification.RESTRICTED to
                ClassificationRoutingRule(
                    allowedZones = setOf(WORKLOAD_ZONE),
                    allowedFallbackZones = emptySet(),
                ),
        )

    val authorization: CandidateAuthorization =
        CandidateAuthorization(ProviderInputRelease(trustZonePolicy, classificationRules), registration)

    // ---- the six provider shapes ------------------------------------------------------------

    /** A: registered, capability-compatible, trust-compatible, classification-compatible. */
    val candidateA: ProviderCandidate = candidate(ALPHA_PROVIDER, ALPHA_MODEL, "dep-alpha", GOVERNED_ZONE)

    /** B: governed exactly like A, so its only possible refusal is at the viability stage. */
    val candidateB: ProviderCandidate = candidate(BETA_PROVIDER, BETA_MODEL, "dep-beta", GOVERNED_ZONE)

    /** C: registered and governed, but the provider cannot perform the required capability. */
    val candidateC: ProviderCandidate = candidate(GAMMA_PROVIDER, GAMMA_MODEL, "dep-gamma", GOVERNED_ZONE)

    /** D: absent from the registration snapshot entirely. */
    val candidateD: ProviderCandidate = candidate(DELTA_PROVIDER, DELTA_MODEL, "dep-delta", GOVERNED_ZONE)

    /** E: registered and capable, but deployed in a zone no permitted pair or rule allows. */
    val candidateE: ProviderCandidate =
        candidate(EPSILON_PROVIDER, EPSILON_MODEL, "dep-epsilon", ProviderTrustZone.GLOBAL_CLOUD)

    /** F: identity names one provider while its deployment names another. */
    val candidateF: ProviderCandidate =
        ProviderCandidate(
            providerId = ALPHA_PROVIDER,
            modelId = ALPHA_MODEL,
            deployment =
                ProviderDeployment(
                    deploymentId = "dep-mismatched",
                    providerId = ZETA_PROVIDER,
                    trustZone = NamedTrustZone(TrustZoneName("dep-mismatched-zone"), GOVERNED_ZONE),
                ),
        )

    /** A second fully-governed provider, so the viable set can hold more than one candidate. */
    val candidateA2: ProviderCandidate = candidate(ETA_PROVIDER, ETA_MODEL, "dep-eta", GOVERNED_ZONE)

    /** The six named provider shapes in one list, for the matrix cases. */
    val allShapes: List<ProviderCandidate> =
        listOf(candidateA, candidateB, candidateC, candidateD, candidateE, candidateF)

    /** The capability the fixtures require when a case needs one. */
    val requiredCapability: Set<ProviderCapability> = setOf(ProviderCapability.TOOL_CALLING)

    fun candidate(
        providerId: String,
        modelId: String,
        deploymentId: String,
        zone: ProviderTrustZone,
    ): ProviderCandidate =
        ProviderCandidate(
            providerId = providerId,
            modelId = modelId,
            deployment =
                ProviderDeployment(
                    deploymentId = deploymentId,
                    providerId = providerId,
                    trustZone = NamedTrustZone(TrustZoneName("$deploymentId-zone"), zone),
                ),
        )

    // ---- the composed stages ----------------------------------------------------------------

    /** The full composed path, with the viability evaluator instrumented. */
    fun pipeline(
        candidates: Collection<ProviderCandidate>,
        classification: DataClassification = DataClassification.INTERNAL,
        requiredCapabilities: Set<ProviderCapability> = emptySet(),
        unavailable: Set<ProviderCandidate> = emptySet(),
    ): Pipeline {
        val probe = ViabilityProbe(unavailable)
        val authorized =
            authorization.authorizedCandidates(candidates, WORKLOAD_ZONE, classification, requiredCapabilities)
        val viability = CandidateViability(probe.evaluator)
        val decisions = viability.decisions(authorized)
        val viable = viability.viableCandidates(authorized)
        val governingDecision: (ProviderCandidate) -> CandidateAuthorizationDecision = { candidate ->
            authorization.decisionFor(candidate, WORKLOAD_ZONE, classification, requiredCapabilities)
        }
        return Pipeline(authorized, decisions, viable, probe, governingDecision)
    }

    /** Every provider stub, for the no-invocation proof. */
    fun invocationCounts(): Map<String, Int> = providerStubs.mapValues { (_, stub) -> stub.invocations }

    internal companion object {
        const val ALPHA_PROVIDER = "alpha"
        const val BETA_PROVIDER = "beta"
        const val GAMMA_PROVIDER = "gamma"
        const val DELTA_PROVIDER = "delta"
        const val EPSILON_PROVIDER = "epsilon"
        const val ETA_PROVIDER = "eta"
        const val ZETA_PROVIDER = "zeta"

        const val ALPHA_MODEL = "alpha-large"
        const val BETA_MODEL = "beta-large"
        const val GAMMA_MODEL = "gamma-small"
        const val DELTA_MODEL = "delta-large"
        const val EPSILON_MODEL = "epsilon-large"
        const val ETA_MODEL = "eta-large"

        /** alpha/beta/eta support everything; gamma is missing TOOL_CALLING; delta is unregistered. */
        val DEFAULT_REGISTERED: Map<String, Set<ProviderCapability>> =
            mapOf(
                ALPHA_PROVIDER to ProviderCapability.entries.toSet(),
                BETA_PROVIDER to ProviderCapability.entries.toSet(),
                GAMMA_PROVIDER to setOf(ProviderCapability.VISION),
                EPSILON_PROVIDER to ProviderCapability.entries.toSet(),
                ETA_PROVIDER to ProviderCapability.entries.toSet(),
            )
    }
}

/** The composed decision path's observable stages, plus the instrument that proves call scope. */
internal class Pipeline(
    val authorized: AuthorizedCandidates,
    val viabilityDecisions: Map<ProviderCandidate, CandidateViabilityDecision>,
    val viable: ViableCandidates,
    val probe: ViabilityProbe,
    /**
     * The same authorization boundary and inputs that produced [authorized], so a case can report one
     * candidate's verdict next to the sets those inputs produced.
     */
    val decision: (ProviderCandidate) -> CandidateAuthorizationDecision,
) {
    /** The authorized members, as a plain set, for membership assertions. */
    val authorizedSet: Set<ProviderCandidate> = authorized.candidates.toSet()

    /** The viable members, as a plain set. */
    val viableSet: Set<ProviderCandidate> = viable.candidates.toSet()

    /** Whether the viability evaluator was asked about [candidate]. */
    fun viabilityEvaluated(candidate: ProviderCandidate): Boolean = candidate in probe.evaluated
}
