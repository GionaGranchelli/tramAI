package dev.tramai.core.identity

import org.assertj.core.api.Assertions.assertThat
import kotlin.test.Test

/**
 * Composition and discrimination semantics of the identity vocabulary.
 *
 * The critical adversarial proof: two deployments of the same workload and
 * configuration in the SAME environment must not collapse into one identity.
 */
class WorkloadIdentityCompositionTest {
    private fun deployment(
        workload: String = "claims",
        configId: String = "claims-prod",
        configVersion: String = "17",
        environment: String = "production",
        deployment: String,
    ): WorkloadDeploymentIdentity =
        WorkloadDeploymentIdentity(
            workloadId = WorkloadId(workload),
            configuration =
                WorkloadConfigurationIdentity(
                    id = ConfigurationId(configId),
                    version = ConfigurationVersion(configVersion),
                ),
            environmentId = EnvironmentId(environment),
            deploymentId = DeploymentId(deployment),
        )

    @Test
    fun `deployments in the same environment do not collapse`() {
        val amsterdam = deployment(deployment = "eu-west-amsterdam-01")
        val frankfurt = deployment(deployment = "eu-central-frankfurt-01")

        assertThat(amsterdam).isNotEqualTo(frankfurt)
    }

    @Test
    fun `deployments in different environments do not collapse`() {
        val production = deployment(environment = "production", deployment = "ams-01")
        val staging = deployment(environment = "staging", deployment = "ams-01")

        assertThat(production).isNotEqualTo(staging)
    }

    @Test
    fun `different workloads do not collapse`() {
        val claims = deployment(workload = "claims", deployment = "ams-01")
        val fraud = deployment(workload = "fraud-review", deployment = "ams-01")

        assertThat(claims).isNotEqualTo(fraud)
    }

    @Test
    fun `configuration version discriminates`() {
        val v17 = deployment(configVersion = "17", deployment = "ams-01")
        val v18 = deployment(configVersion = "18", deployment = "ams-01")

        assertThat(v17).isNotEqualTo(v18)
    }

    @Test
    fun `configuration id discriminates`() {
        val prod = deployment(configId = "claims-prod", deployment = "ams-01")
        val canary = deployment(configId = "claims-canary", deployment = "ams-01")

        assertThat(prod).isNotEqualTo(canary)
    }

    @Test
    fun `identical fields produce one identity`() {
        val a = deployment(deployment = "ams-01")
        val b = deployment(deployment = "ams-01")

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `run identity discriminates runs within one deployment`() {
        val deployment = deployment(deployment = "ams-01")
        val runA = GovernedRunIdentity(deployment = deployment, runId = RunId("run-a"))
        val runB = GovernedRunIdentity(deployment = deployment, runId = RunId("run-b"))
        val sameAsA = GovernedRunIdentity(deployment = deployment, runId = RunId("run-a"))

        assertThat(runA).isNotEqualTo(runB)
        assertThat(runA).isEqualTo(sameAsA)
    }

    @Test
    fun `run identity carries the deployment identity unchanged`() {
        val deployment = deployment(deployment = "ams-01")
        val run = GovernedRunIdentity(deployment = deployment, runId = RunId("run-a"))

        assertThat(run.deployment).isEqualTo(deployment)
        assertThat(run.runId.value).isEqualTo("run-a")
    }
}
