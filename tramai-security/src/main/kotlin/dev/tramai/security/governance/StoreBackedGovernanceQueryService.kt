package dev.tramai.security.governance

import dev.tramai.controlplane.QueryConsistency
import dev.tramai.controlplane.WorkloadControlPlaneQueries
import dev.tramai.core.identity.DeploymentId
import dev.tramai.core.identity.EnvironmentId
import dev.tramai.core.identity.WorkloadId
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.governance.query.DecisionRecordCoverage
import dev.tramai.security.governance.query.GovernanceCurrencyAssessment
import dev.tramai.security.governance.query.GovernanceDecisionFact
import dev.tramai.security.governance.query.GovernanceFactSummary
import dev.tramai.security.governance.query.GovernanceProjectionIdentity
import dev.tramai.security.governance.query.GovernanceProjectionView
import dev.tramai.security.governance.query.GovernanceQueryRequest
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.GovernanceQueryService
import dev.tramai.security.governance.query.GovernanceSourceProvenance
import dev.tramai.security.governance.query.RawStreamCoverage
import dev.tramai.security.governance.query.ReasonCodeExposure
import dev.tramai.security.governance.query.VerifiedStreamRange
import kotlinx.coroutines.runBlocking
import dev.tramai.security.governance.query.Completeness as PublicCompleteness
import dev.tramai.security.governance.query.DecisionKind as PublicDecisionKind
import dev.tramai.security.governance.query.FactAuthority as PublicFactAuthority
import dev.tramai.security.governance.query.FactFreshness as PublicFactFreshness
import dev.tramai.security.governance.query.ReadConsistency as PublicReadConsistency
import dev.tramai.security.governance.query.SourceCurrency as PublicSourceCurrency

/**
 * 0.7.4e — internal read-only adapter implementing [GovernanceQueryService] over
 * [GovernanceProjectionReadModel].
 *
 * Construction takes only read-only ports ([GovernanceProjectionReadModel] or `store.readOnly()`,
 * plus optional fresh-observation callbacks or read ports). No write, rebuild, delete or background
 * refresh path exists on or is reachable from this adapter.
 */
internal class StoreBackedGovernanceQueryService(
    private val readModel: GovernanceProjectionReadModel,
    private val observeRegistrationVersion: suspend (ProjectionIdentity) -> Long? = { null },
    private val observeAuditHead: suspend (ProjectionIdentity) -> ObservedAuditHead? = { null },
) : GovernanceQueryService {
    constructor(
        store: GovernanceProjectionStore,
        controlPlaneQueries: WorkloadControlPlaneQueries? = null,
        auditStore: AuditStore? = null,
        streamAssociation: RunStreamAssociation = RunStreamAssociation.DirectlyNamedByRunId,
    ) : this(
        readModel = store.readOnly(),
        observeRegistrationVersion = registrationObserver(controlPlaneQueries),
        observeAuditHead = auditHeadObserver(auditStore, streamAssociation),
    )

    constructor(
        readModel: GovernanceProjectionReadModel,
        controlPlaneQueries: WorkloadControlPlaneQueries?,
        auditStore: AuditStore? = null,
        streamAssociation: RunStreamAssociation = RunStreamAssociation.DirectlyNamedByRunId,
    ) : this(
        readModel = readModel,
        observeRegistrationVersion = registrationObserver(controlPlaneQueries),
        observeAuditHead = auditHeadObserver(auditStore, streamAssociation),
    )

    override fun query(request: GovernanceQueryRequest): GovernanceQueryResult =
        runBlocking {
            val targetIdentity =
                ProjectionIdentity(
                    workloadId = request.workloadId,
                    environmentId = request.environmentId,
                    deploymentId = request.deploymentId,
                    workflowRunId = request.workflowRunId,
                )
            try {
                val stored = readModel.load(targetIdentity)
                if (stored == null) {
                    GovernanceQueryResult.NotMaterialized
                } else if (!matchesRequestedScope(stored.identity, targetIdentity)) {
                    GovernanceQueryResult.InvalidStoredProjection(SCOPE_MISMATCH_REASON)
                } else {
                    val registrationVersion = observeRegistrationVersion(targetIdentity)
                    val auditHead = observeAuditHead(targetIdentity)
                    val freshness = stored.assessAgainst(auditHead, registrationVersion)
                    GovernanceQueryResult.Found(
                        GovernanceProjectionViewMapper.toView(stored, freshness),
                    )
                }
            } catch (
                @Suppress("SwallowedException") e: UnsupportedProjectionFormatException,
            ) {
                GovernanceQueryResult.UnsupportedFormat(UNSUPPORTED_FORMAT_REASON)
            } catch (
                @Suppress("SwallowedException") e: ProjectionCorruptionException,
            ) {
                GovernanceQueryResult.InvalidStoredProjection(INVALID_PROJECTION_REASON)
            }
        }

    private fun matchesRequestedScope(
        stored: ProjectionIdentity,
        requested: ProjectionIdentity,
    ): Boolean =
        stored.workloadId == requested.workloadId &&
            stored.environmentId == requested.environmentId &&
            stored.deploymentId == requested.deploymentId &&
            stored.workflowRunId == requested.workflowRunId &&
            stored.auditStreamId == requested.auditStreamId
}

