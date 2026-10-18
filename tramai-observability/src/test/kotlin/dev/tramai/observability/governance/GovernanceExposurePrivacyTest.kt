package dev.tramai.observability.governance

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.security.governance.query.Completeness
import dev.tramai.security.governance.query.DecisionKind
import dev.tramai.security.governance.query.FactAuthority
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.ReasonCodeExposure
import dev.tramai.security.governance.query.VerifiedStreamRange
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 0.7.4g E1/E2/E3 — what a safe exposure surface must never carry, proven by canaries rather than by reading.
 *
 * Every value below is seeded into a *real* query DTO through its validating constructor, so it is in the
 * position the exclusion is claimed to cover. The sentinels then have to be absent from all three exposure
 * surfaces: the exposure's own String-valued accessors, its `toString()`, and the rendered OTEL records.
 *
 * The earlier suite asserted two of these absences without seeding the value, so those assertions could not fail.
 * This file seeds first and asserts second.
 */
class GovernanceExposurePrivacyTest {
    private val exporter = InMemorySpanExporter.create()

    private lateinit var provider: SdkTracerProvider
    private lateinit var telemetry: GovernanceTelemetryExporter

    /** Free text, identifiers, hashes and boundary vocabulary the exposure contract declares excluded. */
    private val forbiddenSentinels =
        listOf(
            GovernanceQueryFixtures.FREE_TEXT_ESTABLISHED,
            GovernanceQueryFixtures.CURRENCY_NOTE,
            GovernanceQueryFixtures.BOUNDARY_ID,
            GovernanceQueryFixtures.BOUNDARY_STRENGTH,
            "must not infer text that must never be exported",
            "hash-12",
            "rev-7",
            "eu-west-amsterdam-01",
            "governance-read-model.v1",
            "production",
            "claims",
        )

    @BeforeEach
    fun setUp() {
        provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()
        telemetry = GovernanceTelemetryExporter(provider.get("governance-privacy-test"))
    }

    @AfterEach
    fun tearDown() {
        provider.close()
    }

    @Test
    fun `no forbidden source field reaches any exposure surface`() {
        val exposure =
            GovernanceQueryFixtures.exposureOf(
                GovernanceQueryFixtures.decision(
                    verifiedRange = VerifiedStreamRange(1, 3),
                    completeness = Completeness.COMPLETE,
                ),
                GovernanceQueryFixtures.springAiToolPermissionDecision(),
            )

        telemetry.export(exposure)

        // 1. the exposure's own String-valued accessors
        val accessorValues = stringValuesOf(exposure)
        // 2. its diagnostic rendering
        val diagnostic = exposure.toString() + exposure.decisions.joinToString { it.toString() }
        // 3. the rendered OTEL records
        val rendered = exporter.finishedSpanItems.joinToString(" ") { render(it) }

        assertThat(accessorValues).isNotEmpty()
        forbiddenSentinels.forEach { sentinel ->
            assertThat(accessorValues).describedAs("exposure accessor carrying %s", sentinel).doesNotContain(sentinel)
            assertThat(diagnostic).describedAs("toString carrying %s", sentinel).doesNotContain(sentinel)
            assertThat(rendered).describedAs("telemetry carrying %s", sentinel).doesNotContain(sentinel)
        }
    }

