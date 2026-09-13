package dev.tramai.core.identity

/**
 * Identity of one independently distinguishable deployment of a
 * workload/configuration in an environment.
 *
 * Distinct deployments must never collapse into one identity, even when they
 * share the same workload, configuration and environment. Example: two
 * clusters running the same production configuration
 * (`eu-west-amsterdam-01` vs `eu-central-frankfurt-01`) are different
 * [WorkloadDeploymentIdentity] values.
 */
data class DeploymentId(
    val value: String,
) {
    init {
        validateIdentity("DeploymentId", value)
    }

    override fun toString(): String = value
}
