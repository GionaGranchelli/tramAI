package dev.tramai.security.audit

import java.security.MessageDigest
import java.time.format.DateTimeFormatter

fun AuditEvent.toCanonicalJson(): String {
    val builder = StringBuilder()
    builder.append('{')

    var needsComma = false

    fun appendStringField(name: String, value: String) {
        if (needsComma) builder.append(',')
        builder.append('"')
        builder.append(name)
        builder.append("\":")
        appendJsonString(builder, value)
        needsComma = true
    }

    fun appendIntField(name: String, value: Int) {
        if (needsComma) builder.append(',')
        builder.append('"')
        builder.append(name)
        builder.append("\":")
        builder.append(value)
        needsComma = true
    }

    fun appendLongField(name: String, value: Long) {
        if (needsComma) builder.append(',')
        builder.append('"')
        builder.append(name)
        builder.append("\":")
        builder.append(value)
        needsComma = true
    }

    fun appendNullableStringField(name: String, value: String?) {
        if (needsComma) builder.append(',')
        builder.append('"')
        builder.append(name)
        builder.append("\":")
        if (value != null) {
            appendJsonString(builder, value)
        } else {
            builder.append("null")
        }
        needsComma = true
    }

    fun appendMetadataField(name: String, value: Map<String, String>) {
        if (needsComma) builder.append(',')
        builder.append('"')
        builder.append(name)
        builder.append("\":{")
        var metadataNeedsComma = false
        for ((key, mapValue) in value.toSortedMap()) {
            if (metadataNeedsComma) builder.append(',')
            appendJsonString(builder, key)
            builder.append(':')
            appendJsonString(builder, mapValue)
            metadataNeedsComma = true
        }
        builder.append('}')
        needsComma = true
    }

    appendIntField("schemaVersion", schemaVersion)
    appendStringField("hashAlgorithm", hashAlgorithm.wireName)
    appendStringField("auditStreamId", auditStreamId)
    appendStringField("eventId", eventId)
    appendLongField("sequenceNumber", sequenceNumber)
    appendNullableStringField("workflowRunId", workflowRunId)
    appendNullableStringField("correlationId", correlationId)
    appendNullableStringField("actor", actor)
    appendStringField("enforcementPoint", enforcementPoint)
    appendStringField("decision", decision)
    appendNullableStringField("policyVersion", policyVersion)
    appendNullableStringField("workflowDigest", workflowDigest)
    appendNullableStringField("previousEventHash", previousEventHash)
    appendStringField("eventHash", eventHash)
    appendStringField("timestamp", DateTimeFormatter.ISO_INSTANT.format(timestamp))
    appendNullableStringField("reasonCode", reasonCode)
    appendMetadataField("metadata", metadata)

    builder.append('}')
    return builder.toString()
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
                if (character < ' ') {
                    builder.append("\\u")
                    builder.append(character.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(character)
                }
            }
        }
    }
    builder.append('"')
}
