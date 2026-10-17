package dev.tramai.observability.governance

import dev.tramai.security.governance.query.Completeness
import dev.tramai.security.governance.query.DecisionKind
import dev.tramai.security.governance.query.DecisionRecordCoverage
import dev.tramai.security.governance.query.FactAuthority
import dev.tramai.security.governance.query.FactFreshness
import dev.tramai.security.governance.query.GovernanceCurrencyAssessment
import dev.tramai.security.governance.query.GovernanceDecisionFact
import dev.tramai.security.governance.query.GovernanceFactSummary
import dev.tramai.security.governance.query.GovernanceProjectionIdentity
import dev.tramai.security.governance.query.GovernanceProjectionView
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.GovernanceSourceProvenance
import dev.tramai.security.governance.query.RawStreamCoverage
import dev.tramai.security.governance.query.ReadConsistency
import dev.tramai.security.governance.query.ReasonCodeExposure
import dev.tramai.security.governance.query.SourceCurrency
import dev.tramai.security.governance.query.VerifiedStreamRange
import java.time.Instant

/**
 * Shared fixtures built from the real query DTOs, through their validating constructors.
 *
 * A fixture that violates the projection contract fails here rather than quietly exercising a branch no real
 * read could produce. Tests obtain exposures through [GovernanceExposure.from] over these DTOs, which is the only
 * construction path a consumer has.
 */
internal object GovernanceQueryFixtures {
    const val BOUNDARY_ID = "spring-ai-tool-callback"
    const val BOUNDARY_STRENGTH = "AUTHORITATIVE"
    const val FREE_TEXT_ESTABLISHED = "established text that must never be exported"
    const val CURRENCY_NOTE = "currency note that must never be exported"

    fun view(
        vararg decisions: GovernanceDecisionFact,
        registrationCurrency: SourceCurrency = SourceCurrency.CURRENT,
        auditCurrency: SourceCurrency = SourceCurrency.UNKNOWN,
    ): GovernanceProjectionView =
        GovernanceProjectionView(
            identity = GovernanceProjectionIdentity("claims", "production", "eu-west-amsterdam-01", "run-1"),
            formatVersion = "governance-read-model.v1",
            materializedAt = Instant.parse("2026-06-01T12:00:00Z"),
            provenance =
                GovernanceSourceProvenance(
                    registrationVersion = 4,
                    auditHeadPosition = 12,
                    auditHeadHash = "hash-12",
                    classificationRevision = "rev-7",
                    readConsistency = ReadConsistency.INDEPENDENT_SOURCES,
                ),
            currency = GovernanceCurrencyAssessment(registrationCurrency, auditCurrency, CURRENCY_NOTE),
            registration = fact(FactAuthority.AUTHORITATIVE_PERSISTED, FactFreshness.CURRENT_AT_VERSION),
            declaredRunIdentity = fact(FactAuthority.PRODUCER_DECLARED, FactFreshness.HISTORICAL),
            decisions = decisions.toList(),
            telemetry = fact(FactAuthority.TELEMETRY, FactFreshness.EVENTUAL_KNOWN_LAG, watermark = 5),
        )

    /** An exposure for whatever the given decision facts legitimately establish. */
    fun exposureOf(vararg decisions: GovernanceDecisionFact): GovernanceExposure =
        GovernanceExposure.from(GovernanceQueryResult.Found(view(*decisions)))

    /** An exposure whose two source currencies are independent, for the currency-boundary tests. */
    fun exposureOfCurrencies(
        registration: SourceCurrency,
        audit: SourceCurrency,
    ): GovernanceExposure =
        GovernanceExposure.from(
            GovernanceQueryResult.Found(view(registrationCurrency = registration, auditCurrency = audit)),
        )

    /**
     * A decision fact with the coverage consistent by construction: without a verified range there is no
     * position accounting and completeness cannot be COMPLETE; with one, the accounting spans that range.
     */
    fun decision(
        kind: DecisionKind = DecisionKind.POLICY_DECISION,
        recordedDecision: String? = "DENY",
        verifiedRange: VerifiedStreamRange? = null,
        completeness: Completeness = Completeness.GAPS_PRESENT,
        authority: FactAuthority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
    ): GovernanceDecisionFact =
        buildDecision(
            kind = kind,
            authority = authority,
            recordedDecision = recordedDecision,
            verifiedRange = verifiedRange,
            completeness = completeness,
        )

