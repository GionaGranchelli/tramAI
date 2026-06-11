package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import java.time.Instant

// ====================================================================
// Approval Store DTOs
// ====================================================================

/**
 * Persistable DTO for [ApprovalBinding].
 * All digest fields are stored as raw strings (the "sha256:..." format).
 */
data class PersistedApprovalBindingV1(
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: String,
    val policyVersion: String,
    val workflowDigest: String,
    val approvalTokenDigest: String,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"workflowRunId\": \"${escapeJson(workflowRunId)}\",")
        appendLine("  \"toolName\": \"${escapeJson(toolName)}\",")
        appendLine("  \"argumentsDigest\": \"${escapeJson(argumentsDigest)}\",")
        appendLine("  \"policyVersion\": \"${escapeJson(policyVersion)}\",")
        appendLine("  \"workflowDigest\": \"${escapeJson(workflowDigest)}\",")
        appendLine("  \"approvalTokenDigest\": \"${escapeJson(approvalTokenDigest)}\"")
        append("}")
    }

    companion object {
        fun fromJson(json: String): PersistedApprovalBindingV1 {
            val trimmed = json.trim()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
                "Invalid binding JSON: must be a JSON object"
            }
            return PersistedApprovalBindingV1(
                workflowRunId = extractJsonString(trimmed, "workflowRunId"),
                toolName = extractJsonString(trimmed, "toolName"),
                argumentsDigest = extractJsonString(trimmed, "argumentsDigest"),
                policyVersion = extractJsonString(trimmed, "policyVersion"),
                workflowDigest = extractJsonString(trimmed, "workflowDigest"),
                approvalTokenDigest = extractJsonString(trimmed, "approvalTokenDigest"),
            )
        }
    }
}

/**
 * Persistable DTO for [ApprovalRequest].
 * Timestamps are stored as ISO-8601 strings.
 */
data class PersistedApprovalRequestV1(
    val approvalId: String,
    val binding: PersistedApprovalBindingV1,
    val status: String,
    val requestedBy: String,
    val requestedAt: String,
    val expiresAt: String,
    val decidedBy: String?,
    val decidedAt: String?,
    val decisionComment: String?,
    val consumedBy: String?,
    val consumedAt: String?,
    val version: Long,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"approvalId\": \"${escapeJson(approvalId)}\",")
        appendLine("  \"binding\": ${binding.toJson().replace("\n", "\n  ")},")
        appendLine("  \"status\": \"${escapeJson(status)}\",")
        appendLine("  \"requestedBy\": \"${escapeJson(requestedBy)}\",")
        appendLine("  \"requestedAt\": \"${escapeJson(requestedAt)}\",")
        appendLine("  \"expiresAt\": \"${escapeJson(expiresAt)}\",")
        appendLine("  \"decidedBy\": ${nullToJson(decidedBy)},")
        appendLine("  \"decidedAt\": ${nullToJson(decidedAt)},")
        appendLine("  \"decisionComment\": ${nullToJson(decisionComment)},")
        appendLine("  \"consumedBy\": ${nullToJson(consumedBy)},")
        appendLine("  \"consumedAt\": ${nullToJson(consumedAt)},")
        appendLine("  \"version\": $version")
        append("}")
    }

    companion object {
        fun fromJson(json: String): PersistedApprovalRequestV1 {
            val trimmed = json.trim()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
                "Invalid approval request JSON: must be a JSON object"
            }
            // Extract binding as a nested JSON object
            val bindingRegex = Regex("\"binding\"\\s*:\\s*(\\{[^}]+\\})")
            val bindingMatch = bindingRegex.find(trimmed)
                ?: throw IllegalArgumentException("Missing or invalid field: binding")
            val bindingJson = bindingMatch.groupValues[1]
            val binding = PersistedApprovalBindingV1.fromJson(bindingJson)

            return PersistedApprovalRequestV1(
                approvalId = extractJsonString(trimmed, "approvalId"),
                binding = binding,
                status = extractJsonString(trimmed, "status"),
                requestedBy = extractJsonString(trimmed, "requestedBy"),
                requestedAt = extractJsonString(trimmed, "requestedAt"),
                expiresAt = extractJsonString(trimmed, "expiresAt"),
                decidedBy = extractJsonNullableString(trimmed, "decidedBy"),
                decidedAt = extractJsonNullableString(trimmed, "decidedAt"),
                decisionComment = extractJsonNullableString(trimmed, "decisionComment"),
                consumedBy = extractJsonNullableString(trimmed, "consumedBy"),
                consumedAt = extractJsonNullableString(trimmed, "consumedAt"),
                version = extractJsonLong(trimmed, "version"),
            )
        }
    }
}

