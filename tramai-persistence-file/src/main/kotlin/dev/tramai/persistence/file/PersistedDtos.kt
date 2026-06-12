package dev.tramai.persistence.file

import com.fasterxml.jackson.annotation.JsonProperty
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
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("workflowRunId") val workflowRunId: String,
    @JsonProperty("toolName") val toolName: String,
    @JsonProperty("argumentsDigest") val argumentsDigest: String,
    @JsonProperty("policyVersion") val policyVersion: String,
    @JsonProperty("workflowDigest") val workflowDigest: String,
    @JsonProperty("approvalTokenDigest") val approvalTokenDigest: String,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedApprovalBindingV1 = strictReadValue(json)
    }
}

/**
 * Persistable DTO for [ApprovalRequest].
 * Timestamps are stored as ISO-8601 strings.
 */
data class PersistedApprovalRequestV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("approvalId") val approvalId: String,
    @JsonProperty("binding") val binding: PersistedApprovalBindingV1,
    @JsonProperty("status") val status: String,
    @JsonProperty("requestedBy") val requestedBy: String,
    @JsonProperty("requestedAt") val requestedAt: String,
    @JsonProperty("expiresAt") val expiresAt: String,
    @JsonProperty("decidedBy") val decidedBy: String? = null,
    @JsonProperty("decidedAt") val decidedAt: String? = null,
    @JsonProperty("decisionComment") val decisionComment: String? = null,
    @JsonProperty("consumedBy") val consumedBy: String? = null,
    @JsonProperty("consumedAt") val consumedAt: String? = null,
    @JsonProperty("version") val version: Long,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedApprovalRequestV1 = strictReadValue(json)
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
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("approvalId") val approvalId: String,
    @JsonProperty("workflowRunId") val workflowRunId: String,
    @JsonProperty("correlationId") val correlationId: String,
    @JsonProperty("toolCallId") val toolCallId: String,
    @JsonProperty("toolName") val toolName: String,
    @JsonProperty("argumentsDigest") val argumentsDigest: String,
    @JsonProperty("policyVersion") val policyVersion: String,
    @JsonProperty("workflowDigest") val workflowDigest: String,
    @JsonProperty("status") val status: String,
    @JsonProperty("createdAt") val createdAt: String,
    @JsonProperty("approvalExpiresAt") val approvalExpiresAt: String,
    @JsonProperty("claimedBy") val claimedBy: String? = null,
    @JsonProperty("claimedAt") val claimedAt: String? = null,
    @JsonProperty("completedAt") val completedAt: String? = null,
    @JsonProperty("recoveryResolvedBy") val recoveryResolvedBy: String? = null,
    @JsonProperty("recoveryResolvedAt") val recoveryResolvedAt: String? = null,
    @JsonProperty("recoveryReasonCode") val recoveryReasonCode: String? = null,
    @JsonProperty("version") val version: Long,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedApprovalContinuationV1 = strictReadValue(json)
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
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("continuation") val continuation: PersistedApprovalContinuationV1,
    @JsonProperty("arguments") val arguments: String? = null,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedApprovalContinuationRecordV1 = strictReadValue(json)
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
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("hashAlgorithm") val hashAlgorithm: String,
    @JsonProperty("auditStreamId") val auditStreamId: String,
    @JsonProperty("eventId") val eventId: String,
    @JsonProperty("sequenceNumber") val sequenceNumber: Long,
    @JsonProperty("workflowRunId") val workflowRunId: String? = null,
    @JsonProperty("correlationId") val correlationId: String? = null,
    @JsonProperty("actor") val actor: String? = null,
    @JsonProperty("enforcementPoint") val enforcementPoint: String,
    @JsonProperty("decision") val decision: String,
    @JsonProperty("policyVersion") val policyVersion: String? = null,
    @JsonProperty("workflowDigest") val workflowDigest: String? = null,
    @JsonProperty("previousEventHash") val previousEventHash: String? = null,
    @JsonProperty("eventHash") val eventHash: String,
    @JsonProperty("timestamp") val timestamp: String,
    @JsonProperty("reasonCode") val reasonCode: String? = null,
    @JsonProperty("metadata") val metadata: Map<String, String> = emptyMap(),
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedAuditEventV1 = strictReadValue(json)
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
    schemaVersion = 1,
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
    schemaVersion = 1,
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
        schemaVersion = 1,
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
