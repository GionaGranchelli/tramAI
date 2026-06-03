package dev.tramai.security.audit

import java.security.MessageDigest

fun AuditEvent.toCanonicalJson(): String {
    val builder = StringBuilder()
    builder.append('{')

    var needsComma = false

    fun appendField(name: String, value: String) {
        if (needsComma) {
            builder.append(',')
        }
        builder.append('"')
        builder.append(name)
        builder.append("\":")
        appendJsonString(builder, value)
        needsComma = true
    }

    fun appendField(name: String, value: Int) {
        if (needsComma) {
            builder.append(',')
        }
        builder.append('"')
        builder.append(name)
        builder.append("\":")
        builder.append(value)
        needsComma = true
    }

    fun appendField(name: String, value: Long) {
        if (needsComma) {
            builder.append(',')
        }
        builder.append('"')
        builder.append(name)
        builder.append("\":")
        builder.append(value)
        needsComma = true
    }

    fun appendNullableField(name: String, value: String?) {
        if (value != null) {
            appendField(name, value)
        }
    }

    fun appendMetadataField(name: String, value: Map<String, String>) {
        if (needsComma) {
            builder.append(',')
        }
        builder.append('"')
        builder.append(name)
        builder.append("\":{")
        var metadataNeedsComma = false
        for ((key, mapValue) in value.toSortedMap()) {
            if (metadataNeedsComma) {
                builder.append(',')
            }
            appendJsonString(builder, key)
            builder.append(':')
            appendJsonString(builder, mapValue)
            metadataNeedsComma = true
        }
        builder.append('}')
        needsComma = true
    }

    appendField("schemaVersion", schemaVersion)
    appendField("hashAlgorithm", hashAlgorithm)
    appendField("auditStreamId", auditStreamId)
    appendField("eventId", eventId)
    appendField("sequenceNumber", sequenceNumber)
    appendNullableField("workflowRunId", workflowRunId)
    appendNullableField("correlationId", correlationId)
    appendNullableField("actor", actor)
    appendField("enforcementPoint", enforcementPoint)
    appendField("decision", decision)
    appendNullableField("policyVersion", policyVersion)
    appendNullableField("workflowDigest", workflowDigest)
    appendNullableField("previousEventHash", previousEventHash)
    if (eventHash.isNotEmpty()) {
        appendField("eventHash", eventHash)
    }
    appendField("timestamp", timestamp)
    appendNullableField("reasonCode", reasonCode)
    appendMetadataField("metadata", metadata)

    builder.append('}')
    return builder.toString()
}

fun AuditEvent.calculateHash(): String {
    val canonicalJson = copy(eventHash = "").toCanonicalJson()
    val digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson.toByteArray(Charsets.UTF_8))
    val builder = StringBuilder(digest.size * 2)
    for (byte in digest) {
        builder.append(((byte.toInt() ushr 4) and 0x0F).toString(16))
        builder.append((byte.toInt() and 0x0F).toString(16))
    }
    return builder.toString()
}

private fun appendJsonString(builder: StringBuilder, value: String) {
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
