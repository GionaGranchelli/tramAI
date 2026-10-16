package dev.tramai.security.governance.query

import java.time.Instant

/*
 * 0.7.4e — public governance projection view, provenance, currency, and dual-coverage decision models.
 *
 * Every type here enforces the authority, freshness, and coverage invariants on construction, and
 * exposes only narrow, validated identifiers and classifications so no protected payload (prompts,
 * completions, tool arguments/results, credentials, arbitrary metadata, document bodies, or raw
 * provider/runtime error text) can appear in the query response.
 */

/**
 * The canonical scope and optional run identity a materialized governance projection describes.
 */
public data class GovernanceProjectionIdentity
    @JvmOverloads
    constructor(
        public val workloadId: String,
        public val environmentId: String,
        public val deploymentId: String,
        public val workflowRunId: String? = null,
    ) {
        init {
            requireSafeIdentifier(workloadId, "workloadId")
            requireSafeIdentifier(environmentId, "environmentId")
            requireSafeIdentifier(deploymentId, "deploymentId")
            if (workflowRunId != null) {
                requireSafeIdentifier(workflowRunId, "workflowRunId")
            }
        }
    }

/**
 * Per-source provenance recorded when the projection was materialized.
 *
 * A zero in [registrationVersion] or [auditHeadPosition] means the source was not established by the
 * materializing read (`0L`); valid versions and audit positions start at `1L`.
 */
public data class GovernanceSourceProvenance
    @JvmOverloads
    constructor(
        public val registrationVersion: Long,
        public val auditHeadPosition: Long,
        public val auditHeadHash: String?,
        public val classificationRevision: String,
        public val readConsistency: ReadConsistency = ReadConsistency.INDEPENDENT_SOURCES,
    ) {
        init {
            require(registrationVersion >= 0L) { "registrationVersion cannot be negative" }
            require(auditHeadPosition >= 0L) { "auditHeadPosition cannot be negative" }
            require(auditHeadHash == null || (auditHeadPosition >= FIRST_POSITION && auditHeadHash.isNotBlank())) {
                "an auditHeadHash requires a positive auditHeadPosition and non-blank hash"
            }
            require(classificationRevision.isNotBlank()) { "classificationRevision must not be blank" }
        }
    }

/**
 * Per-source currency of a materialized projection against fresh authoritative observations.
 *
 * Registration and audit currency are assessed independently — there is no global freshness boolean,
 * and a persisted row without a fresh comparable observation reports [SourceCurrency.UNKNOWN], never
 * [SourceCurrency.CURRENT].
 */
public data class GovernanceCurrencyAssessment(
    public val registration: SourceCurrency,
    public val audit: SourceCurrency,
    public val note: String,
) {
    init {
        require(note.isNotBlank()) { "a currency assessment must carry its explanatory note" }
    }
}

/**
 * A projected fact's authority, temporal classification, and explicit inference boundaries.
 *
 * [valuePresent] states whether the source reported a value for this fact; the raw payload itself is
 * never duplicated here.
 */
public data class GovernanceFactSummary
    @JvmOverloads
    constructor(
        public val authority: FactAuthority,
        public val freshness: FactFreshness,
        public val established: String,
        public val mustNotInfer: List<String>,
        public val valuePresent: Boolean,
        public val observedVersion: Long? = null,
        public val sourceVersion: Long? = null,
        public val watermark: Long? = null,
    ) {
        init {
            require(established.isNotBlank()) { "a projected fact must state what was established" }
            require(mustNotInfer.all { it.isNotBlank() }) { "mustNotInfer entries must not be blank" }
            require(valuePresent == (freshness != FactFreshness.ABSENT)) {
                "absence is exactly the case where no value is reported"
            }
            require(
                freshness != FactFreshness.ABSENT ||
                    authority == FactAuthority.NOT_PRODUCED ||
                    authority == FactAuthority.NOT_OBSERVED ||
                    authority == FactAuthority.AUTHORITATIVE_PERSISTED,
            ) {
                "absence may be reported only as NOT_PRODUCED, NOT_OBSERVED, or AUTHORITATIVE_PERSISTED"
            }
            require(authority != FactAuthority.NOT_PRODUCED || freshness == FactFreshness.ABSENT) {
                "a family with no producer cannot carry a freshness claim"
            }
            require(authority != FactAuthority.NOT_OBSERVED || freshness == FactFreshness.ABSENT) {
                "an unobserved fact cannot carry a freshness claim"
            }
            require(
                freshness != FactFreshness.CURRENT_AT_VERSION ||
                    (authority == FactAuthority.AUTHORITATIVE_PERSISTED && observedVersion != null),
            ) {
                "only AUTHORITATIVE_PERSISTED with an observedVersion may claim CURRENT_AT_VERSION"
            }
            require(freshness != FactFreshness.HISTORICAL || (observedVersion != null || sourceVersion != null)) {
                "a HISTORICAL fact must identify the observedVersion or sourceVersion it reflects"
            }
            require(freshness != FactFreshness.EVENTUAL_KNOWN_LAG || watermark != null) {
                "EVENTUAL_KNOWN_LAG requires a watermark"
            }
            require(
                (
                    authority != FactAuthority.PRODUCER_DECLARED &&
                        authority != FactAuthority.POLICY_AUTHORIZATION_RECORD
                ) ||
                    mustNotInfer.isNotEmpty(),
            ) {
                "a declared or recorded authorization fact must state what may not be inferred from it"
            }
        }
    }

