package dev.tramai.engine.provider

import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.provider.ProviderCapability
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZonePolicy

/**
 * The authoritative facts that let provider execution reach the 0.7.3 authority chain.
 *
 * These are configuration and identity, not policy reasoning: this type carries what
 * exists, and [dev.tramai.security.governance.CandidateAuthorization] decides what it
 * permits. Nothing here is defaulted, inferred or derived from a provider brand — a
 * member that is absent refuses rather than widening.
 *
 * Two lifetimes are deliberately separate, so a value cannot be accidentally reused
 * across runs:
 *
 * - [ProviderGovernanceConfiguration] is fixed for the engine (rules, the workload to
 *   provider zone relation, deployments, required capabilities);
 * - [ProviderRunGovernance] belongs to one governed run (workload identity and the zone
 *   that workload actually runs in).
 */
internal data class ProviderGovernanceConfiguration(
    /** Permitted zones per classification. A classification absent here permits nothing. */
    val rules: Map<DataClassification, ClassificationRoutingRule>,
    /** Which workload-zone to provider-zone pairs are permitted at all. */
    val trustZonePolicy: TrustZonePolicy,
    /**
     * The authoritative deployment of a registered provider id, or `null` when the
     * composition cannot establish one — which refuses, because a provider brand does
     * not establish trust.
     *
     * A registered provider id names one registered provider deployment, the same
     * granularity as `ProviderRoutingPlan.providers`. Two deployments of one brand are
     * therefore two registrations with distinct deployment ids and zones, and they do
     * not collapse into one another.
     */
    val deploymentOf: (providerId: String) -> ProviderDeployment?,
    /** What this execution requires of a provider. Empty adds no capability restriction. */
    val requiredCapabilities: Set<ProviderCapability> = emptySet(),
)

/**
 * The governed-workload facts for one run.
 *
 * Both members are required. A run whose workload identity or deployment zone cannot be
 * established has no authoritative trust zone, and this boundary has no permissive
 * fallback for that: an unknown workload zone is not `LOCAL` and not `GLOBAL`.
 */
internal data class ProviderRunGovernance(
    val workloadIdentity: WorkloadDeploymentIdentity,
    /** The zone the workload actually runs in, or `null` when it cannot be established. */
    val workloadZone: ProviderTrustZone?,
)