private fun registrationObserver(queries: WorkloadControlPlaneQueries?): suspend (ProjectionIdentity) -> Long? {
    if (queries == null) return { null }
    return { identity ->
        val read =
            queries.authoritative(
                WorkloadId(identity.workloadId),
                EnvironmentId(identity.environmentId),
                DeploymentId(identity.deploymentId),
            )
        if (read != null && read.consistency == QueryConsistency.AUTHORITATIVE) {
            read.observedVersion.value
        } else {
            null
        }
    }
}

private fun auditHeadObserver(
    auditStore: AuditStore?,
    streamAssociation: RunStreamAssociation,
): suspend (ProjectionIdentity) -> ObservedAuditHead? {
    if (auditStore == null) return { null }
    return { identity ->
        val runId = identity.workflowRunId
        val streamId = if (runId == null) null else streamAssociation.streamIdFor(runId)
        val latest = if (streamId == null) null else auditStore.latestEvent(streamId)
        if (latest != null && latest.sequenceNumber >= FIRST_OBSERVED_POSITION) {
            ObservedAuditHead(
                position = latest.sequenceNumber,
                hash = latest.eventHash.takeIf { it.isNotBlank() },
            )
        } else {
            null
        }
    }
}

/** Pure mapping from internal [StoredGovernanceProjection] to public [GovernanceProjectionView]. */
private object GovernanceProjectionViewMapper {
    fun toView(
        stored: StoredGovernanceProjection,
        freshness: ProjectionFreshnessAssessment,
    ): GovernanceProjectionView {
        val snapshot = stored.snapshot
        return GovernanceProjectionView(
            identity =
                GovernanceProjectionIdentity(
                    workloadId = snapshot.identity.workloadId,
                    environmentId = snapshot.identity.environmentId,
                    deploymentId = snapshot.identity.deploymentId,
                    workflowRunId = snapshot.identity.workflowRunId,
                ),
            formatVersion = snapshot.formatVersion,
            materializedAt = stored.materializedAt,
            provenance = toProvenance(snapshot.provenance),
            currency =
                GovernanceCurrencyAssessment(
                    registration = toPublicCurrency(freshness.registration),
                    audit = toPublicCurrency(freshness.audit),
                    note = freshness.note,
                ),
            registration = toFactSummary(snapshot.registration),
            declaredRunIdentity = toFactSummary(snapshot.declaredRunIdentity),
            decisions =
                DecisionKind.entries
                    .filter { kind -> stored.decision(kind) != null }
                    .map { kind -> toDecisionFact(stored, kind) },
            telemetry = toFactSummary(snapshot.telemetry),
        )
    }

    private fun toProvenance(provenance: SourceProvenance): GovernanceSourceProvenance =
        GovernanceSourceProvenance(
            registrationVersion = provenance.registrationVersion,
            auditHeadPosition = provenance.auditHeadPosition,
            auditHeadHash = provenance.auditHeadHash,
            classificationRevision = provenance.classificationRevision,
            readConsistency =
                when (provenance.readConsistency) {
                    ReadConsistency.INDEPENDENT_SOURCES -> PublicReadConsistency.INDEPENDENT_SOURCES
                },
        )

    /**
     * The public-exposure policy for decision reason codes.
     *
     * Syntactic validation is not a disclosure policy. A stored value such as a provider key satisfies any
     * charset and length rule, so a shape test cannot separate an approved governance reason from a secret.
     * Only codes from the vocabulary this projection documents are exported. Every other stored value is
     * withheld, and the withholding is reported as such instead of being rendered as an absence, so a
     * consumer can never read a withheld value as "no reason was recorded".
     *
     * The vocabulary is deliberately conservative: a legitimate code that is missing from it is withheld
     * rather than leaked. Extending it is a deliberate, reviewable policy change.
     */
    internal object PublicReasonCodePolicy {
        private val approved =
            setOf(
                "classification-fallback-blocked",
                "classification-routing-blocked",
                "approval_pending",
                "approval_authorized",
                "approval_completed",
                "approval_stale_detected",
                "approval_force_cancel_requested",
                "approval_force_cancelled",
                "sovereign-ops-admin-denial",
            )

        fun exposureOf(stored: String?): Pair<String?, ReasonCodeExposure> =
            when {
                stored == null -> null to ReasonCodeExposure.NOT_RECORDED
                stored in approved -> stored to ReasonCodeExposure.EXPOSED
                else -> null to ReasonCodeExposure.WITHHELD
            }
    }