// ====================================================================
// Continuation Store DTOs
// ====================================================================

/**
 * Persistable DTO for [ApprovalContinuation].
 * Timestamps are stored as ISO-8601 strings.
 */
data class PersistedApprovalContinuationV1(
    val approvalId: String,
    val workflowRunId: String,
    val correlationId: String,
    val toolCallId: String,
    val toolName: String,
    val argumentsDigest: String,
    val policyVersion: String,
    val workflowDigest: String,
    val status: String,
    val createdAt: String,
    val approvalExpiresAt: String,
    val claimedBy: String?,
    val claimedAt: String?,
    val completedAt: String?,
    val recoveryResolvedBy: String?,
    val recoveryResolvedAt: String?,
    val recoveryReasonCode: String?,
    val version: Long,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"approvalId\": \"${escapeJson(approvalId)}\",")
        appendLine("  \"workflowRunId\": \"${escapeJson(workflowRunId)}\",")
        appendLine("  \"correlationId\": \"${escapeJson(correlationId)}\",")
        appendLine("  \"toolCallId\": \"${escapeJson(toolCallId)}\",")
        appendLine("  \"toolName\": \"${escapeJson(toolName)}\",")
        appendLine("  \"argumentsDigest\": \"${escapeJson(argumentsDigest)}\",")
        appendLine("  \"policyVersion\": \"${escapeJson(policyVersion)}\",")
        appendLine("  \"workflowDigest\": \"${escapeJson(workflowDigest)}\",")
        appendLine("  \"status\": \"${escapeJson(status)}\",")
        appendLine("  \"createdAt\": \"${escapeJson(createdAt)}\",")
        appendLine("  \"approvalExpiresAt\": \"${escapeJson(approvalExpiresAt)}\",")
        appendLine("  \"claimedBy\": ${nullToJson(claimedBy)},")
        appendLine("  \"claimedAt\": ${nullToJson(claimedAt)},")
        appendLine("  \"completedAt\": ${nullToJson(completedAt)},")
        appendLine("  \"recoveryResolvedBy\": ${nullToJson(recoveryResolvedBy)},")
        appendLine("  \"recoveryResolvedAt\": ${nullToJson(recoveryResolvedAt)},")
        appendLine("  \"recoveryReasonCode\": ${nullToJson(recoveryReasonCode)},")
        appendLine("  \"version\": $version")
        append("}")
    }

    companion object {
        fun fromJson(json: String): PersistedApprovalContinuationV1 {
            val trimmed = json.trim()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
                "Invalid continuation JSON: must be a JSON object"
            }
            return PersistedApprovalContinuationV1(
                approvalId = extractJsonString(trimmed, "approvalId"),
                workflowRunId = extractJsonString(trimmed, "workflowRunId"),
                correlationId = extractJsonString(trimmed, "correlationId"),
                toolCallId = extractJsonString(trimmed, "toolCallId"),
                toolName = extractJsonString(trimmed, "toolName"),
                argumentsDigest = extractJsonString(trimmed, "argumentsDigest"),
                policyVersion = extractJsonString(trimmed, "policyVersion"),
                workflowDigest = extractJsonString(trimmed, "workflowDigest"),
                status = extractJsonString(trimmed, "status"),
                createdAt = extractJsonString(trimmed, "createdAt"),
                approvalExpiresAt = extractJsonString(trimmed, "approvalExpiresAt"),
                claimedBy = extractJsonNullableString(trimmed, "claimedBy"),
                claimedAt = extractJsonNullableString(trimmed, "claimedAt"),
                completedAt = extractJsonNullableString(trimmed, "completedAt"),
                recoveryResolvedBy = extractJsonNullableString(trimmed, "recoveryResolvedBy"),
                recoveryResolvedAt = extractJsonNullableString(trimmed, "recoveryResolvedAt"),
                recoveryReasonCode = extractJsonNullableString(trimmed, "recoveryReasonCode"),
                version = extractJsonLong(trimmed, "version"),
            )
        }
    }
}

/**
 * Storage record that pairs a continuation with its encrypted
 * sensitive arguments payload.
 *
 * @property continuation The continuation metadata.
 * @property arguments Raw [SensitiveToolArguments] content as a UTF-8 string,
 *                     or null after the continuation has been claimed.
 */
