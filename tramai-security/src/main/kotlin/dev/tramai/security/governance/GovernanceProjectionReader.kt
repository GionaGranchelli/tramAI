package dev.tramai.security.governance

import dev.tramai.controlplane.QueryConsistency
import dev.tramai.controlplane.WorkloadControlPlaneQueries
import dev.tramai.controlplane.WorkloadExposure
import dev.tramai.core.identity.WorkloadDeploymentIdentity

/**
 * 0.7.4c1 — the bounded read seam over the existing control-plane read port.
 *
 * Read-only by construction: the constructor takes a [WorkloadControlPlaneQueries] read port and a
 * [GovernanceDecisionFactSource], and nothing else. There is no command port, no outcome type and no
 * effectful collaborator reachable from here, which is asserted by a discriminator rather than
 * trusted; the projection therefore cannot authorize, approve, select a provider, mutate
 * registration or change enforcement strength, because it has nothing to do it with.
 *
 * Registration facts come from the authority that owns them. Decision facts come from a read-only
 * source, and their authority is assigned by this projection's classification rather than taken from
 * whatever the source claims. Telemetry is kept in its own channel so it cannot be folded into a
 * decision fact.
 */
internal interface GovernanceDecisionFactSource {
    /** The decision records for a run, with the verified stream coverage the read established. */
    suspend fun decisionsFor(workflowRunId: String): DecisionFactRead

    /** Non-authoritative observations for a run. Never produced by a decision read. */
    suspend fun telemetryFor(workflowRunId: String): List<TelemetryObservation>
}

