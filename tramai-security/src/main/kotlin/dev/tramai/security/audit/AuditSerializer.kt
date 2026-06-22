package dev.tramai.security.audit

import java.security.MessageDigest
import java.time.format.DateTimeFormatter

fun AuditEvent.toCanonicalJson(): String {
    val writer = CanonicalJsonWriter()
    writer.appendIntField("schemaVersion", schemaVersion)
    writer.appendStringField("hashAlgorithm", hashAlgorithm.wireName)
    writer.appendStringField("auditStreamId", auditStreamId)
    writer.appendStringField("eventId", eventId)
    writer.appendLongField("sequenceNumber", sequenceNumber)
    writer.appendNullableStringField("workflowRunId", workflowRunId)
    writer.appendNullableStringField("correlationId", correlationId)
    writer.appendNullableStringField("actor", actor)
    writer.appendStringField("enforcementPoint", enforcementPoint)
    writer.appendStringField("decision", decision)
    writer.appendNullableStringField("policyVersion", policyVersion)
    writer.appendNullableStringField("workflowDigest", workflowDigest)
    writer.appendNullableStringField("previousEventHash", previousEventHash)
    writer.appendStringField("eventHash", eventHash)
    writer.appendStringField("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp))
    writer.appendNullableStringField("reasonCode", reasonCode)
    writer.appendMetadataField("metadata", metadata)
    return writer.finish()
}

private class CanonicalJsonWriter {
    private val builder = StringBuilder().append('{')
    private var needsComma = false

    fun appendStringField(name: String, value: String) {
        appendFieldName(name)
        appendJsonString(builder, value)
    }

    fun appendIntField(name: String, value: Int) {
        appendFieldName(name)
        builder.append(value)
    }

    fun appendLongField(name: String, value: Long) {
        appendFieldName(name)
        builder.append(value)
    }

    fun appendNullableStringField(name: String, value: String?) {
        appendFieldName(name)
        if (value != null) {
            appendJsonString(builder, value)
        } else {
            builder.append("null")
        }
    }

    fun appendMetadataField(name: String, value: Map<String, String>) {
        appendFieldName(name)
        builder.append('{')
        appendMetadataEntries(value)
        builder.append('}')
    }

    fun finish(): String = builder.append('}').toString()

    private fun appendFieldName(name: String) {
        if (needsComma) builder.append(',')
        appendJsonString(builder, name)
        builder.append(':')
        needsComma = true
    }

    private fun appendMetadataEntries(value: Map<String, String>) {
        var metadataNeedsComma = false
        for ((key, mapValue) in value.toSortedMap()) {
            if (metadataNeedsComma) builder.append(',')
            appendJsonString(builder, key)
            builder.append(':')
            appendJsonString(builder, mapValue)
            metadataNeedsComma = true
        }
    }
}

fun AuditEvent.calculateHash(): String {
    val canonicalJson = copy(eventHash = "").toCanonicalJson()
    val digest = MessageDigest.getInstance(hashAlgorithm.jcaName).digest(canonicalJson.toByteArray(Charsets.UTF_8))
    val builder = StringBuilder(digest.size * 2)
    for (byte in digest) {
        builder.append(((byte.toInt() ushr 4) and 0x0F).toString(16))
        builder.append((byte.toInt() and 0x0F).toString(16))
    }
    return builder.toString()
}

internal fun appendJsonString(builder: StringBuilder, value: String) {
    builder.append('"')
    for (character in value) {
        when (character) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> {
                    when {
                        character.code in 0xD800..0xDFFF || character < ' ' -> {
                            builder.append("\\u")
                            builder.append(character.code.toString(16).padStart(4, '0'))
                        }
                    else -> builder.append(character)
                }
            }
        }
    }
    builder.append('"')
}