data class PersistedApprovalContinuationRecordV1(
    val continuation: PersistedApprovalContinuationV1,
    val arguments: String?,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"continuation\": ${continuation.toJson().replace("\n", "\n  ")},")
        appendLine("  \"arguments\": ${nullToJson(arguments)}")
        append("}")
    }

    companion object {
        fun fromJson(json: String): PersistedApprovalContinuationRecordV1 {
            val trimmed = json.trim()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
                "Invalid continuation record JSON: must be a JSON object"
            }
            val continuationRegex = Regex("\"continuation\"\\s*:\\s*(\\{[^}]+\\})")
            val continuationMatch = continuationRegex.find(trimmed)
                ?: throw IllegalArgumentException("Missing or invalid field: continuation")
            val continuationJson = continuationMatch.groupValues[1]
            val continuation = PersistedApprovalContinuationV1.fromJson(continuationJson)

            return PersistedApprovalContinuationRecordV1(
                continuation = continuation,
                arguments = extractJsonNullableString(trimmed, "arguments"),
            )
        }
    }
}

// ====================================================================
// Audit DTO
// ====================================================================

/**
 * Persistable DTO for [AuditEvent].
 * Timestamps are stored as ISO-8601 strings.
 */
data class PersistedAuditEventV1(
    val schemaVersion: Int,
    val hashAlgorithm: String,
    val auditStreamId: String,
    val eventId: String,
    val sequenceNumber: Long,
    val workflowRunId: String?,
    val correlationId: String?,
    val actor: String?,
    val enforcementPoint: String,
    val decision: String,
    val policyVersion: String?,
    val workflowDigest: String?,
    val previousEventHash: String?,
    val eventHash: String,
    val timestamp: String,
    val reasonCode: String?,
    val metadata: Map<String, String>,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"schemaVersion\": $schemaVersion,")
        appendLine("  \"hashAlgorithm\": \"${escapeJson(hashAlgorithm)}\",")
        appendLine("  \"auditStreamId\": \"${escapeJson(auditStreamId)}\",")
        appendLine("  \"eventId\": \"${escapeJson(eventId)}\",")
        appendLine("  \"sequenceNumber\": $sequenceNumber,")
        appendLine("  \"workflowRunId\": ${nullToJson(workflowRunId)},")
        appendLine("  \"correlationId\": ${nullToJson(correlationId)},")
        appendLine("  \"actor\": ${nullToJson(actor)},")
        appendLine("  \"enforcementPoint\": \"${escapeJson(enforcementPoint)}\",")
        appendLine("  \"decision\": \"${escapeJson(decision)}\",")
        appendLine("  \"policyVersion\": ${nullToJson(policyVersion)},")
        appendLine("  \"workflowDigest\": ${nullToJson(workflowDigest)},")
        appendLine("  \"previousEventHash\": ${nullToJson(previousEventHash)},")
        appendLine("  \"eventHash\": \"${escapeJson(eventHash)}\",")
        appendLine("  \"timestamp\": \"${escapeJson(timestamp)}\",")
        appendLine("  \"reasonCode\": ${nullToJson(reasonCode)},")
        append("  \"metadata\": ${mapToJson(metadata)}")
        append("\n}")
    }

    companion object {
        fun fromJson(json: String): PersistedAuditEventV1 {
            val trimmed = json.trim()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
                "Invalid audit event JSON: must be a JSON object"
            }
            return PersistedAuditEventV1(
                schemaVersion = extractJsonInt(trimmed, "schemaVersion"),
                hashAlgorithm = extractJsonString(trimmed, "hashAlgorithm"),
                auditStreamId = extractJsonString(trimmed, "auditStreamId"),
                eventId = extractJsonString(trimmed, "eventId"),
                sequenceNumber = extractJsonLong(trimmed, "sequenceNumber"),
                workflowRunId = extractJsonNullableString(trimmed, "workflowRunId"),
                correlationId = extractJsonNullableString(trimmed, "correlationId"),
                actor = extractJsonNullableString(trimmed, "actor"),
                enforcementPoint = extractJsonString(trimmed, "enforcementPoint"),
                decision = extractJsonString(trimmed, "decision"),
                policyVersion = extractJsonNullableString(trimmed, "policyVersion"),
                workflowDigest = extractJsonNullableString(trimmed, "workflowDigest"),
                previousEventHash = extractJsonNullableString(trimmed, "previousEventHash"),
                eventHash = extractJsonString(trimmed, "eventHash"),
                timestamp = extractJsonString(trimmed, "timestamp"),
                reasonCode = extractJsonNullableString(trimmed, "reasonCode"),
                metadata = extractJsonMap(trimmed, "metadata"),
            )
        }
    }
}

