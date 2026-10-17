package dev.tramai.observability.governance

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.security.governance.query.Completeness
import dev.tramai.security.governance.query.DecisionKind
import dev.tramai.security.governance.query.FactAuthority
import dev.tramai.security.governance.query.FactFreshness
import dev.tramai.security.governance.query.ReasonCodeExposure
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test

/**
 * 0.7.4f phase 4 - qualification against a real OpenTelemetry export.
 *
 * Every assertion reads what the SDK's in-memory exporter actually received: the finished [SpanData] and the
 * attributes rendered from its events, not an intermediate map the adapter built.
 */
class GovernanceTelemetryExporterTest {
    private val exporter = InMemorySpanExporter.create()

    private lateinit var provider: SdkTracerProvider
    private lateinit var telemetry: GovernanceTelemetryExporter

    @BeforeEach
    fun setUp() {
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        telemetry = GovernanceTelemetryExporter(provider.get("governance-telemetry-test"))
    }

    @AfterEach
    fun tearDown() {
        provider.close()
    }

    @Test
    fun `permitted evidence is emitted under catalogue attribute keys`() {
        telemetry.export(exposureWith(authoritativePolicyDecision()))

        val span = exporter.finishedSpanItems.single()
        val rendered = render(span)

        assertThat(span.name).isEqualTo("tramai.governance.evidence.exported")
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_EXPOSURE_STATE.name)
        assertThat(rendered).contains(GovernanceExposureState.EVIDENCE_AVAILABLE.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_KIND.name)
        assertThat(rendered).contains(DecisionKind.POLICY_DECISION.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY.name)
        assertThat(rendered).contains(FactAuthority.AUTHORITATIVE_PERSISTED.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_FRESHNESS.name)
        assertThat(rendered).contains(FactFreshness.HISTORICAL.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_VERIFIED_RANGE.name)
        assertThat(rendered).contains("PRESENT")
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_COMPLETENESS.name)
        assertThat(rendered).contains(Completeness.COMPLETE.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_REASON_EXPOSURE.name)
    }

    @Test
    fun `a withheld reason code cannot reach telemetry`() {
        // The exposure type has no field for the code text, so a decision whose stored code was withheld
        // renders its exposure state and nothing of the value itself.
        val withheld =
            GovernanceDecisionExposure(
                kind = DecisionKind.POLICY_DECISION,
                authority = FactAuthority.AUTHORITATIVE_PERSISTED,
                freshness = FactFreshness.HISTORICAL,
                hasVerifiedStreamRange = false,
                recordCompleteness = Completeness.UNKNOWN,
                reasonCodeExposure = ReasonCodeExposure.WITHHELD,
            )

        telemetry.export(exposureWith(withheld))

        val rendered = render(exporter.finishedSpanItems.single())
        assertThat(rendered).contains(ReasonCodeExposure.WITHHELD.name)
        assertThat(rendered).doesNotContain("sk-live-example123")
        assertThat(rendered).doesNotContain("policy_allowed")
    }

    @Test
    fun `a non-authoritative classification is exported as itself and never upgraded`() {
        // Found by the mutation campaign: forcing the authority attribute would have gone unnoticed because the
        // other fixtures already carry the authoritative value. Evidence of any weaker class must render as
        // itself, so an upgrade is impossible to smuggle in.
        telemetry.export(exposureWith(authoritativePolicyDecision().copy(authority = FactAuthority.NOT_PRODUCED)))

        val rendered = render(exporter.finishedSpanItems.single())

        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY.name)
        assertThat(rendered).contains(FactAuthority.NOT_PRODUCED.name)
        assertThat(rendered).doesNotContain(FactAuthority.AUTHORITATIVE_PERSISTED.name)
    }

    @Test
    fun `spring ai boundary evidence is exported without an enforcement claim`() {
        // XR1 at the export boundary. The evidence is what the Spring AI reference path can actually establish:
        // a policy authorization record from the registered boundary, not proof that TramAI ran the workflow or
        // that the call passed through the boundary. Nothing rendered may read as enforcement.
        telemetry.export(
            exposureWith(
                GovernanceDecisionExposure(
                    kind = DecisionKind.TOOL_PERMISSION,
                    authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
                    freshness = FactFreshness.HISTORICAL,
                    hasVerifiedStreamRange = false,
                    recordCompleteness = Completeness.GAPS_PRESENT,
                    reasonCodeExposure = ReasonCodeExposure.NOT_RECORDED,
                ),
            ),
        )

        val rendered = render(exporter.finishedSpanItems.single())

        assertThat(rendered).contains(FactAuthority.POLICY_AUTHORIZATION_RECORD.name)
        assertThat(rendered).doesNotContain(FactAuthority.AUTHORITATIVE_PERSISTED.name)
        assertThat(rendered).contains("ABSENT")
        assertThat(rendered).contains(Completeness.GAPS_PRESENT.name)
    }

    @Test
    fun `an unread projection emits the state and no decision evidence`() {
        telemetry.export(GovernanceExposure(GovernanceExposureState.NOT_MATERIALIZED, emptyList()))

        val span = exporter.finishedSpanItems.single()
        val rendered = render(span)

        assertThat(rendered).contains(GovernanceExposureState.NOT_MATERIALIZED.name)
        assertThat(rendered).doesNotContain(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY.name)
        assertThat(rendered).doesNotContain(FactAuthority.AUTHORITATIVE_PERSISTED.name)
        assertThat(span.events).hasSize(1)
    }

    @Test
    fun `equivalent exposures render identically`() {
        telemetry.export(exposureWith(authoritativePolicyDecision()))
        telemetry.export(exposureWith(authoritativePolicyDecision()))

        val rendered = exporter.finishedSpanItems.map { render(it) }
        assertThat(rendered).hasSize(2)
        assertThat(rendered[0]).isEqualTo(rendered[1])
    }

    /** Renders the attributes the exporter actually delivered, event by event. */
    private fun render(span: SpanData): String =
        span.events
            .flatMap { event ->
                event.attributes
                    .asMap()
                    .entries
                    .map { "${it.key.key}=${it.value}" }
            }.joinToString(separator = "\n")

    private fun exposureWith(decision: GovernanceDecisionExposure) =
        GovernanceExposure(GovernanceExposureState.EVIDENCE_AVAILABLE, listOf(decision))

    private fun authoritativePolicyDecision() =
        GovernanceDecisionExposure(
            kind = DecisionKind.POLICY_DECISION,
            authority = FactAuthority.AUTHORITATIVE_PERSISTED,
            freshness = FactFreshness.HISTORICAL,
            hasVerifiedStreamRange = true,
            recordCompleteness = Completeness.COMPLETE,
            reasonCodeExposure = ReasonCodeExposure.EXPOSED,
        )
}
