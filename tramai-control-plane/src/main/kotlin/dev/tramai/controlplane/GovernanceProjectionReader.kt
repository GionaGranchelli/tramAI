package dev.tramai.controlplane

import dev.tramai.core.identity.WorkloadDeploymentIdentity

/**
 * 0.7.4c1 — the bounded read seam over the existing control-plane read port.
 *
 * Read-only by construction: the constructor takes a [WorkloadControlPlaneQueries] read port and a
 * read-only fact source, so this projection has no command port to mutate registration, lifecycle,
 * policy or approvals, and no provider or tool to invoke. It cannot authorize anything: a command
 * still goes to [WorkloadControlPlaneCommands] and is decided by the authority, not by projected
 * state.
 *
 * [GovernanceDecisionFactSource] supplies *records*. It does not supply authority: the reader takes
 * authority from [GovernanceFactClassification], so a source cannot promote itself, and telemetry
 * is read through a separate method so it cannot be folded into a decision projection at all.
 */
internal interface GovernanceDecisionFactSource {
    /** Decision records for a run. Ordered by [RecordedDecision.sourceSequence] when the source has one. */
    suspend fun decisionsFor(workflowRunId: String): List<RecordedDecision>

    /** Non-authoritative observations for a run, held apart from decisions by design. */
    suspend fun telemetryFor(workflowRunId: String): List<TelemetryObservation>
}

/**
 * Builds the authority-aware projection for one governed run in one scope.
 *
 * ponytail: C1 ships the seam and its contract over the existing read port, not a projection
 * engine: no materialised store, no refresh policy, no dashboard API. Wiring the decision source to
 * a real reader (audit store / evidence bundle loader) is the next slice, named in the task record.
 */
internal class GovernanceProjectionReader(
    private val queries: WorkloadControlPlaneQueries,
    private val facts: GovernanceDecisionFactSource,
) {
    suspend fun read(
        scope: WorkloadDeploymentIdentity,
        declaredRunIdentity: DeclaredRunIdentity?,
        workflowRunId: String?,
    ): GovernanceProjection =
        GovernanceProjection(
            registration = registration(scope),
            declaredRunIdentity = declaredIdentity(declaredRunIdentity),
            decisions = decisions(workflowRunId),
            telemetry = telemetry(workflowRunId),
        )

    private suspend fun registration(scope: WorkloadDeploymentIdentity): ProjectedFact<WorkloadExposure> {
        val read =
            queries.authoritative(scope.workloadId, scope.environmentId, scope.deploymentId)
                ?: return ProjectedFact(
                    value = null,
                    authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                    freshness = FactFreshness.ABSENT,
                    established = "the registration authority holds no registration for this scope",
                    mustNotInfer =
                        listOf(
                            "absence of registration is not evidence that a run is ungoverned",
                            "enforcement is decided at the declared boundary, not by this read",
                        ),
                )
        val watermark = read.observedVersion.value.takeIf { read.consistency == QueryConsistency.PROJECTION }
        return ProjectedFact(
            value = read.exposure,
            authority = FactAuthority.AUTHORITATIVE_PERSISTED,
            freshness = FactFreshness.from(read.consistency, watermark),
            established = "registration state for this scope, at the version this read observed",
            mustNotInfer =
                listOf(
                    "registration is not a statement about any run's decisions",
                    "registration is not evidence that a run executed or completed",
                ),
            observedVersion = read.observedVersion,
            watermark = watermark,
        )
    }

    private fun declaredIdentity(declared: DeclaredRunIdentity?): ProjectedFact<DeclaredRunIdentity> =
        if (declared == null) {
            ProjectedFact(
                value = null,
                authority = FactAuthority.NOT_PRODUCED,
                freshness = FactFreshness.ABSENT,
                established = "no declared run identity accompanied this read",
                mustNotInfer = listOf("a missing declaration is not a negative governance fact"),
            )
        } else {
            ProjectedFact(
                value = declared,
                authority = FactAuthority.PRODUCER_DECLARED,
                freshness = FactFreshness.STALE_OR_UNKNOWN,
                established = "a producer declared this identity for the run",
                mustNotInfer = GovernanceFactClassification.declaredIdentityLimits,
            )
        }

    private suspend fun decisions(workflowRunId: String?): List<DecisionProjection> {
        if (workflowRunId == null) {
            return DecisionKind.entries.map { kind ->
                DecisionProjection(kind, GovernanceDecisionFold.noRecords(kind), Completeness.UNKNOWN)
            }
        }
        val records = facts.decisionsFor(workflowRunId)
        return DecisionKind.entries.map { kind ->
            GovernanceDecisionFold.project(kind, records.filter { it.kind == kind })
        }
    }

    private suspend fun telemetry(workflowRunId: String?): ProjectedFact<List<TelemetryObservation>> {
        if (workflowRunId == null) {
            return ProjectedFact(
                value = null,
                authority = FactAuthority.NOT_PRODUCED,
                freshness = FactFreshness.ABSENT,
                established = "no run was named, so no telemetry was read",
            )
        }
        val observations = facts.telemetryFor(workflowRunId)
        val watermark = observations.mapNotNull { it.sourceSequence }.maxOrNull()
        return ProjectedFact(
            value = observations,
            authority = FactAuthority.TELEMETRY,
            freshness = FactFreshness.from(QueryConsistency.PROJECTION, watermark),
            established = "telemetry observations reported for this run",
            mustNotInfer = GovernanceFactClassification.telemetryLimits,
            watermark = watermark,
        )
    }
}
