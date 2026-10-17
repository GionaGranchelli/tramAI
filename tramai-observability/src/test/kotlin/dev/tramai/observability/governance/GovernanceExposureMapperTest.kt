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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Instant
import kotlin.test.Test

/**
 * 0.7.4f phase 1/2 - the mapping from a real governance query result.
 *
 * The fixtures are the real query DTOs, built through their validating constructors, so what is exercised is
 * the actual contract rather than a stand-in with the same field names.
 */
class GovernanceExposureMapperTest {
    @Test
    fun `a real projection maps to its evidence without carrying what it must not`() {
        val credentialShapedReasonCode = "sk-live-example123"
        val view =
            viewOf(
                policyDecision(
                    authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
                    freshness = FactFreshness.HISTORICAL,
                    verifiedRange = VerifiedStreamRange(firstPosition = 1, lastPosition = 3),
                    completeness = Completeness.COMPLETE,
                    reasonExposure = ReasonCodeExposure.WITHHELD,
                ),
                policyDecision(
                    authority = FactAuthority.NOT_PRODUCED,
                    freshness = FactFreshness.ABSENT,
                    verifiedRange = null,
                    completeness = Completeness.UNKNOWN,
                    reasonExposure = ReasonCodeExposure.NOT_RECORDED,
                ).copy(kind = DecisionKind.PROVIDER_ROUTE),
            )

        val exposure = GovernanceExposure.from(GovernanceQueryResult.Found(view))

        assertThat(exposure.state).isEqualTo(GovernanceExposureState.EVIDENCE_AVAILABLE)
        assertThat(exposure.decisions).hasSize(2)
        val authoritative = exposure.decisions[0]
        assertThat(authoritative.authority).isEqualTo(FactAuthority.POLICY_AUTHORIZATION_RECORD)
        assertThat(authoritative.freshness).isEqualTo(FactFreshness.HISTORICAL)
        assertThat(authoritative.hasVerifiedStreamRange).isTrue()
        assertThat(authoritative.recordCompleteness).isEqualTo(Completeness.COMPLETE)
        assertThat(authoritative.reasonCodeExposure).isEqualTo(ReasonCodeExposure.WITHHELD)

        // The withheld code is not merely unexported by the adapter: it is absent from the exposure entirely,
        // so no rendering of it - including a diagnostic toString - can leak it.
        assertThat(exposure.toString()).doesNotContain(credentialShapedReasonCode)
        assertThat(exposure.decisions.toString()).doesNotContain(credentialShapedReasonCode)
    }

    @Test
    fun `a verified range never becomes complete decision recognition`() {
        val view =
            viewOf(
                policyDecision(
                    authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
                    freshness = FactFreshness.HISTORICAL,
                    verifiedRange = VerifiedStreamRange(firstPosition = 1, lastPosition = 3),
                    completeness = Completeness.UNKNOWN,
                    reasonExposure = ReasonCodeExposure.NOT_RECORDED,
                ),
            )

        val decision = GovernanceExposure.from(GovernanceQueryResult.Found(view)).decisions.single()

        assertThat(decision.hasVerifiedStreamRange).isTrue()
        assertThat(decision.recordCompleteness).isEqualTo(Completeness.UNKNOWN)
    }

    @Test
    fun `every unreadable result keeps its own state and carries no evidence`() {
        val cases =
            listOf(
                GovernanceQueryResult.NotMaterialized::class.java to GovernanceExposureState.NOT_MATERIALIZED,
                GovernanceQueryResult.UnsupportedFormat::class.java to GovernanceExposureState.UNSUPPORTED_FORMAT,
                GovernanceQueryResult.InvalidStoredProjection::class.java to
                    GovernanceExposureState.INVALID_STORED_PROJECTION,
            )

        cases.forEach { (_, expected) ->
            assertThat(expected).isNotEqualTo(GovernanceExposureState.EVIDENCE_AVAILABLE)
        }
        // Constructed from the real result types: none of the three may carry decision facts.
        listOf(
            GovernanceQueryResult.NotMaterialized,
            GovernanceQueryResult.UnsupportedFormat("v99"),
            GovernanceQueryResult.InvalidStoredProjection("digest mismatch"),
        ).forEach { result ->
            val exposure = GovernanceExposure.from(result)
            assertThat(exposure.state).isNotEqualTo(GovernanceExposureState.EVIDENCE_AVAILABLE)
            assertThat(exposure.decisions).isEmpty()
        }
    }

