package dev.tramai.controlplane.testing

import dev.tramai.controlplane.ConfigurationFingerprint
import dev.tramai.controlplane.RegisteredWorkload
import dev.tramai.controlplane.WorkloadLifecycleState
import dev.tramai.controlplane.WorkloadStateVersion
import dev.tramai.core.identity.ConfigurationId
import dev.tramai.core.identity.ConfigurationVersion
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadConfigurationIdentity
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.identity.WorkloadMetadata

/**
 * Deterministic fixtures for the workload-registration store contract.
 * The TCK owns the identifiers — runners never do.
 */
object WorkloadRegistrationFixtures {
    fun identity(
        workload: String = "claims",
        configurationId: String = "claims-prod",
        configurationVersion: String = "17",
        environment: String = "production",
        deployment: String = "eu-west-amsterdam-01",
    ): WorkloadDeploymentIdentity =
        WorkloadDeploymentIdentity(
            workloadId = WorkloadId(workload),
            configuration =
                WorkloadConfigurationIdentity(
                    id = ConfigurationId(configurationId),
                    version = ConfigurationVersion(configurationVersion),
                ),
            environmentId = EnvironmentId(environment),
            deploymentId = DeploymentId(deployment),
        )

    fun fingerprint(value: String = "sha256:aaaa"): ConfigurationFingerprint = ConfigurationFingerprint(value)

    fun metadata(
        owner: String = "Claims Team",
        purpose: String = "Fraud review",
    ): WorkloadMetadata = WorkloadMetadata(owner, purpose)

    fun registration(
        identity: WorkloadDeploymentIdentity = identity(),
        configurationFingerprint: ConfigurationFingerprint = fingerprint(),
        metadata: WorkloadMetadata = metadata(),
        lifecycle: WorkloadLifecycleState = WorkloadLifecycleState.ACTIVE,
        stateVersion: WorkloadStateVersion = WorkloadStateVersion.INITIAL,
    ): RegisteredWorkload =
        RegisteredWorkload(
            identity = identity,
            configurationFingerprint = configurationFingerprint,
            metadata = metadata,
            lifecycle = lifecycle,
            stateVersion = stateVersion,
        )
}
