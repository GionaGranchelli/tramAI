package dev.tramai.core.identity

/**
 * Logical governance environment of a deployment.
 *
 * Examples: `dev`, `staging`, `production`, or a tenant-specific environment.
 * An environment is NOT a deployment: two independent deployments of the same
 * workload/configuration can both legitimately live in `production` and must
 * remain distinguishable through [DeploymentId].
 */
data class EnvironmentId(
    val value: String,
) {
    init {
        validateIdentity("EnvironmentId", value)
    }

    override fun toString(): String = value
}