/**
 * Inclusive audit sequence range (`[firstPosition, lastPosition]`) verified for chain integrity and
 * anchored to an observed head.
 */
public data class VerifiedStreamRange(
    public val firstPosition: Long,
    public val lastPosition: Long,
) {
    init {
        require(firstPosition >= FIRST_POSITION) { "audit stream positions begin at 1" }
        require(lastPosition >= firstPosition) { "lastPosition must be at least firstPosition" }
    }

    /** Number of contiguous positions in this verified range. */
    public val span: Long
        get() = lastPosition - firstPosition + 1L

    /** Returns `true` if [position] lies within `[firstPosition, lastPosition]`. */
    public operator fun contains(position: Long): Boolean = position in firstPosition..lastPosition
}

/**
 * Raw audit-stream coverage established when the projection was materialized.
 *
 * This is a claim about the underlying audit stream — which contiguous positions were read, verified
 * for chain integrity, and anchored to [observedHeadHash] — and survives a change in decision
 * classification revision.
 */
public data class RawStreamCoverage
    @JvmOverloads
    constructor(
        public val verifiedRange: VerifiedStreamRange? = null,
        public val observedHeadHash: String? = null,
    ) {
        init {
            if (verifiedRange == null) {
                require(observedHeadHash == null) {
                    "a read that verified no stream range cannot name an observedHeadHash"
                }
            } else {
                require(observedHeadHash == null || observedHeadHash.isNotBlank()) {
                    "observedHeadHash must not be blank when present"
                }
            }
        }
    }

/**
 * Decision-record recognition coverage within a verified stream range.
 *
 * Separate from [RawStreamCoverage]: this states how many positions in the verified range were
 * accounted for under [classificationRevision], which positions at decision-producing boundaries
 * lacked a recognized decision record ([unrecordedDecisionPositions]), which positions could not be
 * classified ([unclassifiedPositions]), and the resulting [completeness] classification.
 */
public data class DecisionRecordCoverage(
    public val completeness: Completeness,
    public val accountedPositions: Long,
    public val unrecordedDecisionPositions: Set<Long>,
    public val unclassifiedPositions: Set<Long>,
    public val classificationRevision: String,
) {
    init {
        require(accountedPositions >= 0L) { "accountedPositions cannot be negative" }
        require(classificationRevision.isNotBlank()) { "classificationRevision must not be blank" }
        require(unrecordedDecisionPositions.all { it >= FIRST_POSITION }) {
            "unrecordedDecisionPositions must be positive sequence numbers"
        }
        require(unclassifiedPositions.all { it >= FIRST_POSITION }) {
            "unclassifiedPositions must be positive sequence numbers"
        }
    }
}

/**
 * One decision family's projected governance state, carrying both [rawCoverage] (raw audit-stream
 * coverage) and [recordCoverage] (decision-record completeness and position accounting) as separate
 * structures.
 */