// ====================================================================
// Domain conversions — ApprovalBinding
// ====================================================================

fun PersistedApprovalBindingV1.toDomain(): ApprovalBinding = ApprovalBinding(
    workflowRunId = workflowRunId,
    toolName = toolName,
    argumentsDigest = Sha256Digest.of(argumentsDigest),
    policyVersion = policyVersion,
    workflowDigest = Sha256Digest.of(workflowDigest),
    approvalTokenDigest = Sha256Digest.of(approvalTokenDigest),
)

fun ApprovalBinding.toPersistedV1(): PersistedApprovalBindingV1 = PersistedApprovalBindingV1(
    workflowRunId = workflowRunId,
    toolName = toolName,
    argumentsDigest = argumentsDigest.value,
    policyVersion = policyVersion,
    workflowDigest = workflowDigest.value,
    approvalTokenDigest = approvalTokenDigest.value,
)

// ====================================================================
// Domain conversions — ApprovalRequest
// ====================================================================

fun PersistedApprovalRequestV1.toDomain(): ApprovalRequest = ApprovalRequest(
    approvalId = approvalId,
    binding = binding.toDomain(),
    status = ApprovalStatus.valueOf(status),
    requestedBy = requestedBy,
    requestedAt = Instant.parse(requestedAt),
    expiresAt = Instant.parse(expiresAt),
    decidedBy = decidedBy,
    decidedAt = decidedAt?.let { Instant.parse(it) },
    decisionComment = decisionComment,
    consumedBy = consumedBy,
    consumedAt = consumedAt?.let { Instant.parse(it) },
    version = version,
)

fun ApprovalRequest.toPersistedV1(): PersistedApprovalRequestV1 = PersistedApprovalRequestV1(
    approvalId = approvalId,
    binding = binding.toPersistedV1(),
    status = status.name,
    requestedBy = requestedBy,
    requestedAt = requestedAt.toString(),
    expiresAt = expiresAt.toString(),
    decidedBy = decidedBy,
    decidedAt = decidedAt?.toString(),
    decisionComment = decisionComment,
    consumedBy = consumedBy,
    consumedAt = consumedAt?.toString(),
    version = version,
)

// ====================================================================
// Domain conversions — ApprovalContinuation
// ====================================================================

fun PersistedApprovalContinuationV1.toDomain(): ApprovalContinuation = ApprovalContinuation(
    approvalId = approvalId,
    workflowRunId = workflowRunId,
    correlationId = correlationId,
    toolCallId = toolCallId,
    toolName = toolName,
    argumentsDigest = Sha256Digest.of(argumentsDigest),
    policyVersion = policyVersion,
    workflowDigest = Sha256Digest.of(workflowDigest),
    status = ApprovalContinuationStatus.valueOf(status),
    createdAt = Instant.parse(createdAt),
    approvalExpiresAt = Instant.parse(approvalExpiresAt),
    claimedBy = claimedBy,
    claimedAt = claimedAt?.let { Instant.parse(it) },
    completedAt = completedAt?.let { Instant.parse(it) },
    recoveryResolvedBy = recoveryResolvedBy,
    recoveryResolvedAt = recoveryResolvedAt?.let { Instant.parse(it) },
    recoveryReasonCode = recoveryReasonCode,
    version = version,
)

fun ApprovalContinuation.toPersistedV1(): PersistedApprovalContinuationV1 =
    PersistedApprovalContinuationV1(
        approvalId = approvalId,
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        toolCallId = toolCallId,
        toolName = toolName,
        argumentsDigest = argumentsDigest.value,
        policyVersion = policyVersion,
        workflowDigest = workflowDigest.value,
        status = status.name,
        createdAt = createdAt.toString(),
        approvalExpiresAt = approvalExpiresAt.toString(),
        claimedBy = claimedBy,
        claimedAt = claimedAt?.toString(),
        completedAt = completedAt?.toString(),
        recoveryResolvedBy = recoveryResolvedBy,
        recoveryResolvedAt = recoveryResolvedAt?.toString(),
        recoveryReasonCode = recoveryReasonCode,
        version = version,
    )

/**
 * Extracts the [ApprovalContinuation] metadata from this record.
 */