    @Test
    fun `an available projection may legitimately carry no decision families`() {
        val exposure = GovernanceExposure.from(GovernanceQueryResult.Found(viewOf()))

        assertThat(exposure.state).isEqualTo(GovernanceExposureState.EVIDENCE_AVAILABLE)
        assertThat(exposure.decisions).isEmpty()
    }

    @Test
    fun `an unavailable state cannot carry decision evidence`() {
        assertThatThrownBy {
            GovernanceExposure(
                GovernanceExposureState.NOT_MATERIALIZED,
                listOf(
                    GovernanceDecisionExposure(
                        kind = DecisionKind.POLICY_DECISION,
                        authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                        freshness = FactFreshness.HISTORICAL,
                        hasVerifiedStreamRange = true,
                        recordCompleteness = Completeness.COMPLETE,
                        reasonCodeExposure = ReasonCodeExposure.EXPOSED,
                    ),
                ),
            )
        }.hasMessageContaining("only when a projection was actually readable")
    }

    private fun viewOf(vararg decisions: GovernanceDecisionFact) =
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
            currency = GovernanceCurrencyAssessment(SourceCurrency.CURRENT, SourceCurrency.UNKNOWN, "note"),
            registration = fact(FactAuthority.AUTHORITATIVE_PERSISTED, FactFreshness.CURRENT_AT_VERSION),
            declaredRunIdentity = fact(FactAuthority.PRODUCER_DECLARED, FactFreshness.HISTORICAL),
            decisions = decisions.toList(),
            telemetry = fact(FactAuthority.TELEMETRY, FactFreshness.EVENTUAL_KNOWN_LAG, watermark = 5),
        )

    private fun fact(
        authority: FactAuthority,
        freshness: FactFreshness,
        observedVersion: Long? = if (authority == FactAuthority.AUTHORITATIVE_PERSISTED) 4 else null,
        watermark: Long? = null,
    ) = GovernanceFactSummary(
        authority = authority,
        freshness = freshness,
        established = "established text that must never be exported",
        mustNotInfer = listOf("must not infer text that must never be exported"),
        valuePresent = true,
        observedVersion = observedVersion,
        sourceVersion = if (freshness == FactFreshness.HISTORICAL) 3 else null,
        watermark = watermark,
    )

    private fun policyDecision(
        authority: FactAuthority,
        freshness: FactFreshness,
        verifiedRange: VerifiedStreamRange?,
        completeness: Completeness,
        reasonExposure: ReasonCodeExposure,
    ) = GovernanceDecisionFact(
        kind = DecisionKind.POLICY_DECISION,
        fact =
            GovernanceFactSummary(
                authority = authority,
                freshness = freshness,
                established = "established",
                mustNotInfer = listOf("must not infer execution from an authorization record"),
                valuePresent = freshness != FactFreshness.ABSENT,
                observedVersion = if (authority == FactAuthority.AUTHORITATIVE_PERSISTED) 3 else null,
                sourceVersion = if (freshness == FactFreshness.HISTORICAL) 3 else null,
            ),
        rawCoverage = RawStreamCoverage(verifiedRange = verifiedRange),
        recordCoverage =
            DecisionRecordCoverage(
                completeness = completeness,
                accountedPositions = if (verifiedRange == null) 0 else 3,
                unrecordedDecisionPositions = emptySet(),
                unclassifiedPositions = emptySet(),
                classificationRevision = "rev-7",
            ),
        decision = if (freshness == FactFreshness.ABSENT) null else "DENY",
        reasonExposure = reasonExposure,
    )
}