internal class GovernanceProjectionReader(
    private val queries: WorkloadControlPlaneQueries,
    private val facts: GovernanceDecisionFactSource,
) {
    /**
     * Projects the governance facts available for [scope], optionally for one run.
     *
     * Everything reported is traceable to a source, and no claim is stronger than that source:
     * registration keeps its own version and consistency, a declared identity stays declared, and a
     * decision family reports what its source returned (or that nothing was observed) without
     * asserting what the runtime did.
     */
    suspend fun read(
        scope: WorkloadDeploymentIdentity,
        declaredRunIdentity: DeclaredRunIdentity?,
        workflowRunId: String?,
    ): GovernanceProjection {
        val read = decisionRead(workflowRunId)
        val records = read?.records.orEmpty()
        val coverage = read?.coverage ?: StreamCoverage()
        return GovernanceProjection(
            registration = registrationFact(scope),
            declaredRunIdentity = declaredRunIdentityFact(declaredRunIdentity),
            decisions = decisions(records, coverage),
            telemetry = telemetryFact(workflowRunId),
        )
    }

    /**
     * The source's decision read, refused unless it is about the run that was asked for.
     *
     * A record carrying another run's identifier would otherwise be projected as this run's fact, so
     * the mismatch is a source defect rather than a coverage gap. Dropping such records silently
     * would be worse than refusing: the projection could then claim completeness over data it
     * quietly discarded. Which run the read is about is not authentication — it is consistency
     * between the request and what came back.
     */
    private suspend fun decisionRead(workflowRunId: String?): DecisionFactRead? {
        if (workflowRunId == null) {
            return null
        }
        val read = facts.decisionsFor(workflowRunId)
        require(read.records.all { it.workflowRunId == workflowRunId }) {
            "the decision source returned records for another run: refusing to project them"
        }
        return read
    }

    /**
     * The owning authority's answer for this scope, at its own version.
     *
     * An authoritative null is reported as the authority's answer, not as "no governance": a scope
     * with no registration is not a statement that a run was ungoverned, because enforcement is
     * decided at the boundary and this read has no part in it.
     */
    private suspend fun registrationFact(scope: WorkloadDeploymentIdentity): ProjectedFact<WorkloadExposure> {
        val read = queries.authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)
        if (read == null) {
            return ProjectedFact(
                value = null,
                authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                freshness = FactFreshness.ABSENT,
                established = "the registration authority holds no registration for this scope",
                mustNotInfer =
                    listOf(
                        "absence of registration is not evidence that a run is ungoverned",
                        "enforcement is decided at the boundary, not by this read",
                    ),
            )
        }
        val authoritative = read.consistency == QueryConsistency.AUTHORITATIVE
        val watermark = if (authoritative) null else read.observedVersion.value
        return ProjectedFact(
            value = read.exposure,
            authority = FactAuthority.AUTHORITATIVE_PERSISTED,
            freshness = GovernanceFactClassification.freshnessFor(read.consistency, watermark),
            established =
                "the registration authority's state for this scope at version ${read.observedVersion.value}",
            mustNotInfer =
                listOf(
                    "registration is not a statement about any run's decisions",
                    "registration is not evidence that a run executed or completed",
                ),
            observedVersion = read.observedVersion,
            watermark = watermark,
        )
    }

    /**
     * A producer-declared identity: reported, with its lack of authentication stated.
     *
     * An absent declaration is [FactAuthority.NOT_PRODUCED] and not [FactAuthority.NOT_OBSERVED]:
     * nothing produced a declaration for this read, which is a different statement from a source read
     * that covered a range and found nothing in it.
     */
    private fun declaredRunIdentityFact(declared: DeclaredRunIdentity?): ProjectedFact<DeclaredRunIdentity> {
        if (declared == null) {
            return ProjectedFact(
                value = null,
                authority = FactAuthority.NOT_PRODUCED,
                freshness = FactFreshness.ABSENT,
                established = "no declared run identity accompanied this read",
                mustNotInfer =
                    listOf(
                        "a missing declaration is not a negative governance fact",
                        "this is not evidence that the run was ungoverned",
                    ),
            )
        }
        return ProjectedFact(
            value = declared,
            authority = FactAuthority.PRODUCER_DECLARED,
            freshness = FactFreshness.STALE_OR_UNKNOWN,
            established = "a producer declared this identity for the run; the tuple is not authenticated",
            mustNotInfer = GovernanceFactClassification.DECLARED_IDENTITY_LIMITS,
        )
    }

    private fun decisions(
        records: List<RecordedDecision>,
        coverage: StreamCoverage,
    ): List<DecisionProjection> {
        val completeness = GovernanceFactClassification.completenessOf(records, coverage)
        val sourceRead = coverage.verifiedRange != null
        return DecisionKind.entries.map { kind ->
            val forKind = records.filter { it.kind == kind }
            if (!sourceRead && forKind.isEmpty()) {
                GovernanceDecisionFold.absent(kind)
            } else {
                GovernanceDecisionFold.project(kind, forKind, coverage.verifiedRange, completeness)
            }
        }
    }

    /**
     * Telemetry in its own channel, so it cannot become decision evidence.
     *
     * A lag is only reported with the watermark that establishes it; otherwise freshness is
     * reported as unestablished rather than as zero lag.
     */
    private suspend fun telemetryFact(workflowRunId: String?): ProjectedFact<List<TelemetryObservation>> {
        if (workflowRunId == null) {
            return ProjectedFact(
                value = null,
                authority = FactAuthority.NOT_OBSERVED,
                freshness = FactFreshness.ABSENT,
                established = "no run was named, so no telemetry was read",
            )
        }
        val observations = facts.telemetryFor(workflowRunId)
        val watermark = observations.mapNotNull { it.sourceSequence }.maxOrNull()
        return ProjectedFact(
            value = observations,
            authority = FactAuthority.TELEMETRY,
            freshness =
                if (watermark == null) {
                    FactFreshness.STALE_OR_UNKNOWN
                } else {
                    FactFreshness.EVENTUAL_KNOWN_LAG
                },
            established = "${observations.size} telemetry observation(s) for this run",
            mustNotInfer = GovernanceFactClassification.TELEMETRY_LIMITS,
            watermark = watermark,
        )
    }
}
