package dev.tramai.observability.governance

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer

/**
 * Exports a [GovernanceExposure] as OpenTelemetry span events.
 *
 * ## What this is not
 *
 * Telemetry is a representation of evidence, never evidence of enforcement. Exporting a fact cannot create
 * authority, authorize an operation, prove one executed, or prove one was prevented. Nothing here can change a
 * policy decision, block an invocation or mutate projection state: it is downstream of all of them and writes
 * only to a span.
 *
 * ## Schema conformance
 *
 * Every event is built as a [RuntimeEvent] through [RuntimeEvent.of], which rejects attributes the catalogue does
 * not allow for this event, attributes of the wrong value type, and events missing a required attribute. The
 * catalogue requires the exposure state on every event of this type, so a decision event carries it too: a
 * consumer reading one decision event still knows which result produced it. Building the event rather than
 * putting attributes on the span directly is what makes that a structural guarantee instead of a convention.
 *
 * Attribute names come from [RuntimeAttributes] and the event name from [RuntimeEvents]; no attribute name is
 * assembled from data, and no object is rendered into an attribute. Values are the fixed enum names and, for the
 * recorded decision, the bounded token the projection contract already validated.
 *
 * Export is best-effort and non-authoritative. The OpenTelemetry API does not throw when export fails - the SDK
 * reports and drops - so no catch is added here that would bury a programming defect under a success status.
 *
 * Metrics are deliberately not implemented in this slice. This event is emitted once per query, so counting it
 * would report query volume; presenting that as enforcement, prevention or governance coverage would be a claim
 * the evidence does not support.
 */
public class GovernanceTelemetryExporter(
    private val tracer: Tracer,
) {
    /**
     * Records one export. Deterministic for the same input: identical exposure, identical span name and
     * attributes, in identical order.
     */
    public fun export(exposure: GovernanceExposure) {
        val span = tracer.spanBuilder(RuntimeEvents.GOVERNANCE_EVIDENCE_EXPORTED.name).startSpan()
        try {
            events(exposure).forEach { event -> span.addEvent(event.name, attributesOf(event)) }
        } finally {
            span.end()
        }
    }

    /** The catalogue-validated events this exporter emits, in order. Visible for tests. */
    internal fun events(exposure: GovernanceExposure): List<RuntimeEvent> =
        listOf(stateEvent(exposure)) + exposure.decisions.map { decisionEvent(exposure.state, it) }

    private fun stateEvent(exposure: GovernanceExposure): RuntimeEvent =
        RuntimeEvent.of(RuntimeEvents.GOVERNANCE_EVIDENCE_EXPORTED.name) {
            set(RuntimeAttributes.GOVERNANCE_EXPOSURE_STATE, exposure.state.name)
            // Both sources, independently, and UNKNOWN whenever nothing was read. There is no combined flag.
            set(RuntimeAttributes.GOVERNANCE_REGISTRATION_CURRENCY, exposure.registrationCurrency.name)
            set(RuntimeAttributes.GOVERNANCE_AUDIT_CURRENCY, exposure.auditCurrency.name)
        }

    private fun decisionEvent(
        state: GovernanceExposureState,
        decision: GovernanceDecisionExposure,
    ): RuntimeEvent =
        RuntimeEvent.of(RuntimeEvents.GOVERNANCE_EVIDENCE_EXPORTED.name) {
            set(RuntimeAttributes.GOVERNANCE_EXPOSURE_STATE, state.name)
            set(RuntimeAttributes.GOVERNANCE_DECISION_KIND, decision.kind.name)
            set(RuntimeAttributes.GOVERNANCE_DECISION_AUTHORITY, decision.authority.name)
            set(RuntimeAttributes.GOVERNANCE_DECISION_FRESHNESS, decision.freshness.name)
            set(RuntimeAttributes.GOVERNANCE_DECISION_VERIFIED_RANGE, verifiedRangeOf(decision))
            set(RuntimeAttributes.GOVERNANCE_DECISION_COMPLETENESS, decision.recordCompleteness.name)
            set(RuntimeAttributes.GOVERNANCE_DECISION_REASON_EXPOSURE, decision.reasonCodeExposure.name)
            decision.recordedDecision?.let { set(RuntimeAttributes.GOVERNANCE_DECISION_RECORDED, it) }
        }

    private fun attributesOf(event: RuntimeEvent): Attributes {
        val builder = Attributes.builder()
        // Catalogue declaration order: deterministic, and a key the catalogue does not declare cannot appear.
        event.definition.allowedAttributes.forEach { key ->
            val value = event.attribute(key)
            if (value is String) {
                builder.put(key.name, value)
            }
        }
        return builder.build()
    }

    private fun verifiedRangeOf(decision: GovernanceDecisionExposure): String =
        if (decision.hasVerifiedStreamRange) VERIFIED_RANGE_PRESENT else VERIFIED_RANGE_ABSENT

    private companion object {
        const val VERIFIED_RANGE_PRESENT = "PRESENT"
        const val VERIFIED_RANGE_ABSENT = "ABSENT"
    }
}
