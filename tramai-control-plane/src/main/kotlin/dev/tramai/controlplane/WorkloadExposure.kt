package dev.tramai.controlplane

import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadMetadata

/**
 * The complete set of registration facts a generic control-plane surface may expose.
 *
 * Four categories bound this boundary:
 *
 * 1. SAFE TO READ — [identity], [metadata], [lifecycle], [stateVersion]. These are the allowlist.
 * 2. DECLARATION INPUT, NOT READABLE — [ConfigurationFingerprint]. A client legitimately *originates*
 *    it when it declares a registration (`register(...)`, and the HTTP registration body), so it is
 *    neither secret material nor a mutation credential. What it must never be is *reflected back*: it
 *    is the witness [WorkloadRegistrationStore.compareAndSet] compares to decide mutation authority
 *    and the value that makes a `(configurationId, version)` binding unrebindable, so it is absent
 *    from every read model and from every command *outcome*.
 * 3. INTERNAL AUTHORITY / STORAGE — [RegisteredWorkload] (which necessarily carries the witness),
 *    `CreateResult` and persistence records. Reachable through [WorkloadRegistrationStore], never
 *    through a generic control-plane port.
 * 4. PROTECTED PAYLOAD — prompts, model input/output, user content, tool arguments/results,
 *    credentials, PII, approval/suspension/replay payloads, evidence bodies and provider traffic.
 *    Not representable here at all.
 *
 * This type carries category 1 only. No `Map`, `Any` or `JsonNode` property may ever be added: adding
 * a property is a deliberate exposure decision that must update the exact-shape tests, which fail
 * closed otherwise.
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