fun PersistedApprovalContinuationRecordV1.toDomain(): ApprovalContinuation =
    continuation.toDomain()

// ====================================================================
// Domain conversions — AuditEvent
// ====================================================================

fun PersistedAuditEventV1.toDomain(): AuditEvent = AuditEvent(
    schemaVersion = schemaVersion,
    hashAlgorithm = AuditHashAlgorithm.valueOf(
        AuditHashAlgorithm.entries.first { it.wireName == hashAlgorithm }.name,
    ),
    auditStreamId = auditStreamId,
    eventId = eventId,
    sequenceNumber = sequenceNumber,
    workflowRunId = workflowRunId,
    correlationId = correlationId,
    actor = actor,
    enforcementPoint = enforcementPoint,
    decision = decision,
    policyVersion = policyVersion,
    workflowDigest = workflowDigest,
    previousEventHash = previousEventHash,
    eventHash = eventHash,
    timestamp = Instant.parse(timestamp),
    reasonCode = reasonCode,
    metadata = metadata,
)

fun AuditEvent.toPersistedV1(): PersistedAuditEventV1 = PersistedAuditEventV1(
    schemaVersion = schemaVersion,
    hashAlgorithm = hashAlgorithm.wireName,
    auditStreamId = auditStreamId,
    eventId = eventId,
    sequenceNumber = sequenceNumber,
    workflowRunId = workflowRunId,
    correlationId = correlationId,
    actor = actor,
    enforcementPoint = enforcementPoint,
    decision = decision,
    policyVersion = policyVersion,
    workflowDigest = workflowDigest,
    previousEventHash = previousEventHash,
    eventHash = eventHash,
    timestamp = timestamp.toString(),
    reasonCode = reasonCode,
    metadata = metadata,
)

// ====================================================================
// JSON helpers for Persisted DTO serialization
// ====================================================================

/**
 * Escapes a string value for use inside a JSON string literal.
 */
internal fun escapeJson(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

/**
 * Converts a nullable string to a JSON literal: `null` or `"value"`.
 */
internal fun nullToJson(value: String?): String =
    if (value == null) "null" else "\"${escapeJson(value)}\""

/**
 * Extracts a required string value from a JSON object string.
 */
internal fun extractJsonString(json: String, key: String): String {
    val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
    val match = regex.find(json)
        ?: throw IllegalArgumentException("Missing or invalid field: $key")
    return match.groupValues[1]
}

/**
 * Extracts a nullable string value from a JSON object string.
 * Returns null if the value is `null`, otherwise the string content.
 */
internal fun extractJsonNullableString(json: String, key: String): String? {
    val nullRegex = Regex("\"$key\"\\s*:\\s*null\\s*(?:,|\\}|$)")
    if (nullRegex.containsMatchIn(json)) return null
    return extractJsonString(json, key)
}

/**
 * Extracts a long value from a JSON object string.
 */
internal fun extractJsonLong(json: String, key: String): Long {
    val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
    val match = regex.find(json)
        ?: throw IllegalArgumentException("Missing or invalid field: $key")
    return match.groupValues[1].toLong()
}

/**
 * Extracts an int value from a JSON object string.
 */
internal fun extractJsonInt(json: String, key: String): Int {
    val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
    val match = regex.find(json)
        ?: throw IllegalArgumentException("Missing or invalid field: $key")
    return match.groupValues[1].toInt()
}

/**
 * Serializes a [Map<String, String>] to a JSON object string.
 */
internal fun mapToJson(map: Map<String, String>): String {
    if (map.isEmpty()) return "{}"
    val entries = map.entries.joinToString(",") { (k, v) ->
        "\"${escapeJson(k)}\":\"${escapeJson(v)}\""
    }
    return "{$entries}"
}

/**
 * Extracts a map value from a JSON object string.
 * Handles `"metadata": {"key1":"val1","key2":"val2"}` format.
 */
internal fun extractJsonMap(json: String, key: String): Map<String, String> {
    val regex = Regex("\"$key\"\\s*:\\s*(\\{.*\\})", setOf(RegexOption.DOT_MATCHES_ALL))
    val match = regex.find(json) ?: return emptyMap()
    val mapStr = match.groupValues[1]
    if (mapStr == "{}" || mapStr.isEmpty()) return emptyMap()
    val entryRegex = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
    val result = mutableMapOf<String, String>()
    for (m in entryRegex.findAll(mapStr)) {
        result[m.groupValues[1]] = m.groupValues[2]
    }
    return result
}