    @Test
    fun `a credential-shaped reason code cannot be carried as withheld`() {
        // The query contract makes "a reason code is present" and "the reason code is exposed" the same claim, so
        // a withheld code cannot smuggle text past the exposure mapper's allowlist.
        assertThatThrownBy {
            GovernanceQueryFixtures.decision().copy(
                reasonCode = "sk-live-example123",
                reasonExposure = ReasonCodeExposure.WITHHELD,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a credential-shaped reason code is not reachable through an exposed fact`() {
        val exposed =
            GovernanceQueryFixtures.decision().copy(
                reasonCode = "sk-live-example123",
                reasonExposure = ReasonCodeExposure.EXPOSED,
            )

        telemetry.export(GovernanceQueryFixtures.exposureOf(exposed))

        val rendered = render(exporter.finishedSpanItems.single())
        assertThat(rendered).doesNotContain("sk-live-example123")
        assertThat(rendered).doesNotContain("sk-live")
        // The reason-code channel is represented categorically, which is all the allowlist permits.
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_REASON_EXPOSURE.name)
        assertThat(rendered).contains(ReasonCodeExposure.EXPOSED.name)
    }

    @Test
    fun `no weaker authority class is upgraded by rendering it`() {
        // Every (family, authority) pair the query contract permits on a recorded decision, exported as itself.
        val permitted =
            listOf(
                DecisionKind.POLICY_DECISION to FactAuthority.POLICY_AUTHORIZATION_RECORD,
                DecisionKind.TOOL_PERMISSION to FactAuthority.POLICY_AUTHORIZATION_RECORD,
                DecisionKind.PROVIDER_ROUTE to FactAuthority.PRODUCER_DECLARED,
            )

        permitted.forEach { (kind, authority) ->
            exporter.reset()

            telemetry.export(
                GovernanceQueryFixtures.exposureOf(GovernanceQueryFixtures.decision(kind = kind, authority = authority)),
            )

            val rendered = render(exporter.finishedSpanItems.single())
            assertThat(rendered).describedAs("rendering %s/%s", kind, authority).contains(authority.name)
            assertThat(rendered).doesNotContain(FactAuthority.AUTHORITATIVE_PERSISTED.name)
        }
    }

    @Test
    fun `a family the contract confines to a declared authority cannot be built with a stronger one`() {
        // The upgrade refusal lives in the query DTO, so it holds for every consumer of the exposure mapper, not
        // just for the ones that go through a live read.
        assertThatThrownBy {
            GovernanceQueryFixtures.decision(
                kind = DecisionKind.PROVIDER_ROUTE,
                authority = FactAuthority.POLICY_AUTHORIZATION_RECORD,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `every unavailable state keeps its own identity and carries no decision evidence`() {
        val unavailable =
            listOf(
                GovernanceQueryResult.NotMaterialized to GovernanceExposureState.NOT_MATERIALIZED,
                GovernanceQueryResult.UnsupportedFormat("governance-read-model.v99") to
                    GovernanceExposureState.UNSUPPORTED_FORMAT,
                GovernanceQueryResult.InvalidStoredProjection("stored projection failed verification") to
                    GovernanceExposureState.INVALID_STORED_PROJECTION,
            )

        unavailable.forEach { (result, expected) ->
            exporter.reset()

            val exposure = GovernanceExposure.from(result)
            telemetry.export(exposure)

            assertThat(exposure.state).describedAs("mapped state").isEqualTo(expected)
            assertThat(exposure.decisions).isEmpty()

            val span = exporter.finishedSpanItems.single()
            val rendered = render(span)
            assertThat(rendered).contains(expected.name)
            // One event only: the state. No decision event, so no authority, kind or completeness claim.
            assertThat(span.events).hasSize(1)
            assertThat(rendered).doesNotContain(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY.name)
            assertThat(rendered).doesNotContain(RuntimeAttributes.GOVERNANCE_DECISION_KIND.name)
            assertThat(rendered).doesNotContain(RuntimeAttributes.GOVERNANCE_DECISION_COMPLETENESS.name)
            assertThat(rendered).doesNotContain(Completeness.COMPLETE.name)
            assertThat(rendered).doesNotContain(FactAuthority.AUTHORITATIVE_PERSISTED.name)
        }
    }

    private fun render(span: SpanData): String =
        buildString {
            append(span.name).append(' ')
            span.attributes.forEach { key, value -> append(key.key).append('=').append(value).append(' ') }
            span.events.forEach { event ->
                append(event.name).append(' ')
                event.attributes.forEach { key, value ->
                    append(key.key).append('=').append(value).append(' ')
                }
            }
        }

    /** Every String a caller could read off the exposure through a no-argument accessor. */
    private fun stringValuesOf(value: Any): List<String> =
        value::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == String::class.java }
            .mapNotNull { method ->
                runCatching { method.invoke(value) as String? }.getOrNull()
            } +
            value::class.java.methods
                .filter { it.parameterCount == 0 && it.name.startsWith("get") }
                .mapNotNull { method -> runCatching { method.invoke(value) }.getOrNull() }
                .filterIsInstance<Enum<*>>()
                .map { it.name }
}
