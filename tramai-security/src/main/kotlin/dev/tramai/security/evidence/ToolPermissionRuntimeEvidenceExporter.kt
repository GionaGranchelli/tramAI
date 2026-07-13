package dev.tramai.security.evidence

import dev.tramai.security.audit.AuditEvent

/**
 * The set of enforcement points that represent tool permission decisions.
 *
 * Events with any of these enforcement points belong to the `tool.permission`
 * evidence family, not `policy.decision`.
 */
internal val TOOL_ENFORCEMENT_POINTS = setOf(
    "BEFORE_TOOL_EXPOSURE",
    "BEFORE_TOOL_EXECUTION",
    "BEFORE_TOOL_RESULT_REINJECTION",
)

/**
 * Allowlisted metadata keys that may appear in a runtime-evidence.v1
 * tool.permission record.
 */
internal val TOOL_PERMISSION_ALLOWED_METADATA_KEYS = setOf(
    "toolName",
    "enforcementPoint",
    "riskLevel",
    "classification",
    "classificationSource",
)

/**
 * Converts tool enforcement [AuditEvent]s into [RuntimeEvidenceRecord]s
 * following the runtime-evidence.v1 schema.
 *
 * Only events whose enforcement point is one of the three tool enforcement
 * points are accepted. All other events are silently skipped.
 *
 * A recognised tool enforcement event missing [AuditEvent.toolName] fails
 * closed rather than silently falling back to policy.decision.
 */
class ToolPermissionRuntimeEvidenceExporter {

    fun export(events: List<AuditEvent>): List<RuntimeEvidenceRecord> =
        events
            .filter { it.enforcementPoint in TOOL_ENFORCEMENT_POINTS }
            .map { it.toToolPermissionRuntimeEvidenceRecord() }

    private companion object {
        private val ALLOWED_DECISIONS = setOf("ALLOW", "DENY", "REQUIRE_APPROVAL")
    }
}

internal fun AuditEvent.toToolPermissionRuntimeEvidenceRecord(): RuntimeEvidenceRecord {
    val toolName = metadata["toolName"]
    require(!toolName.isNullOrBlank()) {
        "Tool enforcement event $eventId is missing required metadata.toolName"
    }

    val safeMetadata = metadata.filterKeys { it in TOOL_PERMISSION_ALLOWED_METADATA_KEYS }
    val payloadDigest = computeToolPermissionPayloadDigest(safeMetadata)

    return RuntimeEvidenceRecord(
        eventId = eventId,
        eventType = "tool.permission",
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
 * Computes a deterministic SHA-256 digest of the tool.permission payload.
 *
 * Stable fields included:
 * - eventType (always "tool.permission")
 * - decision
 * - reasonCode (if present)
 * - source.component (always "policy-engine")
 * - source.module (if present)
 * - metadata (filtered, sorted)
 *
 * Raw tool arguments are never included in the canonical payload.
 */
internal fun AuditEvent.computeToolPermissionPayloadDigest(
    safeMetadata: Map<String, String>,
): String {
    val canonical = CanonicalDigestBuilder().apply {
        appendField("eventType", "tool.permission")
        appendField("decision", decision)
        appendNullableField("reasonCode", reasonCode)
        appendField("sourceComponent", "policy-engine")
        appendNullableField("sourceModule", policyVersion)
        appendMetadataField("metadata", safeMetadata)
    }.build()
    return EvidenceDigest.sha256(canonical)
}
