package dev.tramai.observability.governance

import dev.tramai.core.observation.event.RuntimeAttributeKey
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEventCatalogue
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.security.governance.query.Completeness
import dev.tramai.security.governance.query.DecisionKind
import dev.tramai.security.governance.query.FactAuthority
import dev.tramai.security.governance.query.FactFreshness
import dev.tramai.security.governance.query.GovernanceQueryResult
import dev.tramai.security.governance.query.ReasonCodeExposure
import dev.tramai.security.governance.query.SourceCurrency
import dev.tramai.security.governance.query.VerifiedStreamRange
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test

/**
 * 0.7.4f phases 3-4 - qualification against a real OpenTelemetry export.
 *
 * Every assertion reads what the SDK's in-memory exporter actually received: the finished [SpanData] and the
 * attributes rendered from its events, not an intermediate map the adapter built. Every exposure comes from
 * [GovernanceExposure.from] over real query DTOs, which is the only construction path a consumer has.
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
        telemetry.export(
            GovernanceQueryFixtures.exposureOf(
                GovernanceQueryFixtures.decision(
                    verifiedRange = VerifiedStreamRange(1, 3),
                    completeness = Completeness.COMPLETE,
                    recordedDecision = "DENY",
                ),
            ),
        )

        val span = exporter.finishedSpanItems.single()
        val rendered = render(span)

        assertThat(span.name).isEqualTo("tramai.governance.evidence.exported")
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_EXPOSURE_STATE.name)
        assertThat(rendered).contains(GovernanceExposureState.EVIDENCE_AVAILABLE.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_KIND.name)
        assertThat(rendered).contains(DecisionKind.POLICY_DECISION.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY.name)
        assertThat(rendered).contains(FactAuthority.POLICY_AUTHORIZATION_RECORD.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_FRESHNESS.name)
        assertThat(rendered).contains(FactFreshness.HISTORICAL.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_VERIFIED_RANGE.name)
        assertThat(rendered).contains("PRESENT")
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_COMPLETENESS.name)
        assertThat(rendered).contains(Completeness.COMPLETE.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_REASON_EXPOSURE.name)
        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_RECORDED.name)
        assertThat(rendered).contains("DENY")
    }

    @Test
    fun `a withheld reason code cannot reach telemetry`() {
        // The exposure carries the exposure state and no field for the code text, and the catalogue declares no
        // attribute that could hold one - so a withheld code cannot be re-opened here even by a future adapter.
        telemetry.export(GovernanceQueryFixtures.exposureOf(GovernanceQueryFixtures.withheldReason()))

        val rendered = render(exporter.finishedSpanItems.single())

        assertThat(rendered).contains(ReasonCodeExposure.WITHHELD.name)
        assertThat(rendered).doesNotContain("policy_allowed")
        assertThat(rendered).doesNotContain("tramai.governance.reason_code")
    }

    @Test
    fun `a non-authoritative classification is exported as itself and never upgraded`() {
        // Found by the mutation campaign: forcing the authority attribute would have gone unnoticed because the
        // other fixtures already carry a recorded authorization. Evidence of any weaker class must render as
        // itself, so an upgrade is impossible to smuggle in.
        telemetry.export(
            GovernanceQueryFixtures.exposureOf(GovernanceQueryFixtures.unobserved(DecisionKind.TOOL_PERMISSION)),
        )

        val rendered = render(exporter.finishedSpanItems.single())

        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY.name)
        assertThat(rendered).contains(FactAuthority.NOT_OBSERVED.name)
        assertThat(rendered).doesNotContain(FactAuthority.AUTHORITATIVE_PERSISTED.name)
        assertThat(rendered).doesNotContain(FactAuthority.POLICY_AUTHORIZATION_RECORD.name)
    }

    @Test
    fun `spring ai boundary evidence is exported without an enforcement claim`() {
        // XR1 end to end from a real query result. What the Spring AI external-runtime path establishes is a
        // policy authorization record from the registered boundary - not proof that TramAI ran the workflow, and
        // not proof the call passed through the boundary, whose declared strength is therefore never exported.
        telemetry.export(
            GovernanceQueryFixtures.exposureOf(GovernanceQueryFixtures.springAiToolPermissionDecision()),
        )

        val rendered = render(exporter.finishedSpanItems.single())

        assertThat(rendered).contains(FactAuthority.POLICY_AUTHORIZATION_RECORD.name)
        assertThat(rendered).doesNotContain(FactAuthority.AUTHORITATIVE_PERSISTED.name)
        assertThat(rendered).contains("ABSENT")
        assertThat(rendered).contains(Completeness.GAPS_PRESENT.name)
        assertThat(rendered).contains("DENY")
        assertThat(rendered).doesNotContain(GovernanceQueryFixtures.BOUNDARY_ID)
        assertThat(rendered).doesNotContain(GovernanceQueryFixtures.BOUNDARY_STRENGTH)
        assertThat(rendered).doesNotContain(GovernanceQueryFixtures.FREE_TEXT_ESTABLISHED)
    }

    @Test
    fun `both source currencies are exported independently and never collapsed`() {
        // Registration and audit are separate sources. A current registration with an unobserved audit head is
        // exactly one CURRENT and one UNKNOWN: no combined freshness flag exists to report.
        telemetry.export(GovernanceQueryFixtures.exposureOfCurrencies(SourceCurrency.CURRENT, SourceCurrency.UNKNOWN))

        val attributes =
            exporter.finishedSpanItems
                .single()
                .events
                .first()
                .attributes
                .asMap()
                .mapKeys { it.key.key }

        assertThat(attributes[RuntimeAttributes.GOVERNANCE_REGISTRATION_CURRENCY.name]).isEqualTo("CURRENT")
        assertThat(attributes[RuntimeAttributes.GOVERNANCE_AUDIT_CURRENCY.name]).isEqualTo("UNKNOWN")

        // The stale case is reported as stale, not smoothed into a single currency answer.
        exporter.reset()
        telemetry.export(GovernanceQueryFixtures.exposureOfCurrencies(SourceCurrency.STALE, SourceCurrency.CURRENT))
        val stale =
            exporter.finishedSpanItems
                .single()
                .events
                .first()
                .attributes
                .asMap()
                .mapKeys { it.key.key }
        assertThat(stale[RuntimeAttributes.GOVERNANCE_REGISTRATION_CURRENCY.name]).isEqualTo("STALE")
        assertThat(stale[RuntimeAttributes.GOVERNANCE_AUDIT_CURRENCY.name]).isEqualTo("CURRENT")
    }

    @Test
    fun `an unread projection emits the state and no decision evidence`() {
        telemetry.export(GovernanceExposure.from(GovernanceQueryResult.NotMaterialized))

        val span = exporter.finishedSpanItems.single()
        val rendered = render(span)

        assertThat(rendered).contains(GovernanceExposureState.NOT_MATERIALIZED.name)
        assertThat(rendered).doesNotContain(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY.name)
        assertThat(rendered).doesNotContain(FactAuthority.AUTHORITATIVE_PERSISTED.name)
        assertThat(span.events).hasSize(1)
        // Nothing was read, so neither source can claim currency.
        assertThat(rendered).doesNotContain(SourceCurrency.CURRENT.name)
        assertThat(rendered).contains(SourceCurrency.UNKNOWN.name)
    }

    @Test
    fun `equivalent exposures render identically`() {
        telemetry.export(GovernanceQueryFixtures.exposureOf(GovernanceQueryFixtures.decision()))
        telemetry.export(GovernanceQueryFixtures.exposureOf(GovernanceQueryFixtures.decision()))

        val rendered = exporter.finishedSpanItems.map { render(it) }
        assertThat(rendered).hasSize(2)
        assertThat(rendered[0]).isEqualTo(rendered[1])
    }

    @Test
    fun `every emitted event conforms to the catalogue schema`() {
        val catalogueDefinition = RuntimeEventCatalogue.event(RuntimeEvents.GOVERNANCE_EVIDENCE_EXPORTED.name)
        val allowed = catalogueDefinition.allowedAttributes.map { it.name }.toSet()
        val required = catalogueDefinition.requiredAttributes.map { it.name }.toSet()

        telemetry.export(
            GovernanceQueryFixtures.exposureOf(
                GovernanceQueryFixtures.decision(),
                GovernanceQueryFixtures.decision(kind = DecisionKind.TOOL_PERMISSION),
            ),
        )

        val events = exporter.finishedSpanItems.single().events
        assertThat(events).hasSize(3)
        events.forEach { event ->
            val keys =
                event.attributes
                    .asMap()
                    .keys
                    .map { it.key }
                    .toSet()
            // No attribute outside the catalogue for this event type, and no attribute name built from data.
            assertThat(allowed).containsAll(keys)
            // The catalogue requires the exposure state on every event, decision events included.
            assertThat(keys).containsAll(required)
        }
    }

    @Test
    fun `an attribute the catalogue does not declare is rejected, not exported`() {
        // The exporter builds RuntimeEvent rather than putting attributes on the span, so this is the rule it
        // runs under: an unregistered key cannot be emitted even if production code asked for one.
        assertThatThrownBy {
            RuntimeEvent.of(RuntimeEvents.GOVERNANCE_EVIDENCE_EXPORTED.name) {
                set(RuntimeAttributes.GOVERNANCE_EXPOSURE_STATE, GovernanceExposureState.EVIDENCE_AVAILABLE.name)
                set(RuntimeAttributeKey("tramai.governance.debug", String::class), "exposure.toString()")
            }
        }.hasMessageContaining("is not allowed for event")
    }

    @Test
    fun `a consumer cannot implement the exposure types`() {
        // Kotlin `internal` is not a JVM visibility, and a public interface can simply be implemented by any
        // consumer - the previous review round found this documented guarantee was stronger than the code. The
        // types are now sealed with private implementations, so a Kotlin consumer in another module cannot supply
        // its own. Asserted, not described.
        assertThat(GovernanceExposure::class.java.isInterface).isTrue()
        assertThat(GovernanceDecisionExposure::class.java.isInterface).isTrue()
        // Read the JVM `PermittedSubclasses` attribute rather than `KClass.isSealed`: the Kotlin reflection
        // property needs kotlin-reflect, which is not on this module's test classpath, and the compiler says
        // so (NO_REFLECTION_IN_CLASS_PATH). The JVM attribute asserts the same guarantee without the
        // dependency: a sealed interface has permitted subclasses, an open one does not.
        assertThat(GovernanceExposure::class.java.permittedSubclasses).isNotEmpty()
        assertThat(GovernanceDecisionExposure::class.java.permittedSubclasses).isNotEmpty()
        assertThat(GovernanceExposure::class.java.constructors).isEmpty()
        assertThat(GovernanceDecisionExposure::class.java.constructors).isEmpty()

        val exposureMethods = GovernanceExposure::class.java.methods.map { it.name }
        val decisionMethods = GovernanceDecisionExposure::class.java.methods.map { it.name }
        assertThat(exposureMethods).doesNotContain("copy")
        assertThat(decisionMethods).doesNotContain("copy")

        // The implementation is package-private, so a Java caller outside this package cannot name it either.
        val implementation = Class.forName("dev.tramai.observability.governance.DefaultGovernanceExposure")
        assertThat(
            java.lang.reflect.Modifier
                .isPublic(implementation.modifiers),
        ).isFalse()

        val companionMethods = GovernanceExposure.Companion::class.java.methods.map { it.name }
        assertThat(companionMethods).contains("from")
        assertThat(companionMethods).doesNotContain("of")
    }

    @Test
    fun `exported attributes are caller-attested, and this adapter does not authenticate them`() {
        // The documented limit, pinned so it cannot silently drift into a stronger claim. `GovernanceQueryResult`
        // and its DTOs are publicly constructible, so a caller can hand this adapter a projection no query
        // produced. The mapper reports that claim faithfully and upgrades nothing - which is exactly why no
        // consumer may read these attributes as authenticated enforcement evidence.
        telemetry.export(
            GovernanceExposure.from(
                GovernanceQueryResult.Found(
                    GovernanceQueryFixtures.view(
                        GovernanceQueryFixtures.decision(
                            verifiedRange = VerifiedStreamRange(1, 3),
                            completeness = Completeness.COMPLETE,
                        ),
                    ),
                ),
            ),
        )

        val rendered = render(exporter.finishedSpanItems.single())

        assertThat(rendered).contains(GovernanceExposureState.EVIDENCE_AVAILABLE.name)
        assertThat(rendered).contains(Completeness.COMPLETE.name)
        // Nothing in the export claims authentication: no attribute exists that could carry such a claim.
        assertThat(rendered).doesNotContain("AUTHENTICATED")
        assertThat(rendered).doesNotContain("ATTESTED_BY")
    }

    @Test
    fun `a recorded decision outside the approved vocabulary is not disclosed`() {
        // The query DTO only guarantees a syntactically safe token, and a credential-shaped string satisfies it.
        // Only the vocabulary the audit readers already recognize is disclosed; anything else becomes a fixed
        // classification, so the value itself never reaches telemetry.
        val credentialShaped = "sk-live-example123"
        telemetry.export(
            GovernanceQueryFixtures.exposureOf(GovernanceQueryFixtures.decision(recordedDecision = credentialShaped)),
        )

        val rendered = render(exporter.finishedSpanItems.single())

        assertThat(rendered).contains(RuntimeAttributes.GOVERNANCE_DECISION_RECORDED.name)
        assertThat(rendered).contains("UNRECOGNIZED")
        assertThat(rendered).doesNotContain(credentialShaped)
        assertThat(rendered).doesNotContain("sk-live")
    }

    @Test
    fun `the approved decision vocabulary is disclosed as itself`() {
        listOf("ALLOW", "DENY", "REQUIRE_APPROVAL").forEach { token ->
            exporter.reset()
            telemetry.export(
                GovernanceQueryFixtures.exposureOf(GovernanceQueryFixtures.decision(recordedDecision = token)),
            )
            val rendered = render(exporter.finishedSpanItems.single())
            assertThat(rendered).contains(token)
            assertThat(rendered).doesNotContain("UNRECOGNIZED")
        }
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
}