public data class GovernanceDecisionFact
    @JvmOverloads
    constructor(
        public val kind: DecisionKind,
        public val fact: GovernanceFactSummary,
        public val rawCoverage: RawStreamCoverage,
        public val recordCoverage: DecisionRecordCoverage,
        public val decision: String? = null,
        public val reasonCode: String? = null,
        public val boundaryId: String? = null,
        public val boundaryStrength: String? = null,
        public val duplicatesIgnored: Int = 0,
        public val staleIgnored: Int = 0,
    ) {
        init {
            require(duplicatesIgnored >= 0 && staleIgnored >= 0) {
                "ignored record counts cannot be negative"
            }
            require((decision != null) == fact.valuePresent) {
                "decision token presence must match fact.valuePresent"
            }
            requireOptionalSafeToken(decision, "decision")
            requireOptionalSafeToken(reasonCode, "reasonCode")
            requireOptionalSafeToken(boundaryId, "boundaryId")
            requireOptionalSafeToken(boundaryStrength, "boundaryStrength")
            validateCoverageConsistency(rawCoverage, recordCoverage)
            validateDecisionAuthority(kind, fact.authority, decision != null)
        }
    }

/**
 * Read-only view of a materialized governance projection for a single workload scope.
 */
public data class GovernanceProjectionView(
    public val identity: GovernanceProjectionIdentity,
    public val formatVersion: String,
    public val materializedAt: Instant,
    public val provenance: GovernanceSourceProvenance,
    public val currency: GovernanceCurrencyAssessment,
    public val registration: GovernanceFactSummary,
    public val declaredRunIdentity: GovernanceFactSummary,
    public val decisions: List<GovernanceDecisionFact>,
    public val telemetry: GovernanceFactSummary,
) {
    init {
        require(formatVersion.isNotBlank()) { "formatVersion must not be blank" }
        require(decisions.map { it.kind }.distinct().size == decisions.size) {
            "each DecisionKind may appear at most once in a projection view"
        }
        require(registration.authority == FactAuthority.AUTHORITATIVE_PERSISTED) {
            "registration fact must carry AUTHORITATIVE_PERSISTED authority"
        }
        require(
            declaredRunIdentity.authority == FactAuthority.PRODUCER_DECLARED ||
                declaredRunIdentity.authority == FactAuthority.NOT_PRODUCED,
        ) {
            "declaredRunIdentity must remain PRODUCER_DECLARED or NOT_PRODUCED, never authoritative"
        }
        require(
            telemetry.authority == FactAuthority.TELEMETRY ||
                telemetry.authority == FactAuthority.NOT_OBSERVED,
        ) {
            "telemetry summary must remain TELEMETRY or NOT_OBSERVED"
        }
    }

    /** Returns the [GovernanceDecisionFact] for [kind], or `null` if not present. */
    public fun decisionFor(kind: DecisionKind): GovernanceDecisionFact? = decisions.firstOrNull { it.kind == kind }
}

private fun validateCoverageConsistency(
    rawCoverage: RawStreamCoverage,
    recordCoverage: DecisionRecordCoverage,
) {
    val range = rawCoverage.verifiedRange
    if (range == null) {
        require(recordCoverage.completeness != Completeness.COMPLETE) {
            "decision completeness cannot be COMPLETE without a verified stream range"
        }
        require(
            recordCoverage.accountedPositions == 0L &&
                recordCoverage.unrecordedDecisionPositions.isEmpty() &&
                recordCoverage.unclassifiedPositions.isEmpty(),
        ) {
            "a read with no verified range cannot account for or classify stream positions"
        }
    } else {
        require(recordCoverage.accountedPositions <= range.span) {
            "accountedPositions cannot exceed the verified range span"
        }
        require(
            (recordCoverage.unrecordedDecisionPositions + recordCoverage.unclassifiedPositions).all {
                it in range
            },
        ) {
            "all unrecorded and unclassified positions must lie within the verified range"
        }
    }
}

private fun validateDecisionAuthority(
    kind: DecisionKind,
    authority: FactAuthority,
    hasDecision: Boolean,
) {
    require(
        kind != DecisionKind.PROVIDER_ROUTE ||
            !(hasDecision && authority != FactAuthority.PRODUCER_DECLARED),
    ) {
        "PROVIDER_ROUTE has no live production producer and may only be PRODUCER_DECLARED when present"
    }
    val attestsAuthorization =
        kind == DecisionKind.POLICY_DECISION || kind == DecisionKind.TOOL_PERMISSION
    require(!(attestsAuthorization && hasDecision && authority == FactAuthority.AUTHORITATIVE_PERSISTED)) {
        "a policy or tool decision is a POLICY_AUTHORIZATION_RECORD, not authoritative runtime execution state"
    }
}

private const val FIRST_POSITION = 1L
