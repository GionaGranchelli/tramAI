package dev.tramai.security.governance

import dev.tramai.core.identity.WorkloadDeploymentIdentity

/**
 * 0.7.4d — the materialization boundary.
 *
 * Read, fold, validate, store. Nothing here evaluates a permission, decides an outcome or executes
 * anything: the materializer turns what the authoritative sources established into a persisted view of
 * it, and the governance engine keeps owning every decision.
 *
 * A rebuild is this same operation. There is no incremental path, because an incremental one would have
 * to assume events arrive in order, and that assumption is not enforced by the storage contract: the
 * publish ordering rejects regressions, it does not promise delivery order. Re-materializing the same
 * source facts therefore reproduces the same snapshot, and the store reports it as already current.
 */
internal class GovernanceProjectionMaterializer(
    private val reader: GovernanceProjectionReader,
    private val store: GovernanceProjectionStore,
) {
    /**
     * Reads the sources and publishes what they established.
     *
     * The recorded provenance comes from the read itself — the registration version the registration
     * authority reported, and the audit head the decision source anchored its range to. Nothing is
     * passed in that could disagree with what the sources said.
     */
    suspend fun materialize(
        identity: ProjectionIdentity,
        scope: WorkloadDeploymentIdentity,
        declaredRunIdentity: DeclaredRunIdentity? = null,
    ): MaterializationOutcome {
        val projection = reader.read(scope, declaredRunIdentity, identity.workflowRunId)
        return store.publish(
            GovernanceProjectionSnapshot.of(identity, projection, provenanceOf(projection)),
        )
    }

    /**
     * The provenance the read established, per source.
     *
     * Families that disagree about the head they were anchored to would mean one read produced two
     * audit claims; that is refused rather than reconciled, because a snapshot may not pick a winner
     * between two contradictory observations of the same stream.
     */
    private fun provenanceOf(projection: GovernanceProjection): SourceProvenance {
        val heads =
            projection.decisions
                .mapNotNull { decision ->
                    decision.coverage.verifiedRange?.let { range -> range.last to decision.coverage.observedHeadHash }
                }.distinct()
        require(heads.size <= 1) {
            "one read reported ${heads.size} different audit heads; a snapshot cannot reconcile them"
        }
        val revisions = projection.decisions.map { it.coverage.classificationRevision }.distinct()
        require(revisions.size <= 1) {
            "one read classified under ${revisions.size} different revisions; the snapshot cannot pick one"
        }
        return SourceProvenance(
            registrationVersion = projection.registration.observedVersion?.value ?: NOT_ESTABLISHED,
            auditHeadPosition = heads.singleOrNull()?.first ?: NOT_ESTABLISHED,
            auditHeadHash = heads.singleOrNull()?.second,
            classificationRevision = revisions.singleOrNull() ?: GOVERNANCE_CLASSIFICATION_REVISION,
        )
    }
}
