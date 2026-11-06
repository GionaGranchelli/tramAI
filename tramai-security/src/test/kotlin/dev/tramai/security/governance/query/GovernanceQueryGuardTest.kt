package dev.tramai.security.governance.query

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 0.7.4e — the guards that hold the public query contract.
 *
 * Every public response type refuses an inconsistent construction in its own `init`, so a consumer cannot
 * hold a view that claims more than the evidence established. These tests take the **failing** side of
 * each guard (the side the query-path tests never reach), and assert the exception names the rule rather
 * than merely that something threw. The module's branch-coverage gate, which CI runs, regressed when these
 * types were added; this class is what covers those branches, and it is the reason each assertion checks a
 * message fragment instead of only an exception type.
 */
class GovernanceQueryGuardTest {
    // ------------------------------------------------------------------ identity

    @Test
    fun `an identity refuses a blank or unsafe scope component, naming the field`() {
        val bad = listOf("", "   ", "has space", "semi;colon", "x".repeat(201))
        bad.forEach { value ->
            assertThatThrownBy { GovernanceProjectionIdentity(value, "env", "dep") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("workloadId")
            assertThatThrownBy { GovernanceProjectionIdentity("wl", value, "dep") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("environmentId")
            assertThatThrownBy { GovernanceProjectionIdentity("wl", "env", value) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("deploymentId")
        }
        assertThatThrownBy { GovernanceProjectionIdentity("wl", "env", "dep", "has space") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("workflowRunId")
        assertThat(GovernanceProjectionIdentity("wl", "env", "dep").workflowRunId).isNull()
    }

    @Test
    fun `a query request refuses a blank or unsafe scope component, naming the field`() {
        assertThatThrownBy { GovernanceQueryRequest("", "env", "dep") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("workloadId")
        assertThatThrownBy { GovernanceQueryRequest("wl", "env", "dep", "bad token") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("workflowRunId")
        assertThat(GovernanceQueryRequest("wl", "env", "dep").workflowRunId).isNull()
    }

    // ------------------------------------------------------------------ provenance

    @Test
    fun `provenance refuses a negative version, a negative position, and a hash without a positive position`() {
        assertThatThrownBy { GovernanceSourceProvenance(-1L, 1L, null, "rev") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("registrationVersion")
        assertThatThrownBy { GovernanceSourceProvenance(1L, -1L, null, "rev") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("auditHeadPosition")
        assertThatThrownBy { GovernanceSourceProvenance(1L, 0L, "hash", "rev") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("auditHeadHash")
        assertThatThrownBy { GovernanceSourceProvenance(1L, 1L, "  ", "rev") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("auditHeadHash")
        assertThatThrownBy { GovernanceSourceProvenance(1L, 1L, null, " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("classificationRevision")
        assertThat(GovernanceSourceProvenance(1L, 1L, null, "rev").readConsistency)
            .isEqualTo(ReadConsistency.INDEPENDENT_SOURCES)
    }

    // ------------------------------------------------------------------ currency

    @Test
    fun `a currency assessment must carry its note`() {
        assertThatThrownBy { GovernanceCurrencyAssessment(SourceCurrency.UNKNOWN, SourceCurrency.UNKNOWN, " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("note")
    }

    // ------------------------------------------------------------------ fact summary guards

    @Test
    fun `a fact summary refuses a blank description and blank inference boundaries`() {
        assertThatThrownBy { factSummary().copy(established = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("established")
        assertThatThrownBy {
            factSummary(
                authority = FactAuthority.PRODUCER_DECLARED,
                mustNotInfer = listOf("ok", " "),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("mustNotInfer")
    }

    @Test
    fun `absence is exactly the case where no value is reported, in both directions`() {
        assertThatThrownBy { factSummary(freshness = FactFreshness.ABSENT, valuePresent = true) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("absence")
        assertThatThrownBy { factSummary(freshness = FactFreshness.HISTORICAL, valuePresent = false) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("absence")
    }

    @Test
    fun `an absent fact may only be unproduced, unobserved or authoritative`() {
        assertThatThrownBy {
            factSummary(authority = FactAuthority.TELEMETRY, freshness = FactFreshness.ABSENT, valuePresent = false)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("absence")

        assertThatThrownBy {
            factSummary(
                authority = FactAuthority.NOT_PRODUCED,
                freshness = FactFreshness.HISTORICAL,
                valuePresent = true,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no producer cannot carry a freshness claim")

        assertThatThrownBy {
            factSummary(
                authority = FactAuthority.NOT_OBSERVED,
                freshness = FactFreshness.HISTORICAL,
                valuePresent = true,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unobserved fact cannot carry a freshness claim")
    }

    @Test
    fun `freshness claims require the evidence each one names`() {
        assertThatThrownBy {
            factSummary(
                authority = FactAuthority.TELEMETRY,
                freshness = FactFreshness.CURRENT_AT_VERSION,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("CURRENT_AT_VERSION")

        assertThatThrownBy {
            factSummary(
                authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                freshness = FactFreshness.CURRENT_AT_VERSION,
                observedVersion = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("CURRENT_AT_VERSION")

        assertThatThrownBy {
            factSummary(
                authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                freshness = FactFreshness.HISTORICAL,
                observedVersion = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("HISTORICAL")

        assertThatThrownBy {
            factSummary(
                authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                freshness = FactFreshness.EVENTUAL_KNOWN_LAG,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("watermark")
    }

    @Test
    fun `a declared or recorded authorization fact must state what may not be inferred`() {
        assertThatThrownBy {
            factSummary(authority = FactAuthority.PRODUCER_DECLARED, mustNotInfer = emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("may not be inferred")

        assertThatThrownBy {
            factSummary(authority = FactAuthority.POLICY_AUTHORIZATION_RECORD, mustNotInfer = emptyList())
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("may not be inferred")
    }

    // ------------------------------------------------------------------ coverage guards

    @Test
    fun `a verified stream range refuses a non-positive start and an inverted range`() {
        assertThatThrownBy { VerifiedStreamRange(0L, 3L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("begin at 1")
        assertThatThrownBy { VerifiedStreamRange(4L, 3L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("lastPosition")
        val range = VerifiedStreamRange(2L, 5L)
        assertThat(range.span).isEqualTo(4L)
        assertThat(range.contains(3L)).isTrue()
        assertThat(range.contains(6L)).isFalse()
    }

    @Test
    fun `raw coverage refuses a head hash when no range was verified`() {
        assertThatThrownBy { RawStreamCoverage(null, "hash") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("observedHeadHash")
        assertThatThrownBy { RawStreamCoverage(VerifiedStreamRange(1L, 2L), " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("observedHeadHash")
        assertThat(RawStreamCoverage().observedHeadHash).isNull()
    }

    @Test
    fun `decision record coverage refuses negative counts and blank revisions`() {
        assertThatThrownBy { recordCoverage(accounted = -1L) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("accountedPositions")
        assertThatThrownBy { recordCoverage(revision = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("classificationRevision")
        assertThatThrownBy { recordCoverage(unrecorded = setOf(0L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unrecordedDecisionPositions")
        assertThatThrownBy { recordCoverage(unclassified = setOf(0L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unclassifiedPositions")
    }

    // ------------------------------------------------------------------ decision fact guards

    @Test
    fun `a decision fact refuses negative ignore counts and a decision token that contradicts valuePresent`() {
        assertThatThrownBy { decisionFact().copy(duplicatesIgnored = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cannot be negative")
        assertThatThrownBy { decisionFact().copy(staleIgnored = -1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cannot be negative")
        assertThatThrownBy {
            decisionFact().copy(
                decision = "ALLOW",
                fact =
                    factSummary(
                        authority = FactAuthority.NOT_OBSERVED,
                        freshness = FactFreshness.ABSENT,
                        valuePresent = false,
                    ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("valuePresent")
        assertThatThrownBy { decisionFact().copy(decision = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("valuePresent")
    }

    @Test
    fun `a decision fact refuses an unsafe token in any exposed identifier`() {
        assertThatThrownBy { decisionFact().copy(decision = "not a token") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decision")
        assertThatThrownBy { decisionFact().copy(reasonCode = "not a token") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reasonCode")
        assertThatThrownBy { decisionFact().copy(boundaryId = "not a token") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("boundaryId")
        assertThatThrownBy { decisionFact().copy(boundaryStrength = "not a token") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("boundaryStrength")
    }

    @Test
    fun `decision completeness cannot exceed what the raw range verified`() {
        assertThatThrownBy {
            decisionFact(
                raw = RawStreamCoverage(),
                coverage = recordCoverage(completeness = Completeness.COMPLETE, accounted = 0L),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("without a verified stream range")
        assertThatThrownBy {
            decisionFact(
                raw = RawStreamCoverage(),
                coverage = recordCoverage(completeness = Completeness.UNKNOWN, accounted = 1L),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("cannot account")
        assertThatThrownBy {
            decisionFact(
                raw = RawStreamCoverage(),
                coverage = recordCoverage(completeness = Completeness.UNKNOWN, unrecorded = setOf(1L)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("cannot account")
        assertThatThrownBy {
            decisionFact(
                raw = RawStreamCoverage(),
                coverage = recordCoverage(completeness = Completeness.UNKNOWN, unclassified = setOf(1L)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("cannot account")
        assertThatThrownBy { decisionFact(coverage = recordCoverage(accounted = 9L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("verified range span")
        assertThatThrownBy { decisionFact(coverage = recordCoverage(unrecorded = setOf(99L))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("within the verified range")
        assertThatThrownBy { decisionFact(coverage = recordCoverage(unclassified = setOf(99L))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("within the verified range")
    }

    @Test
    fun `an authorization record cannot be presented as execution authority`() {
        assertThatThrownBy {
            decisionFact(
                kind = DecisionKind.POLICY_DECISION,
                authority = FactAuthority.AUTHORITATIVE_PERSISTED,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("POLICY_AUTHORIZATION_RECORD")

        assertThatThrownBy {
            decisionFact(
                kind = DecisionKind.TOOL_PERMISSION,
                authority = FactAuthority.AUTHORITATIVE_PERSISTED,
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("POLICY_AUTHORIZATION_RECORD")
    }

    @Test
    fun `a routing decision without a live producer may only be producer-declared`() {
        assertThatThrownBy {
            decisionFact(kind = DecisionKind.PROVIDER_ROUTE, authority = FactAuthority.AUTHORITATIVE_PERSISTED)
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PROVIDER_ROUTE")

        val declared =
            decisionFact(
                kind = DecisionKind.PROVIDER_ROUTE,
                authority = FactAuthority.PRODUCER_DECLARED,
            )
        assertThat(declared.kind).isEqualTo(DecisionKind.PROVIDER_ROUTE)
    }

    // ------------------------------------------------------------------ view guards

    @Test
    fun `a view refuses a blank format version and a duplicated decision family`() {
        assertThatThrownBy { view(formatVersion = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("formatVersion")
        assertThatThrownBy {
            val policy = decisionFact(kind = DecisionKind.POLICY_DECISION)
            view(decisions = listOf(policy, policy))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("at most once")
    }

    @Test
    fun `a view keeps each fact in the authority its evidence allows`() {
        assertThatThrownBy { view(registration = factSummary(authority = FactAuthority.TELEMETRY)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("registration fact")
        assertThatThrownBy {
            view(declared = factSummary(authority = FactAuthority.AUTHORITATIVE_PERSISTED, observedVersion = 1L))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("declaredRunIdentity")
        assertThatThrownBy {
            view(telemetry = factSummary(authority = FactAuthority.AUTHORITATIVE_PERSISTED, observedVersion = 1L))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("telemetry")
        assertThatThrownBy {
            view(declared = factSummary(authority = FactAuthority.TELEMETRY))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("declaredRunIdentity")
    }

    // ------------------------------------------------------------------ result guards

    @Test
    fun `a refusal result must state its reason`() {
        assertThatThrownBy { GovernanceQueryResult.UnsupportedFormat(" ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reason")
        assertThatThrownBy { GovernanceQueryResult.InvalidStoredProjection(" ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("reason")
        assertThat(GovernanceQueryResult.NotMaterialized).isSameAs(GovernanceQueryResult.NotMaterialized)
    }

    // ------------------------------------------------------------------ fixtures

    private fun factSummary(
        authority: FactAuthority = FactAuthority.AUTHORITATIVE_PERSISTED,
        freshness: FactFreshness = FactFreshness.HISTORICAL,
        mustNotInfer: List<String> = emptyList(),
        valuePresent: Boolean = true,
        observedVersion: Long? = 1L,
    ): GovernanceFactSummary =
        GovernanceFactSummary(
            authority = authority,
            freshness = freshness,
            established = "established",
            mustNotInfer = mustNotInfer,
            valuePresent = valuePresent,
            observedVersion = observedVersion,
            sourceVersion = null,
            watermark = null,
        )

    private fun recordCoverage(
        completeness: Completeness = Completeness.COMPLETE,
        accounted: Long = 2L,
        unrecorded: Set<Long> = emptySet(),
        unclassified: Set<Long> = emptySet(),
        revision: String = "rev-1",
    ): DecisionRecordCoverage =
        DecisionRecordCoverage(
            completeness = completeness,
            accountedPositions = accounted,
            unrecordedDecisionPositions = unrecorded,
            unclassifiedPositions = unclassified,
            classificationRevision = revision,
        )

    private fun decisionFact(
        kind: DecisionKind = DecisionKind.POLICY_DECISION,
        authority: FactAuthority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
        raw: RawStreamCoverage = RawStreamCoverage(VerifiedStreamRange(1L, 2L), "hash-2"),
        coverage: DecisionRecordCoverage = recordCoverage(),
    ): GovernanceDecisionFact =
        GovernanceDecisionFact(
            kind = kind,
            fact = factSummary(authority = authority, mustNotInfer = listOf("execution")),
            rawCoverage = raw,
            recordCoverage = coverage,
            decision = "ALLOW",
        )

    private fun view(
        formatVersion: String = "governance-read-model.v1",
        registration: GovernanceFactSummary = factSummary(),
        declared: GovernanceFactSummary =
            factSummary(
                authority = FactAuthority.PRODUCER_DECLARED,
                mustNotInfer = listOf("authenticated identity"),
            ),
        telemetry: GovernanceFactSummary = factSummary(authority = FactAuthority.TELEMETRY),
        decisions: List<GovernanceDecisionFact> = listOf(decisionFact()),
    ): GovernanceProjectionView =
        GovernanceProjectionView(
            identity = GovernanceProjectionIdentity("wl", "env", "dep", "run"),
            formatVersion = formatVersion,
            materializedAt = Instant.parse("2026-10-16T00:00:00Z"),
            provenance = GovernanceSourceProvenance(1L, 2L, "hash-2", "rev-1"),
            currency =
                GovernanceCurrencyAssessment(
                    registration = SourceCurrency.UNKNOWN,
                    audit = SourceCurrency.UNKNOWN,
                    note = "no fresh observation",
                ),
            registration = registration,
            declaredRunIdentity = declared,
            decisions = decisions,
            telemetry = telemetry,
        )
}
