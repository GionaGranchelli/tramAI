package dev.tramai.controlplane

import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadMetadata

/**
 * One authoritative registration record.
 *
 * Immutable portion:
 * - [identity] — workload/configuration/environment/deployment identity;
 * - [configurationFingerprint] — witness binding [identity.configuration] to
 *   exactly one governed configuration.
 *
 * Mutable authoritative portion (mutated only through
 * [WorkloadRegistrationAuthority], never through registration):
 * - [metadata] — owner/purpose, outside identity equality;
 * - [lifecycle] — registration lifecycle state;
 * - [stateVersion] — monotonic version advanced by every successful mutation.
 *
 * Invariant: [stateVersion] is not part of any identity type; a state update
 * never changes the identity of historical runs.
 */
data class RegisteredWorkload(
    val identity: WorkloadDeploymentIdentity,
    val configurationFingerprint: ConfigurationFingerprint,
    val metadata: WorkloadMetadata,
    val lifecycle: WorkloadLifecycleState,
    val stateVersion: WorkloadStateVersion,
)