    private fun toDecisionFact(
        stored: StoredGovernanceProjection,
        kind: DecisionKind,
    ): GovernanceDecisionFact {
        // Reached only for a family the projection genuinely contains. A family it does not contain is
        // absent from the decision list rather than reported as a synthetic absence: the projection
        // records what its sources established, and a missing family is not a recorded observation that
        // nothing occurred. Confusing the two would make the API assert a source fact it never had.
        val decision =
            requireNotNull(stored.decision(kind)) {
                "toDecisionFact was called for a family this projection does not contain: $kind"
            }
        val effectiveCompleteness = stored.completenessFor(kind)
        val coverage = decision.coverage
        val range = coverage.verifiedRange
        val (reasonCode, reasonExposure) = PublicReasonCodePolicy.exposureOf(decision.latestReasonCode)
        return GovernanceDecisionFact(
            kind = toPublicDecisionKind(kind),
            fact = toFactSummary(decision.latest),
            rawCoverage =
                RawStreamCoverage(
                    verifiedRange =
                        if (range == null) {
                            null
                        } else {
                            VerifiedStreamRange(range.first, range.last)
                        },
                    observedHeadHash = coverage.observedHeadHash,
                ),
            recordCoverage =
                DecisionRecordCoverage(
                    completeness = toPublicCompleteness(effectiveCompleteness),
                    accountedPositions = coverage.accountedPositions,
                    unrecordedDecisionPositions = coverage.unrecordedDecisionPositions,
                    unclassifiedPositions = coverage.unclassifiedPositions,
                    classificationRevision = coverage.classificationRevision,
                ),
            decision = decision.latestDecision,
            reasonCode = reasonCode,
            reasonExposure = reasonExposure,
            boundaryId = decision.latestBoundaryId,
            boundaryStrength = decision.latestBoundaryStrength,
            duplicatesIgnored = decision.duplicatesIgnored,
            staleIgnored = decision.staleIgnored,
        )
    }

    private fun toFactSummary(fact: SnapshotFact): GovernanceFactSummary =
        GovernanceFactSummary(
            authority = toPublicAuthority(fact.authority),
            freshness = toPublicFreshness(fact.freshness),
            established = fact.established,
            mustNotInfer = fact.mustNotInfer,
            valuePresent = fact.valuePresent,
            observedVersion = fact.observedVersion,
            sourceVersion = fact.sourceVersion,
            watermark = fact.watermark,
        )

    private fun toPublicAuthority(authority: FactAuthority): PublicFactAuthority =
        when (authority) {
            FactAuthority.AUTHORITATIVE_PERSISTED -> PublicFactAuthority.AUTHORITATIVE_PERSISTED
            FactAuthority.POLICY_AUTHORIZATION_RECORD -> PublicFactAuthority.POLICY_AUTHORIZATION_RECORD
            FactAuthority.PRODUCER_DECLARED -> PublicFactAuthority.PRODUCER_DECLARED
            FactAuthority.TELEMETRY -> PublicFactAuthority.TELEMETRY
            FactAuthority.NOT_OBSERVED -> PublicFactAuthority.NOT_OBSERVED
            FactAuthority.NOT_PRODUCED -> PublicFactAuthority.NOT_PRODUCED
        }

    private fun toPublicFreshness(freshness: FactFreshness): PublicFactFreshness =
        when (freshness) {
            FactFreshness.CURRENT_AT_VERSION -> PublicFactFreshness.CURRENT_AT_VERSION
            FactFreshness.HISTORICAL -> PublicFactFreshness.HISTORICAL
            FactFreshness.EVENTUAL_KNOWN_LAG -> PublicFactFreshness.EVENTUAL_KNOWN_LAG
            FactFreshness.STALE_OR_UNKNOWN -> PublicFactFreshness.STALE_OR_UNKNOWN
            FactFreshness.ABSENT -> PublicFactFreshness.ABSENT
        }

    private fun toPublicDecisionKind(kind: DecisionKind): PublicDecisionKind =
        when (kind) {
            DecisionKind.POLICY_DECISION -> PublicDecisionKind.POLICY_DECISION
            DecisionKind.TOOL_PERMISSION -> PublicDecisionKind.TOOL_PERMISSION
            DecisionKind.PROVIDER_ROUTE -> PublicDecisionKind.PROVIDER_ROUTE
        }

    private fun toPublicCompleteness(completeness: Completeness): PublicCompleteness =
        when (completeness) {
            Completeness.COMPLETE -> PublicCompleteness.COMPLETE
            Completeness.GAPS_PRESENT -> PublicCompleteness.GAPS_PRESENT
            Completeness.UNKNOWN -> PublicCompleteness.UNKNOWN
        }

    private fun toPublicCurrency(currency: SourceCurrency): PublicSourceCurrency =
        when (currency) {
            SourceCurrency.CURRENT -> PublicSourceCurrency.CURRENT
            SourceCurrency.STALE -> PublicSourceCurrency.STALE
            SourceCurrency.UNKNOWN -> PublicSourceCurrency.UNKNOWN
        }
}

private const val UNSUPPORTED_FORMAT_REASON =
    "stored governance projection uses an unsupported format version and cannot be upgraded"

private const val INVALID_PROJECTION_REASON =
    "stored governance projection failed integrity or contract verification"

private const val SCOPE_MISMATCH_REASON =
    "stored governance projection identity does not match the requested scope"
