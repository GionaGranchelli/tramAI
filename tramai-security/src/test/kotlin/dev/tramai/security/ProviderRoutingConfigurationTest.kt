package dev.tramai.security

import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.security.governance.NamedTrustZone
import dev.tramai.security.governance.ProviderDeployment
import dev.tramai.security.governance.TrustZoneName
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/**
 * The topology facts 0.7.3 authorizes from: which workload deployment sits in which zone, and the
 * authoritative deployment of each registered provider.
 */
class ProviderRoutingConfigurationTest {
    @Test
    fun `a deployment cannot be registered under another provider`() {
        val deployment = deployment("alpha", ProviderTrustZone.LOCAL, "dep-alpha")

        val thrown =
            catchThrowable {
                ProviderRoutingConfiguration(providerDeployments = mapOf("beta" to deployment))
            }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown.message).contains("must equal the deployment's own providerId")
    }

    @Test
    fun `two deployments of one brand are distinct entries with distinct zones`() {
        val configuration =
            ProviderRoutingConfiguration(
                providerDeployments =
                    mapOf(
                        "brand-eu" to deployment("brand-eu", ProviderTrustZone.EU_CLOUD, "dep-eu"),
                        "brand-global" to deployment("brand-global", ProviderTrustZone.GLOBAL_CLOUD, "dep-global"),
                    ),
            )

        val zones = configuration.providerDeployments.values.map { it.trustZone.category }
        assertThat(zones).containsExactlyInAnyOrder(ProviderTrustZone.EU_CLOUD, ProviderTrustZone.GLOBAL_CLOUD)
        assertThat(configuration.providerDeployments.values.map { it.deploymentId })
            .containsExactlyInAnyOrder("dep-eu", "dep-global")
    }

    @Test
    fun `the workload zone is keyed by the exact deployment identity`() {
        val first = identity("deployment-a")
        val second = identity("deployment-b")
        val configuration =
            ProviderRoutingConfiguration(
                workloadZones = mapOf(first to ProviderTrustZone.LOCAL, second to ProviderTrustZone.EU_CLOUD),
            )

        assertThat(configuration.workloadZones[first]).isEqualTo(ProviderTrustZone.LOCAL)
        assertThat(configuration.workloadZones[second]).isEqualTo(ProviderTrustZone.EU_CLOUD)
        // A different deployment of the same workload does not inherit the other's zone.
        assertThat(configuration.workloadZones[identity("deployment-c")]).isNull()
    }

    private fun deployment(
        providerId: String,
        zone: ProviderTrustZone,
        deploymentId: String,
    ) = ProviderDeployment(
        deploymentId,
        providerId,
        NamedTrustZone(TrustZoneName("zone-$deploymentId"), zone),
    )

    private fun identity(deploymentId: String) =
        WorkloadDeploymentIdentity(
            WorkloadId("workload"),
            WorkloadConfigurationIdentity(ConfigurationId("config"), ConfigurationVersion("1")),
            EnvironmentId("env"),
            DeploymentId(deploymentId),
        )
}
