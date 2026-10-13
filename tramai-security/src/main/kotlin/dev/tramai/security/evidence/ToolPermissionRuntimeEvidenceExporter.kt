package dev.tramai.security.evidence

import dev.tramai.security.audit.AuditEvent

/**
 * The set of enforcement points that represent tool permission decisions.
 *
 * Events with any of these enforcement points belong to the `tool.permission`
 * evidence family, not `policy.decision`.
 */
internal val TOOL_ENFORCEMENT_POINTS =
    setOf(
        "BEFORE_TOOL_EXPOSURE",
        "BEFORE_TOOL_EXECUTION",
        "BEFORE_TOOL_RESULT_REINJECTION",
    )

/**
 * Allowlisted metadata keys that may appear in a runtime-evidence.v1
 * tool.permission record.
 */
internal val TOOL_PERMISSION_ALLOWED_METADATA_KEYS =
    setOf(
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
            .filter { it.decision in ALLOWED_DECISIONS }
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

    val safeMetadata =
        mutableMapOf<String, String>().apply {
            // Preserve enforcementPoint from the audit-event top-level field
            put("enforcementPoint", enforcementPoint)
            // Filter remaining metadata to the allowlist
            putAll(metadata.filterKeys { it in TOOL_PERMISSION_ALLOWED_METADATA_KEYS && it != "enforcementPoint" })
        }

    // G1b: the framework-bound governed identity is overlaid AFTER the family allowlist, so the
    // exfiltration allowlist keeps its purpose while the reserved identity keys still reach the
    // record. The overlay precedes the digest because the digest must advertise the identity the
    // record carries; an overlay computed afterwards would leave a record whose digest does not
    // bind the identity it shows.
    //
    // Fail closed, never downgrade: a partial tuple, or a complete tuple without the canonical
    // run id, refuses here instead of being read as legacy evidence.
    val attribution = metadata.filterKeys { it in RuntimeEvidenceAttribution.metadataKeys }
    RuntimeEvidenceAttribution.validate(workflowRunId, attribution)
    val attributedMetadata = RuntimeEvidenceAttribution.merge(safeMetadata, attribution)

    val payloadDigest = computeToolPermissionPayloadDigest(attributedMetadata)

    return RuntimeEvidenceRecord(
        eventId = eventId,
        eventType = "tool.permission",
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        actor = actor,
        createdAt = timestamp,
        source =
            RuntimeEvidenceSource(
                component = "policy-engine",
                module = policyVersion,
            ),
        decision =
            RuntimeEvidenceDecision(
                kind = decision,
                reasonCode = reasonCode,
            ),
        digests =
            RuntimeEvidenceDigests(
                subjectDigest = EvidenceDigest.sha256(auditStreamId),
                payloadDigest = payloadDigest,
            ),
        metadata = attributedMetadata,
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
 * - workflowRunId — **only when the record is attributed** (see below)
 * - metadata (filtered, sorted) — including the reserved governed-identity keys when the event is
 *   attributed, so the digest binds the identity this record advertises
 *
 * The canonical run id is part of the complete governed identity, so an attributed record binds it
 * here rather than relying on `subjectDigest` alone: `subjectDigest` is `sha256(auditStreamId)`, and
 * `auditStreamId` only equals the run id because [DefaultAuditStreamIdResolver] happens to return it.
 * A deployment that installs its own [AuditStreamIdResolver] would keep the audit stream and the run
 * distinct, and the run id would then be bound by nothing.
 *
 * A **legacy** record (zero reserved keys) gains no run-id term, so its digest stays byte-identical
 * to the one it carried before governed attribution existed. Attribution is atomic — zero keys is
 * legacy, a partial tuple is refused — so this condition is a binary state, not a heuristic.
 *
 * Raw tool arguments are never included in the canonical payload.
 */
internal fun AuditEvent.computeToolPermissionPayloadDigest(safeMetadata: Map<String, String>): String {
    val attributed = safeMetadata.keys.any { it in RuntimeEvidenceAttribution.metadataKeys }
    val canonical =
        CanonicalDigestBuilder()
            .apply {
                appendField("eventType", "tool.permission")
                appendField("decision", decision)
                appendNullableField("reasonCode", reasonCode)
                appendField("sourceComponent", "policy-engine")
                appendNullableField("sourceModule", policyVersion)
                if (attributed) {
                    appendNullableField("workflowRunId", workflowRunId)
                }
                appendMetadataField("metadata", safeMetadata)
            }.build()
    return EvidenceDigest.sha256(canonical)
}
