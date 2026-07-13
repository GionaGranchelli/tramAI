package dev.tramai.security.evidence

import dev.tramai.security.audit.AuditEvent

/** Allowlist of metadata keys that may appear in a runtime-evidence.v1 policy.decision record. */
internal val ALLOWED_METADATA_KEYS = setOf(
    "providerName",
    "modelName",
    "toolName",
    "classification",
    "classificationSource",
    "riskLevel",
    "fallbackProviderName",
    "attr_cacheReuse",
    "attr_fallbackReason",
)

/**
 * Converts policy [AuditEvent]s into [RuntimeEvidenceRecord]s following
 * the runtime-evidence.v1 schema.
 *
 * The exporter applies its own metadata allowlist as a defensive boundary:
 * only known-safe metadata keys are carried into evidence records, even
 * though the audit emitter already filters unsafe attributes.
 */
class PolicyDecisionRuntimeEvidenceExporter {

    /**
     * Transforms policy audit events into runtime evidence records.
     *
     * Only events with decision values ALLOW, DENY, or REQUIRE_APPROVAL
     * are exported. All other events are silently skipped.
     */
    fun export(events: List<AuditEvent>): List<RuntimeEvidenceRecord> =
        events
            .filter { it.decision in ALLOWED_DECISIONS }
            .map { it.toRuntimeEvidenceRecord() }

    private companion object {
        private val ALLOWED_DECISIONS = setOf("ALLOW", "DENY", "REQUIRE_APPROVAL")
    }
}

/**
 * Converts a single policy [AuditEvent] to a [RuntimeEvidenceRecord].
 *
 * Applies exporter-level metadata allowlist filtering as a defensive
 * boundary: only keys in [PolicyDecisionRuntimeEvidenceExporter]'s
 * ALLOWED_METADATA_KEYS are carried through.
 */
internal fun AuditEvent.toRuntimeEvidenceRecord(): RuntimeEvidenceRecord {
    val safeMetadata = safeRuntimeEvidenceMetadata()
    val payloadDigest = computePayloadDigest(safeMetadata)

    return RuntimeEvidenceRecord(
        eventId = eventId,
        eventType = "policy.decision",
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        actor = actor,
        createdAt = timestamp,
        source = RuntimeEvidenceSource(
            component = "policy-engine",
            module = policyVersion,
        ),
        decision = RuntimeEvidenceDecision(
            kind = decision,
            reasonCode = reasonCode,
        ),
        digests = RuntimeEvidenceDigests(
            subjectDigest = EvidenceDigest.sha256(auditStreamId),
            payloadDigest = payloadDigest,
        ),
        metadata = safeMetadata,
    )
}

/**
 * Filters audit event metadata to only include allowlisted keys.
 *
 * This is a defensive boundary: the exporter does not trust that all
 * [AuditEvent] sources have already filtered unsafe attributes. Only
 * keys matching the safe set defined by the policy audit emitter are
 * carried into the evidence record.
 */
internal fun AuditEvent.safeRuntimeEvidenceMetadata(): Map<String, String> =
    metadata.filterKeys { it in ALLOWED_METADATA_KEYS }

/**
 * Computes a deterministic SHA-256 digest of the decision payload.
 *
 * The canonical form uses a JSON-style structure with proper string
 * escaping, making the digest unambiguous regardless of field values.
 *
 * Stable fields included (volatile fields like eventId and timestamp
 * are excluded so the digest reflects the decision shape, not the emit):
 * - eventType
 * - decision
 * - reasonCode (if present)
 * - source.component
 * - source.module (if present)
 * - metadata (filtered, sorted)
 */
internal fun AuditEvent.computePayloadDigest(safeMetadata: Map<String, String>): String {
    val canonical = CanonicalDigestBuilder().apply {
        appendField("eventType", "policy.decision")
        appendField("decision", decision)
        appendNullableField("reasonCode", reasonCode)
        appendField("sourceComponent", "policy-engine")
        appendNullableField("sourceModule", policyVersion)
        appendMetadataField("metadata", safeMetadata)
    }.build()
    return EvidenceDigest.sha256(canonical)
}
