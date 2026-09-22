package dev.tramai.controlplane

import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadMetadata

/**
 * The complete set of registration facts a generic control-plane surface may expose.
 *
 * Safe facts are [identity], [metadata], [lifecycle], and [stateVersion]. Authority-only facts
 * include [ConfigurationFingerprint], because it is the witness that
 * [WorkloadRegistrationStore.compareAndSet] compares to decide mutation authority and the value
 * that makes a `(configurationId, version)` binding unrebindable. A generic client cannot present
 * it back and must not be invited to treat it as a credential. Protected facts include prompts,
 * model input/output, user content, tool arguments/results, credentials, PII,
 * approval/suspension/replay payloads, evidence bodies, provider traffic, and storage records.
 *
 * This type is not a carrier for any of those protected categories. No `Map`, `Any`, or `JsonNode`
 * property may ever be added here. Adding a property is a deliberate exposure decision that must
 * update the exact-shape tests; they fail closed otherwise.
 */
data class WorkloadExposure(
    val identity: WorkloadDeploymentIdentity,
    val metadata: WorkloadMetadata,
    val lifecycle: WorkloadLifecycleState,
    val stateVersion: WorkloadStateVersion,
) {
    companion object {
        /** The explicit allowlist mapper: the ONLY path from the authority record to an exposure. */
        fun from(registration: RegisteredWorkload): WorkloadExposure =
            WorkloadExposure(
                identity = registration.identity,
                metadata = registration.metadata,
                lifecycle = registration.lifecycle,
                stateVersion = registration.stateVersion,
            )
    }
}
