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
 * Immutable by construction, and never accepted as a mutation input AS A RECORD: commands take
 * `(identity fields, expectedVersion, payload)`, so a read result cannot be handed over as a
 * mutation witness.
 *
 * Its [observedVersion] may legitimately be submitted as an explicit expected version like any other
 * version. That is NOT the read becoming authoritative: the authority re-reads and answers
 * `Stale(current, expected)` unless its current version still equals what was submitted. Projection
 * lag is defeated by the compare-and-set, not by the type system — so there is deliberately no
 * separate "version token" type here.
 */
data class ClassifiedRead(
    val registration: RegisteredWorkload,
    val consistency: QueryConsistency,
    val observedVersion: WorkloadStateVersion,
) {
    init {
        // A read must not contradict itself: the version it reports is the version of the state it
        // carries. A future projection implementation that observed one version and shipped another
        // fails here instead of returning contradictory state/version evidence.
        require(registration.stateVersion == observedVersion) {
            "read state/version mismatch: registration.stateVersion=" +
                "${registration.stateVersion.value} but observedVersion=${observedVersion.value}"
        }
    }
}

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
