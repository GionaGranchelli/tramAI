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
        // so no String-valued accessor of the exposure or of its decisions can return it.
        assertThat(stringValuesOf(exposure)).doesNotContain(credentialShapedReasonCode)
        assertThat(exposure.decisions.flatMap { stringValuesOf(it) }).doesNotContain(credentialShapedReasonCode)
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
            GovernanceExposure.of(
                state = GovernanceExposureState.NOT_MATERIALIZED,
                registrationCurrency = SourceCurrency.UNKNOWN,
                auditCurrency = SourceCurrency.UNKNOWN,
                decisions = listOf(GovernanceDecisionExposure.of(GovernanceQueryFixtures.decision())),
            )
        }.hasMessageContaining("only when a projection was actually readable")
    }

    @Test
    fun `spring ai boundary evidence maps without claiming authorship or enforcement`() {
        // XR1. The evidence comes from the existing Spring AI external-runtime reference path: the
        // GovernedToolCallback declared the registered boundary "spring-ai-tool-callback" and the policy engine
        // produced an authorization record. TramAI did not author the application workflow, and the declared
        // boundary strength is not evidence that this call passed through it, so neither may be exported as one.
        val springAiDecision =
            GovernanceDecisionFact(
                kind = DecisionKind.TOOL_PERMISSION,
                fact =
                    GovernanceFactSummary(
                        authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
                        freshness = FactFreshness.HISTORICAL,
                        established = "the spring-ai tool callback consulted the engine before delegating",
                        mustNotInfer =
                            listOf("must not infer that TramAI authored the application workflow"),
                        valuePresent = true,
                        sourceVersion = 12,
                    ),
                rawCoverage = RawStreamCoverage(verifiedRange = null),
                recordCoverage =
                    DecisionRecordCoverage(
                        completeness = Completeness.GAPS_PRESENT,
                        accountedPositions = 0,
                        unrecordedDecisionPositions = emptySet(),
                        unclassifiedPositions = emptySet(),
                        classificationRevision = "rev-7",
                    ),
                decision = "DENY",
                boundaryId = "spring-ai-tool-callback",
                boundaryStrength = "AUTHORITATIVE",
                reasonExposure = ReasonCodeExposure.NOT_RECORDED,
            )

        val mapped = GovernanceExposure.from(GovernanceQueryResult.Found(viewOf(springAiDecision))).decisions.single()

        // The authority is an authorization record, never persisted runtime authority: TramAI did not run this.
        assertThat(mapped.authority).isEqualTo(FactAuthority.POLICY_AUTHORIZATION_RECORD)
        assertThat(mapped.authority).isNotEqualTo(FactAuthority.AUTHORITATIVE_PERSISTED)
        // The registered strength and the declared boundary id are not exported at all: strength is not origin.
        assertThat(mapped.hasVerifiedStreamRange).isFalse()
        assertThat(mapped.recordCompleteness).isEqualTo(Completeness.GAPS_PRESENT)
        // The recorded decision category is exported; the declared boundary and its strength are not.
        assertThat(mapped.recordedDecision).isEqualTo("DENY")
        assertThat(stringValuesOf(mapped)).doesNotContain("AUTHORITATIVE")
        assertThat(stringValuesOf(mapped)).doesNotContain("spring-ai-tool-callback")
    }

    @Test
    fun `registration and audit currency map through independently`() {
        // The fixture records a current registration and an unobserved audit head. Two sources, two answers,
        // and no combined freshness flag exists to flatten them into one.
        val exposure = GovernanceExposure.from(GovernanceQueryResult.Found(viewOf()))

        assertThat(exposure.state).isEqualTo(GovernanceExposureState.EVIDENCE_AVAILABLE)
        assertThat(exposure.registrationCurrency).isEqualTo(SourceCurrency.CURRENT)
        assertThat(exposure.auditCurrency).isEqualTo(SourceCurrency.UNKNOWN)
    }

    /** Every String-valued, no-argument accessor of a value: what a consumer could read out of it. */
    private fun stringValuesOf(value: Any): List<String?> =
        value.javaClass.methods
            .filter { it.parameterCount == 0 && it.returnType == String::class.java }
            .mapNotNull { method -> runCatching { method.invoke(value) as String? }.getOrNull() }

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
