package dev.tramai.controlplane

import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadDeploymentIdentity
import dev.tramai.core.identity.WorkloadId
import dev.tramai.core.identity.WorkloadMetadata

/**
 * Framework-neutral command authority for workload registration state (0.7.1e).
 *
 * This port exposes the semantic shape of [WorkloadRegistrationAuthority] — it is
 * implemented BY that authority, not wrapped by a forwarding layer, so no
 * lifecycle rule, fingerprint check or compare-and-set exists in two places.
 *
 * Every state-dependent mutation requires the [WorkloadStateVersion] the caller
 * observed. That is a structural guarantee, not a convention:
 *
 * - the port has no operation that stores a record unconditionally, so
 *   "read, modify, write" is not expressible here;
 * - an expected version is a bare version, never a record, so a value obtained
 *   from any read (authoritative or projection) cannot be replayed as a mutation
 *   witness;
 * - stale expectations are reported as the typed
 *   [MetadataUpdateOutcome.Stale] / [LifecycleTransitionOutcome.Stale] carrying
 *   the CURRENT authoritative version, never as a false/null and never as a
 *   generic persistence failure.
 *
 * HTTP mechanics — `ETag`/`If-Match` parsing, status codes, problem DTOs — are
 * deliberately NOT part of this contract: they belong to the adapter that
 * consumes this port.
 */
interface WorkloadControlPlaneCommands {
    /**
     * Registers a workload deployment as ACTIVE at state version 1.
     *
     * Idempotent for an identical declaration; a conflicting declaration is
     * rejected, never silently applied.
     */
    suspend fun register(
        identity: WorkloadDeploymentIdentity,
        configurationFingerprint: ConfigurationFingerprint,
        metadata: WorkloadMetadata,
    ): RegisterOutcome

    /**
     * Updates owner/purpose metadata. Identity is unchanged; a successful change
     * advances the state version exactly once.
     *
     * @param expectedVersion version observed by the caller — a mutation based on
     *   a stale version fails with [MetadataUpdateOutcome.Stale] carrying the
     *   current authoritative version.
     */
    suspend fun updateMetadata(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
        expectedVersion: WorkloadStateVersion,
        metadata: WorkloadMetadata,
    ): MetadataUpdateOutcome

    /**
     * Applies an authoritative lifecycle transition. RETIRED is terminal;
     * suspending a registration never touches running workflows.
     *
     * @param expectedVersion version observed by the caller — a mutation based on
     *   a stale version fails with [LifecycleTransitionOutcome.Stale]; a current
     *   version with an illegal target fails with
     *   [LifecycleTransitionOutcome.InvalidTransition]. The two are distinct
     *   outcomes on purpose.
     */
    suspend fun transitionLifecycle(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
        expectedVersion: WorkloadStateVersion,
        target: WorkloadLifecycleState,
    ): LifecycleTransitionOutcome
}
