package dev.tramai.engine.provider

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.policy.DataClassification
import dev.tramai.security.ClassificationRoutingRule
import dev.tramai.security.ProviderRoutingConfiguration
import dev.tramai.security.ProviderTrustZone
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZoneName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the governed/legacy discriminator. [ProviderGovernanceConfiguration.from] is the single
 * decision that sends an execution down the governed path or keeps the pre-0.7.3h path, so every
 * topology field is asserted on its own: a configuration carrying several of them at once cannot
 * show which one admitted the execution to governance.
 */
class ProviderGovernanceConfigurationTest {
    @Test
    fun `a defaulted routing configuration keeps the pre-0_7_3h path`() {
        assertThat(ProviderGovernanceConfiguration.from(ProviderRoutingConfiguration())).isNull()
    }

    @Test
    fun `a sovereign rule matrix alone is not governed topology`() {
        val routing =
            ProviderRoutingConfiguration(
                rules =
                    mapOf(
                        DataClassification.INTERNAL to
                            ClassificationRoutingRule(
                                allowedZones = setOf(ProviderTrustZone.LOCAL),
                                allowedFallbackZones = emptySet(),
                            ),
                    ),
            )
        assertThat(ProviderGovernanceConfiguration.from(routing)).isNull()
    }

    @Test
    fun `enabled alone governs and projects no deployment`() {
        val governed = ProviderGovernanceConfiguration.from(ProviderRoutingConfiguration(enabled = true))
        assertThat(governed).isNotNull()
        assertThat(governed?.deploymentOf("alpha")).isNull()
    }

    @Test
    fun `a workload zone alone governs`() {
        val routing = ProviderRoutingConfiguration(workloadZones = mapOf(workload to ProviderTrustZone.LOCAL))
        val governed = ProviderGovernanceConfiguration.from(routing)
        assertThat(governed?.workloadZones).containsEntry(workload, ProviderTrustZone.LOCAL)
    }

    @Test
    fun `a provider deployment alone governs and resolves by provider id`() {
        val deployment = deployment("alpha")
        val routing = ProviderRoutingConfiguration(providerDeployments = mapOf("alpha" to deployment))
        val governed = ProviderGovernanceConfiguration.from(routing)
        assertThat(governed?.deploymentOf("alpha")).isEqualTo(deployment)
        assertThat(governed?.deploymentOf("beta")).isNull()
    }

    @Test
    fun `an allowed zone pair alone governs`() {
        val routing =
            ProviderRoutingConfiguration(
                allowedZonePairs = setOf(ProviderTrustZone.LOCAL to ProviderTrustZone.LOCAL),
            )
        assertThat(ProviderGovernanceConfiguration.from(routing)).isNotNull()
    }

    @Test
    fun `the configured rules and deployments survive the projection`() {
        val rules =
            mapOf(
                DataClassification.RESTRICTED to
                    ClassificationRoutingRule(
                        allowedZones = setOf(ProviderTrustZone.LOCAL),
                        allowedFallbackZones = emptySet(),
                    ),
            )
        val routing =
            ProviderRoutingConfiguration(
                enabled = true,
                rules = rules,
                providerDeployments = mapOf("alpha" to deployment("alpha")),
            )
        val governed = ProviderGovernanceConfiguration.from(routing)
        assertThat(governed?.rules).isEqualTo(rules)
        assertThat(governed?.deploymentOf("alpha")).isEqualTo(deployment("alpha"))
    }

    private val workload =
        WorkloadDeploymentIdentity(
            WorkloadId("workload"),
            WorkloadConfigurationIdentity(ConfigurationId("config"), ConfigurationVersion("1")),
            EnvironmentId("env"),
            DeploymentId("deployment"),
        )

    private fun deployment(providerId: String) =
        ProviderDeployment(
            "dep-$providerId",
            providerId,
            NamedTrustZone(TrustZoneName("zone-$providerId"), ProviderTrustZone.LOCAL),
        )
}
