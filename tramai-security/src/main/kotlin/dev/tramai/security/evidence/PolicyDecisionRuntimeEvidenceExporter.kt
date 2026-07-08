package dev.tramai.security.evidence

import dev.tramai.security.audit.AuditEvent
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Converts policy [AuditEvent]s into [RuntimeEvidenceRecord]s following
 * the runtime-evidence.v1 schema.
 */
class PolicyDecisionRuntimeEvidenceExporter(
    private val clock: Clock = Clock.systemUTC(),
) {
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

        private val DIGEST_REGEX = Regex("^sha256:[0-9a-f]{64}$")
    }
}

/**
 * Converts a single policy [AuditEvent] to a [RuntimeEvidenceRecord].
 *
 * Mapping follows docs/evidence/runtime-evidence-export-model.md:
 *
 * | Runtime evidence field     | Source                      |
 * |---------------------------|-----------------------------|
 * | schemaVersion             | "runtime-evidence.v1"       |
 * | eventId                   | AuditEvent.eventId          |
 * | eventType                 | "policy.decision"           |
 * | workflowRunId             | AuditEvent.workflowRunId    |
 * | correlationId             | AuditEvent.correlationId    |
 * | actor                     | AuditEvent.actor            |
 * | createdAt                 | AuditEvent.timestamp        |
 * | source.component          | "policy-engine"             |
 * | source.module             | AuditEvent.policyVersion    |
 * | decision.kind             | AuditEvent.decision         |
 * | decision.reasonCode       | AuditEvent.reasonCode       |
 * | digests.subjectDigest     | SHA-256 of event.auditStreamId |
 * | digests.payloadDigest     | SHA-256 of canonical payload   |
 * | metadata                  | AuditEvent.metadata (safe)  |
 */
internal fun AuditEvent.toRuntimeEvidenceRecord(): RuntimeEvidenceRecord {
    val subjectDigest = EvidenceDigest.sha256(auditStreamId)
    val payloadDigest = computePayloadDigest()

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
            subjectDigest = subjectDigest,
            payloadDigest = payloadDigest,
        ),
        metadata = metadata,
    )
}

/**
 * Computes a deterministic SHA-256 digest of the decision payload.
 *
 * The canonical payload includes:
 * - eventType
 * - decision.kind
 * - decision.reasonCode (if present)
 * - source.component
 * - source.module (if present)
 * - metadata (sorted)
 *
 * This ensures the digest is stable for a given decision shape, while
 * excluding volatile fields (timestamps, event IDs) that change per emit.
 */
internal fun AuditEvent.computePayloadDigest(): String {
    val canonical = buildString {
        append("eventType=policy.decision")
        append("|decision=")
        append(decision)
        reasonCode?.let { append("|reasonCode=").append(it) }
        append("|source.component=policy-engine")
        policyVersion?.let { append("|source.module=").append(it) }
        if (metadata.isNotEmpty()) {
            append("|metadata=")
            append(
                metadata.toSortedMap().entries.joinToString(",") { (k, v) ->
                    "$k=$v"
                }
            )
        }
    }
    return EvidenceDigest.sha256(canonical)
}

/**
 * Writes [RuntimeEvidenceRecord] instances as newline-delimited JSON (JSONL).
 *
 * Each line is a complete, valid JSON object. The output is terminated
 * with a newline (including the last record).
 */
object RuntimeEvidenceJsonlWriter {

    /**
     * Serialises a list of records to JSONL format.
     *
     * @param records the evidence records
     * @return JSONL string — one JSON object per line, trailing newline
     */
    fun write(records: List<RuntimeEvidenceRecord>): String =
        records.joinToString(separator = "\n", postfix = "\n") { it.toJson() }

    private fun RuntimeEvidenceRecord.toJson(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.appendField("schemaVersion", schemaVersion)
        sb.appendField("eventId", eventId)
        sb.appendField("eventType", eventType)
        sb.appendNullableField("workflowRunId", workflowRunId)
        sb.appendNullableField("correlationId", correlationId)
        sb.appendNullableField("actor", actor)
        sb.appendField("createdAt", DateTimeFormatter.ISO_INSTANT.format(createdAt))
        sb.appendObjectField("source") {
            appendField("component", source.component)
            appendNullableField("module", source.module)
        }
        sb.appendObjectField("decision") {
            appendField("kind", decision.kind)
            appendNullableField("reasonCode", decision.reasonCode)
        }
        sb.appendObjectField("digests") {
            appendField("subjectDigest", digests.subjectDigest)
            appendField("payloadDigest", digests.payloadDigest)
        }
        sb.appendMetadataField("metadata", metadata)
        // Remove trailing comma and close
        sb.setLength(sb.length - 1)
        sb.append('}')
        return sb.toString()
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        appendJsonString(name)
        append(':')
        appendJsonString(value)
        append(',')
    }

    private fun StringBuilder.appendNullableField(name: String, value: String?) {
        appendJsonString(name)
        append(':')
        if (value != null) {
            appendJsonString(value)
        } else {
            append("null")
        }
        append(',')
    }

    private fun StringBuilder.appendObjectField(name: String, block: StringBuilder.() -> Unit) {
        appendJsonString(name)
        append(':')
        append('{')
        block()
        if (this.last() == ',') this.setLength(this.length - 1)
        append("},")
    }

    private fun StringBuilder.appendMetadataField(name: String, value: Map<String, String>) {
        appendJsonString(name)
        append(':')
        append('{')
        if (value.isNotEmpty()) {
            for ((key, mapValue) in value.toSortedMap()) {
                appendJsonString(key)
                append(':')
                appendJsonString(mapValue)
                append(',')
            }
            setLength(length - 1)
        }
        append('}')
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    when {
                        c.code in 0xD800..0xDFFF || c < ' ' -> {
                            append("\\u")
                            append(c.code.toString(16).padStart(4, '0'))
                        }
                        else -> append(c)
                    }
                }
            }
        }
        append('"')
    }
}
