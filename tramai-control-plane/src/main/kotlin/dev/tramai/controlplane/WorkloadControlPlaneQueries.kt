package dev.tramai.controlplane

import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadId

/**
 * How a read relates to the authoritative state (0.7.1e).
 *
 * Read paths are separated from command paths structurally: [WorkloadControlPlaneQueries]
 * exposes nothing that mutates, and this classification is what a caller must use to
 * reason about freshness — instead of an unqualified "eventually consistent: true".
 */
enum class QueryConsistency {
    /** Read directly from the authority; current according to that authority. */
    AUTHORITATIVE,

    /**
     * Read from derived state that may lag. Always reports the authoritative version it
     * observed, so a caller can compare it with the authoritative read rather than guess.
     */
    PROJECTION,
}

/**
 * A read result labelled with the consistency class that produced it and the
 * authoritative [WorkloadStateVersion] it reflects.
 *
 * Immutable by construction. It is NOT accepted anywhere as a mutation input: commands take
 * `(identity fields, expectedVersion, payload)`, so neither this wrapper nor the
 * [registration] inside it can be replayed as a mutation witness.
 */
data class ClassifiedRead(
    val registration: RegisteredWorkload,
    val consistency: QueryConsistency,
    val observedVersion: WorkloadStateVersion,
)

/**
 * Read authority for workload registration state (0.7.1e).
 *
 * A read-only port: there is no operation here that can change state, so a query path cannot
 * become a mutation authority. Implemented by [WorkloadRegistrationAuthority] over the same
 * authority as [WorkloadControlPlaneCommands] — no second copy of the state.
 *
 * Returns null when the deployment scope has no registration. Absence is not an error on the
 * read path; on the command path it is a typed `NotFound` outcome.
 */
interface WorkloadControlPlaneQueries {
    /**
     * Reads current authoritative state. The result carries
     * [QueryConsistency.AUTHORITATIVE] and an [ClassifiedRead.observedVersion] equal to the
     * registration's own state version.
     */
    suspend fun authoritative(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): ClassifiedRead?

    /**
     * Reads a projection snapshot: read-only derived state that may lag the authoritative
     * version because it reflects the moment it was observed, not the authority's present.
     *
     * The result carries [QueryConsistency.PROJECTION] and the version it observed. A
     * lagging observation never authorizes a command — the authority still rejects a command
     * conditioned on it as [MetadataUpdateOutcome.Stale] / [LifecycleTransitionOutcome.Stale].
     *
     * ponytail: 0.7.1e ships the classification and the read-only guarantee, not a projection
     * engine. There is no event bus, no materialised store and no refresh policy; a real
     * projection source arrives with the slices that need one (0.7.4 evidence, 0.7.8 dashboard).
     */
    suspend fun projection(
        workloadId: WorkloadId,
        environmentId: EnvironmentId,
        deploymentId: DeploymentId,
    ): ClassifiedRead?
}
