package dev.tramai.core.identity

/**
 * Identity of one governed workload/configuration as deployed in one place:
 * which workload, which exact configuration, and where it runs.
 *
 * Distinct deployments must never collapse into one identity. Two deployments
 * that share workload, configuration and environment but differ in
 * [deploymentId] (e.g. `eu-west-amsterdam-01` and `eu-central-frankfurt-01`)
 * are different identities. Owner/purpose metadata is deliberately NOT part of
 * this type: ownership may change without changing workload identity. The
 * authoritative association of metadata to identity belongs to the
 * registration/state authority (Epic 0.7.1 candidate 0.7.1c).
 */
data class WorkloadDeploymentIdentity(
    val workloadId: WorkloadId,
    val configuration: WorkloadConfigurationIdentity,
    val environmentId: EnvironmentId,
    val deploymentId: DeploymentId,
)
