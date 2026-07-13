package dev.tramai.security.evidence

import java.time.format.DateTimeFormatter

/**
 * Writes [RuntimeEvidenceRecord] instances as newline-delimited JSON (JSONL).
 *
 * Each line is a complete, valid JSON object. The output is terminated
 * with a newline when records are present; an empty list produces an
 * empty string.
 */
object RuntimeEvidenceJsonlWriter {

    /**
     * Serialises a list of records to JSONL format.
     *
     * @param records the evidence records
     * @return JSONL string — one JSON object per line, trailing newline.
     *         Returns empty string for an empty input list.
     */
    fun write(records: List<RuntimeEvidenceRecord>): String {
        if (records.isEmpty()) return ""
        return records.joinToString(separator = "\n", postfix = "\n") { it.toJson() }
    }

    private fun RuntimeEvidenceRecord.toJson(): String {
        val w = JsonObjectWriter()
        w.field("schemaVersion", schemaVersion)
        w.field("eventId", eventId)
        w.field("eventType", eventType)
        w.nullableField("workflowRunId", workflowRunId)
        w.nullableField("correlationId", correlationId)
        w.nullableField("actor", actor)
        w.field("createdAt", DateTimeFormatter.ISO_INSTANT.format(createdAt))
        w.objectField("source") {
            field("component", source.component)
            nullableField("module", source.module)
        }
        w.objectField("decision") {
            field("kind", decision.kind)
            nullableField("reasonCode", decision.reasonCode)
        }
        w.objectField("digests") {
            field("subjectDigest", digests.subjectDigest)
            field("payloadDigest", digests.payloadDigest)
        }
        w.metadataField("metadata", metadata)
        return w.finish()
    }
}

/**
 * Tracks comma state internally so the caller never needs to trim
 * trailing characters.
 */
internal class JsonObjectWriter {
    private val sb = StringBuilder("{")
    private var needsComma = false

    fun field(name: String, value: String) {
        writeComma()
        appendJsonString(sb, name)
        sb.append(':')
        appendJsonString(sb, value)
    }

    fun nullableField(name: String, value: String?) {
        writeComma()
        appendJsonString(sb, name)
        sb.append(':')
        if (value != null) {
            appendJsonString(sb, value)
        } else {
            sb.append("null")
        }
    }

    fun objectField(name: String, block: JsonObjectWriter.() -> Unit) {
        writeComma()
        appendJsonString(sb, name)
        sb.append(':')
        val nested = JsonObjectWriter()
        nested.block()
        sb.append(nested.finish())
    }

    fun metadataField(name: String, value: Map<String, String>) {
        writeComma()
        appendJsonString(sb, name)
        sb.append(':')
        sb.append('{')
        var first = true
        for ((k, v) in value.toSortedMap()) {
            if (!first) sb.append(',')
            appendJsonString(sb, k)
            sb.append(':')
            appendJsonString(sb, v)
            first = false
        }
        sb.append('}')
    }

    fun finish(): String {
        sb.append('}')
        return sb.toString()
    }

    private fun writeComma() {
        if (needsComma) sb.append(',')
        needsComma = true
    }

    private fun appendJsonString(builder: StringBuilder, value: String) {
        CanonicalDigestBuilder.appendJsonString(builder, value)
    }
}

/**
 * Minimal canonical JSON builder used exclusively for digest computation.
 *
 * Uses stable key ordering and proper JSON string escaping so that the
 * same logical payload always produces the same digest.
 */
internal class CanonicalDigestBuilder {
    private val sb = StringBuilder("{")

    fun appendField(name: String, value: String) {
        appendComma()
        appendJsonString(sb, name)
        sb.append(':')
        appendJsonString(sb, value)
    }

    fun appendNullableField(name: String, value: String?) {
        appendComma()
        appendJsonString(sb, name)
        sb.append(':')
        if (value != null) {
            appendJsonString(sb, value)
        } else {
            sb.append("null")
        }
    }

    fun appendMetadataField(name: String, value: Map<String, String>) {
        appendComma()
        appendJsonString(sb, name)
        sb.append(':')
        sb.append('{')
        var first = true
        for ((k, v) in value.toSortedMap()) {
            if (!first) sb.append(',')
            appendJsonString(sb, k)
            sb.append(':')
            appendJsonString(sb, v)
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

    companion object {
        internal fun appendJsonString(builder: StringBuilder, value: String) {
            builder.append('"')
            for (c in value) {
                when (c) {
                    '\\' -> builder.append("\\\\")
                    '"' -> builder.append("\\\"")
                    '\b' -> builder.append("\\b")
                    '\u000C' -> builder.append("\\f")
                    '\n' -> builder.append("\\n")
                    '\r' -> builder.append("\\r")
                    '\t' -> builder.append("\\t")
                    else -> {
                        when {
                            c.code in 0xD800..0xDFFF || c < ' ' -> {
                                builder.append("\\u")
                                builder.append(c.code.toString(16).padStart(4, '0'))
                            }
                            else -> builder.append(c)
                        }
                    }
                }
            }
            builder.append('"')
        }
    }
}
