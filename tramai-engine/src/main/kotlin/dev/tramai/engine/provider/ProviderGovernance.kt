package dev.tramai.engine.provider

import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderRoutingConfiguration
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZonePolicy

/**
 * The routing topology the provider execution path authorizes from, projected from the configured
 * [ProviderRoutingConfiguration].
 *
 * Every fact here is configured, never inferred: a workload deployment's zone is looked up by its
 * exact identity, and a provider's deployment is only what the configuration registered. An absent
 * entry means no authority — there is no default zone, no zone inferred from an environment
 * convention, and no `ProviderDeployment` derived from a provider name or a legacy zone entry.
 *
 * Required capabilities are deliberately absent: they are derived from the actual request the
 * provider will receive, so there is no second surface that could disagree with it.
 */
internal class ProviderGovernanceConfiguration(
    val workloadZones: Map<WorkloadDeploymentIdentity, ProviderTrustZone>,
    val rules: Map<DataClassification, ClassificationRoutingRule>,
    val trustZonePolicy: TrustZonePolicy,
    val deploymentOf: (String) -> ProviderDeployment?,
) {
    companion object {
        /** Projects the configured routing topology; the configured zone pairs feed the existing policy. */
        fun from(routing: ProviderRoutingConfiguration): ProviderGovernanceConfiguration =
            ProviderGovernanceConfiguration(
                workloadZones = routing.workloadZones,
                rules = routing.rules,
                trustZonePolicy = TrustZonePolicy(routing.allowedZonePairs),
                deploymentOf = { routing.providerDeployments[it] },
            )
    }
}
