package dev.tramai.spring.sovereign.ops.evidence

import dev.tramai.security.evidence.EvidenceDigest
import dev.tramai.security.evidence.RuntimeEvidenceDecision
import dev.tramai.security.evidence.RuntimeEvidenceDigests
import dev.tramai.security.evidence.RuntimeEvidenceRecord
import dev.tramai.security.evidence.RuntimeEvidenceSource
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord

/**
 * Converts approval decision outbox records into [RuntimeEvidenceRecord]s
 * following the runtime-evidence.v1 schema.
 *
 * Only records with aggregate type "approval" and operations
 * "approval-approved.*" or "approval-denied.*" are exported.
 * All other records are silently skipped.
 */
class ApprovalDecisionRuntimeEvidenceExporter {

    /**
     * Transforms approval outbox records into runtime evidence records.
     *
     * @param records approval audit outbox records
     * @return runtime evidence records for approved/denied decisions
     */
    fun export(records: List<SovereignOpsAuditOutboxRecord>): List<RuntimeEvidenceRecord> =
        records
            .filter { it.aggregateType == "approval" }
            .filter { record ->
                record.operation.startsWith("approval-approved.") ||
                    record.operation.startsWith("approval-denied.")
            }
            .map { it.toRuntimeEvidenceRecord() }
}

private fun SovereignOpsAuditOutboxRecord.toDecisionKind(): String =
    when (approvalStatus) {
        "APPROVED" -> "APPROVED"
        "DENIED" -> "DENIED"
        else -> error(
            "Unsupported approval status for runtime evidence: $approvalStatus. " +
                "Only APPROVED and DENIED are exportable.",
        )
    }

private fun SovereignOpsAuditOutboxRecord.toReasonCode(): String =
    when {
        operation.startsWith("approval-approved.") -> "approval-approved"
        operation.startsWith("approval-denied.") -> "approval-denied"
        else -> "approval-decision"
    }

/**
 * Converts a single outbox record to a [RuntimeEvidenceRecord].
 *
 * Mapping follows docs/evidence/runtime-evidence-export-model.md:
 *
 * | Runtime evidence field     | Source                                          |
 * |---------------------------|-------------------------------------------------|
 * | schemaVersion             | "runtime-evidence.v1"                           |
 * | eventId                   | outboxId                                        |
 * | eventType                 | "approval.decision"                             |
 * | workflowRunId             | workflowRunId                                   |
 * | correlationId             | correlationId                                   |
 * | actor                     | actor                                           |
 * | createdAt                 | createdAt                                       |
 * | source.component          | "approval-control-plane"                        |
 * | source.module             | aggregateType ("approval")                      |
 * | decision.kind             | approvalStatus (APPROVED / DENIED)              |
 * | decision.reasonCode       | "approval-approved" / "approval-denied"         |
 * | digests.subjectDigest     | aggregateIdDigest (already sha256:hex)          |
 * | digests.payloadDigest     | SHA-256 of canonical payload (filtered fields)  |
 * | metadata.approvalVersion  | approvalVersion?.toString()                     |
 * | metadata.reasonDigest     | reasonDigest (already sha256:hex)               |
 * | metadata.reasonLength     | reasonLength.toString()                         |
 * | metadata.outboxStatus     | status.name                                     |
 * | metadata.eventKeyDigest   | SHA-256 of eventKey (not raw eventKey)          |
 */
private fun SovereignOpsAuditOutboxRecord.toRuntimeEvidenceRecord(): RuntimeEvidenceRecord {
    val safeMetadata = buildSafeMetadata()
    return RuntimeEvidenceRecord(
        eventId = outboxId,
        eventType = "approval.decision",
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        actor = actor,
        createdAt = createdAt,
        source = RuntimeEvidenceSource(
            component = "approval-control-plane",
            module = aggregateType,
        ),
        decision = RuntimeEvidenceDecision(
            kind = toDecisionKind(),
            reasonCode = toReasonCode(),
        ),
        digests = RuntimeEvidenceDigests(
            subjectDigest = aggregateIdDigest,
            payloadDigest = computePayloadDigest(safeMetadata),
        ),
        metadata = safeMetadata,
    )
}

/**
 * Builds safe metadata from the outbox record.
 *
 * Never includes raw approval IDs, raw event keys, raw comments,
 * approval tokens, resume tokens, or replay envelopes.
 */
private fun SovereignOpsAuditOutboxRecord.buildSafeMetadata(): Map<String, String> {
    val map = mutableMapOf<String, String>()
    approvalVersion?.let { map["approvalVersion"] = it.toString() }
    map["reasonDigest"] = reasonDigest
    map["reasonLength"] = reasonLength.toString()
    map["outboxStatus"] = status.name
    // eventKey contains the raw approval ID (e.g. "approval-approved.<id>"),
    // so we export only its digest.
    map["eventKeyDigest"] = EvidenceDigest.sha256(eventKey)
    return map
}

/**
 * Computes a deterministic SHA-256 digest over the filtered decision payload.
 *
 * Stable fields (volatile fields like outboxId and createdAt are excluded):
 * - eventType
 * - decision.kind
 * - decision.reasonCode
 * - source.component
 * - source.module
 * - metadata (filtered, sorted)
 */
private fun SovereignOpsAuditOutboxRecord.computePayloadDigest(
    safeMetadata: Map<String, String>,
): String {
    val canonical = ApprovalCanonicalDigestBuilder().apply {
        appendField("eventType", "approval.decision")
        appendField("decision", toDecisionKind())
        appendNullableField("reasonCode", toReasonCode())
        appendField("sourceComponent", "approval-control-plane")
        appendField("sourceModule", aggregateType)
        appendMetadataField("metadata", safeMetadata)
    }.build()
    return EvidenceDigest.sha256(canonical)
}

/**
 * Minimal canonical JSON builder used exclusively for payload digest
 * computation in the approval evidence exporter.
 */
private class ApprovalCanonicalDigestBuilder {
    private val sb = StringBuilder("{")

    fun appendField(name: String, value: String) {
        appendComma()
        appendJsonString(name)
        sb.append(':')
        appendJsonString(value)
    }

    fun appendNullableField(name: String, value: String?) {
        appendComma()
        appendJsonString(name)
        sb.append(':')
        if (value != null) {
            appendJsonString(value)
        } else {
            sb.append("null")
        }
    }

    fun appendMetadataField(name: String, value: Map<String, String>) {
        appendComma()
        appendJsonString(name)
        sb.append(':')
        sb.append('{')
        var first = true
        for ((k, v) in value.toSortedMap()) {
            if (!first) sb.append(',')
            appendJsonString(k)
            sb.append(':')
            appendJsonString(v)
            first = false
        }
        sb.append('}')
    }

    fun build(): String {
        sb.append('}')
        return sb.toString()
    }

    private fun appendComma() {
        if (sb.length > 1) sb.append(',')
    }

    private fun appendJsonString(value: String) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    when {
                        c.code in 0xD800..0xDFFF || c < ' ' -> {
                            sb.append("\\u")
                            sb.append(c.code.toString(16).padStart(4, '0'))
                        }
                        else -> sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }
}