    /** A family the runtime never observed: no value, no freshness claim, no completeness claim. */
    fun unobserved(kind: DecisionKind): GovernanceDecisionFact =
        buildDecision(
            kind = kind,
            authority = FactAuthority.NOT_OBSERVED,
            recordedDecision = null,
            verifiedRange = null,
            completeness = Completeness.UNKNOWN,
        )

    /** A recorded decision whose reason code was withheld at the query boundary. */
    fun withheldReason(kind: DecisionKind = DecisionKind.POLICY_DECISION): GovernanceDecisionFact =
        buildDecision(
            kind = kind,
            authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
            recordedDecision = "DENY",
            verifiedRange = null,
            completeness = Completeness.GAPS_PRESENT,
        ).copy(reasonExposure = ReasonCodeExposure.WITHHELD)

    private fun buildDecision(
        kind: DecisionKind,
        authority: FactAuthority,
        recordedDecision: String?,
        verifiedRange: VerifiedStreamRange?,
        completeness: Completeness,
    ): GovernanceDecisionFact =
        GovernanceDecisionFact(
            kind = kind,
            fact =
                fact(
                    authority = authority,
                    // A family with no producer or no observation cannot carry a freshness claim.
                    freshness =
                        if (authority == FactAuthority.NOT_OBSERVED || authority == FactAuthority.NOT_PRODUCED) {
                            FactFreshness.ABSENT
                        } else {
                            FactFreshness.HISTORICAL
                        },
                    mustNotInfer = listOf("must not infer that this authorized or prevented an execution"),
                ),
            rawCoverage = RawStreamCoverage(verifiedRange = verifiedRange),
            recordCoverage =
                DecisionRecordCoverage(
                    completeness = completeness,
                    accountedPositions = verifiedRange?.let { it.lastPosition - it.firstPosition + 1 } ?: 0,
                    unrecordedDecisionPositions = emptySet(),
                    unclassifiedPositions = emptySet(),
                    classificationRevision = "rev-7",
                ),
            decision = recordedDecision,
            reasonCode = null,
            reasonExposure = ReasonCodeExposure.NOT_RECORDED,
        )

    /**
     * The XR1 evidence: what the Spring AI external-runtime path can actually establish. A policy authorization
     * record from the registered boundary, with a declared strength that is not evidence of passing through it.
     */
    fun springAiToolPermissionDecision(completeness: Completeness = Completeness.GAPS_PRESENT): GovernanceDecisionFact =
        GovernanceDecisionFact(
            kind = DecisionKind.TOOL_PERMISSION,
            fact =
                fact(
                    authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
                    freshness = FactFreshness.HISTORICAL,
                    mustNotInfer = listOf("must not infer that TramAI authored the application workflow"),
                ),
            rawCoverage = RawStreamCoverage(verifiedRange = null),
            recordCoverage =
                DecisionRecordCoverage(
                    completeness = completeness,
                    accountedPositions = 0,
                    unrecordedDecisionPositions = emptySet(),
                    unclassifiedPositions = emptySet(),
                    classificationRevision = "rev-7",
                ),
            decision = "DENY",
            boundaryId = BOUNDARY_ID,
            boundaryStrength = BOUNDARY_STRENGTH,
            reasonExposure = ReasonCodeExposure.NOT_RECORDED,
        )

    fun fact(
        authority: FactAuthority,
        freshness: FactFreshness,
        mustNotInfer: List<String> = listOf("must not infer text that must never be exported"),
        watermark: Long? = null,
    ): GovernanceFactSummary =
        GovernanceFactSummary(
            authority = authority,
            freshness = freshness,
            established = FREE_TEXT_ESTABLISHED,
            mustNotInfer = mustNotInfer,
            valuePresent = freshness != FactFreshness.ABSENT,
            observedVersion = if (authority == FactAuthority.AUTHORITATIVE_PERSISTED) 4 else null,
            sourceVersion = if (freshness == FactFreshness.HISTORICAL) 3 else null,
            watermark = watermark,
        )
}
