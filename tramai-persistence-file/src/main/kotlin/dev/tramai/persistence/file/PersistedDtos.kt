package dev.tramai.persistence.file

import com.fasterxml.jackson.annotation.JsonProperty
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.core.policy.AuditDetail
import dev.tramai.core.policy.ClassificationSource
import dev.tramai.core.policy.CompatibilityMode
import dev.tramai.core.policy.DataClassification
import dev.tramai.core.policy.ManagedNetworkEgress
import dev.tramai.core.policy.RiskLevel
import dev.tramai.core.policy.ToolSecurityMetadata
import dev.tramai.core.policy.ApprovalMode
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.TokenBudgetSnapshot
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import java.time.Instant
import java.util.Base64

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

// ====================================================================
// Suspended Invocation DTOs
// ====================================================================

data class PersistedSuspendedInvocationRecordV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("metadata") val metadata: PersistedSuspendedInvocationMetadataV1,
    @JsonProperty("replayEnvelope") val replayEnvelope: PersistedReplayEnvelopeV1,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedSuspendedInvocationRecordV1 = strictReadValue(json)
    }
}

data class PersistedSuspendedInvocationMetadataV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("approvalId") val approvalId: String,
    @JsonProperty("toolCallId") val toolCallId: String,
    @JsonProperty("toolName") val toolName: String,
    @JsonProperty("toolCallIndex") val toolCallIndex: Int,
    @JsonProperty("correlationId") val correlationId: String,
    @JsonProperty("identity") val identity: PersistedEngineExecutionIdentityV1,
    @JsonProperty("securityContext") val securityContext: PersistedExecutionSecurityContextV1,
    @JsonProperty("operationReference") val operationReference: PersistedResumeOperationReferenceV1,
    @JsonProperty("replayEnvelopeDigest") val replayEnvelopeDigest: String,
    @JsonProperty("conversationId") val conversationId: String?,
    @JsonProperty("historySize") val historySize: Int,
    @JsonProperty("tokenBudgetSnapshot") val tokenBudgetSnapshot: PersistedTokenBudgetSnapshotV1?,
    @JsonProperty("toolReference") val toolReference: PersistedResumeToolReferenceV1,
    @JsonProperty("toolSecurity") val toolSecurity: PersistedToolSecurityMetadataV1?,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedSuspendedInvocationMetadataV1 = strictReadValue(json)
    }
}

data class PersistedEngineExecutionIdentityV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("workflowRunId") val workflowRunId: String,
    @JsonProperty("correlationId") val correlationId: String,
    @JsonProperty("workflowDigest") val workflowDigest: String,
    @JsonProperty("policyVersion") val policyVersion: String,
    @JsonProperty("actorId") val actorId: String,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedEngineExecutionIdentityV1 = strictReadValue(json)
    }
}

data class PersistedExecutionSecurityContextV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("dataClassification") val dataClassification: String?,
    @JsonProperty("classificationSource") val classificationSource: String?,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedExecutionSecurityContextV1 = strictReadValue(json)
    }
}

data class PersistedResumeOperationReferenceV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("serviceInterface") val serviceInterface: String,
    @JsonProperty("methodName") val methodName: String,
    @JsonProperty("jvmMethodDescriptor") val jvmMethodDescriptor: String,
    @JsonProperty("resumeDefinitionDigest") val resumeDefinitionDigest: String,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedResumeOperationReferenceV1 = strictReadValue(json)
    }
}

data class PersistedResumeToolReferenceV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("toolName") val toolName: String,
    @JsonProperty("declarationDigest") val declarationDigest: String,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedResumeToolReferenceV1 = strictReadValue(json)
    }
}

data class PersistedTokenBudgetSnapshotV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("totalInputTokens") val totalInputTokens: Long,
    @JsonProperty("totalOutputTokens") val totalOutputTokens: Long,
    @JsonProperty("totalInputCost") val totalInputCost: Double,
    @JsonProperty("totalOutputCost") val totalOutputCost: Double,
    @JsonProperty("warnIfExceeded") val warnIfExceeded: Boolean,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedTokenBudgetSnapshotV1 = strictReadValue(json)
    }
}

data class PersistedToolSecurityMetadataV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("permission") val permission: String,
    @JsonProperty("risk") val risk: String,
    @JsonProperty("approval") val approval: String,
    @JsonProperty("managedNetworkEgress") val managedNetworkEgress: String,
    @JsonProperty("audit") val audit: String,
    @JsonProperty("compatibilityMode") val compatibilityMode: String,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedToolSecurityMetadataV1 = strictReadValue(json)
    }
}

data class PersistedReplayEnvelopeV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("messages") val messages: List<PersistedMessageV1>,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedReplayEnvelopeV1 = strictReadValue(json)
    }
}

data class PersistedMessageV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("role") val role: String,
    @JsonProperty("content") val content: String,
    @JsonProperty("contentParts") val contentParts: List<PersistedContentPartV1>?,
    @JsonProperty("toolCallId") val toolCallId: String?,
    @JsonProperty("toolCalls") val toolCalls: List<PersistedToolCallV1>?,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedMessageV1 = strictReadValue(json)
    }
}

data class PersistedToolCallV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("argumentsJson") val argumentsJson: String,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedToolCallV1 = strictReadValue(json)
    }
}

data class PersistedContentPartV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int,
    @JsonProperty("type") val type: String,
    @JsonProperty("text") val text: String?,
    @JsonProperty("mimeType") val mimeType: String?,
    @JsonProperty("dataBase64") val dataBase64: String?,
    @JsonProperty("url") val url: String?,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedContentPartV1 = strictReadValue(json)
    }
}

// ====================================================================
// Domain conversions — SuspendedInvocationMetadata
// ====================================================================

fun PersistedSuspendedInvocationMetadataV1.toDomain(): SuspendedInvocationMetadata =
    SuspendedInvocationMetadata(
        approvalId = approvalId,
        toolCallId = toolCallId,
        toolName = toolName,
        toolCallIndex = toolCallIndex,
        correlationId = correlationId,
        identity = identity.toDomain(),
        securityContext = securityContext.toDomain(),
        operationReference = operationReference.toDomain(),
        replayEnvelopeDigest = Sha256Digest.of(replayEnvelopeDigest),
        conversationId = conversationId,
        historySize = historySize,
        tokenBudgetSnapshot = tokenBudgetSnapshot?.toDomain(),
        toolReference = toolReference.toDomain(),
        toolSecurity = toolSecurity?.toDomain(),
    )

fun SuspendedInvocationMetadata.toPersistedV1(): PersistedSuspendedInvocationMetadataV1 =
    PersistedSuspendedInvocationMetadataV1(
        schemaVersion = 1,
        approvalId = approvalId,
        toolCallId = toolCallId,
        toolName = toolName,
        toolCallIndex = toolCallIndex,
        correlationId = correlationId,
        identity = identity.toPersistedV1(),
        securityContext = securityContext.toPersistedV1(),
        operationReference = operationReference.toPersistedV1(),
        replayEnvelopeDigest = replayEnvelopeDigest.value,
        conversationId = conversationId,
        historySize = historySize,
        tokenBudgetSnapshot = tokenBudgetSnapshot?.toPersistedV1(),
        toolReference = toolReference.toPersistedV1(),
        toolSecurity = toolSecurity?.toPersistedV1(),
    )

fun PersistedEngineExecutionIdentityV1.toDomain(): EngineExecutionIdentity = EngineExecutionIdentity(
    workflowRunId = workflowRunId,
    correlationId = correlationId,
    workflowDigest = Sha256Digest.of(workflowDigest),
    policyVersion = policyVersion,
    actorId = actorId,
)

fun EngineExecutionIdentity.toPersistedV1(): PersistedEngineExecutionIdentityV1 =
    PersistedEngineExecutionIdentityV1(
        schemaVersion = 1,
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        workflowDigest = workflowDigest.value,
        policyVersion = policyVersion,
        actorId = actorId,
    )

fun PersistedExecutionSecurityContextV1.toDomain(): ExecutionSecurityContext = ExecutionSecurityContext(
    dataClassification = dataClassification?.let(DataClassification::valueOf),
    classificationSource = classificationSource?.let(ClassificationSource::valueOf),
)

fun ExecutionSecurityContext.toPersistedV1(): PersistedExecutionSecurityContextV1 =
    PersistedExecutionSecurityContextV1(
        schemaVersion = 1,
        dataClassification = dataClassification?.name,
        classificationSource = classificationSource?.name,
    )

fun PersistedResumeOperationReferenceV1.toDomain(): ResumeOperationReference = ResumeOperationReference(
    serviceInterface = serviceInterface,
    methodName = methodName,
    jvmMethodDescriptor = jvmMethodDescriptor,
    resumeDefinitionDigest = Sha256Digest.of(resumeDefinitionDigest),
)

fun ResumeOperationReference.toPersistedV1(): PersistedResumeOperationReferenceV1 =
    PersistedResumeOperationReferenceV1(
        schemaVersion = 1,
        serviceInterface = serviceInterface,
        methodName = methodName,
        jvmMethodDescriptor = jvmMethodDescriptor,
        resumeDefinitionDigest = resumeDefinitionDigest.value,
    )

fun PersistedResumeToolReferenceV1.toDomain(): ResumeToolReference = ResumeToolReference(
    toolName = toolName,
    declarationDigest = Sha256Digest.of(declarationDigest),
)

fun ResumeToolReference.toPersistedV1(): PersistedResumeToolReferenceV1 =
    PersistedResumeToolReferenceV1(
        schemaVersion = 1,
        toolName = toolName,
        declarationDigest = declarationDigest.value,
    )

fun PersistedTokenBudgetSnapshotV1.toDomain(): TokenBudgetSnapshot = TokenBudgetSnapshot(
    totalInputTokens = totalInputTokens,
    totalOutputTokens = totalOutputTokens,
    totalInputCost = totalInputCost,
    totalOutputCost = totalOutputCost,
    warnIfExceeded = warnIfExceeded,
)

fun TokenBudgetSnapshot.toPersistedV1(): PersistedTokenBudgetSnapshotV1 =
    PersistedTokenBudgetSnapshotV1(
        schemaVersion = 1,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens,
        totalInputCost = totalInputCost,
        totalOutputCost = totalOutputCost,
        warnIfExceeded = warnIfExceeded,
    )

fun PersistedToolSecurityMetadataV1.toDomain(): ToolSecurityMetadata = ToolSecurityMetadata(
    permission = permission,
    risk = RiskLevel.valueOf(risk),
    approval = ApprovalMode.valueOf(approval),
    managedNetworkEgress = ManagedNetworkEgress.valueOf(managedNetworkEgress),
    audit = AuditDetail.valueOf(audit),
    compatibilityMode = CompatibilityMode.valueOf(compatibilityMode),
)

fun ToolSecurityMetadata.toPersistedV1(): PersistedToolSecurityMetadataV1 =
    PersistedToolSecurityMetadataV1(
        schemaVersion = 1,
        permission = permission,
        risk = risk.name,
        approval = approval.name,
        managedNetworkEgress = managedNetworkEgress.name,
        audit = audit.name,
        compatibilityMode = compatibilityMode.name,
    )

fun PersistedReplayEnvelopeV1.toDomain(): SensitiveReplayEnvelope =
    SensitiveReplayEnvelope.of(messages.map { it.toDomain() })

fun SensitiveReplayEnvelope.toPersistedV1(): PersistedReplayEnvelopeV1 =
    PersistedReplayEnvelopeV1(
        schemaVersion = 1,
        messages = revealForResume().messages.map { it.toPersistedV1() },
    )

fun PersistedMessageV1.toDomain(): Message = Message(
    role = MessageRole.valueOf(role),
    content = content,
    contentParts = contentParts?.map { it.toDomain() },
    toolCallId = toolCallId,
    toolCalls = toolCalls?.map { it.toDomain() },
)

fun Message.toPersistedV1(): PersistedMessageV1 = PersistedMessageV1(
    schemaVersion = 1,
    role = role.name,
    content = content,
    contentParts = contentParts?.map { it.toPersistedV1() },
    toolCallId = toolCallId,
    toolCalls = toolCalls?.map { it.toPersistedV1() },
)

fun PersistedToolCallV1.toDomain(): ToolCall = ToolCall(
    id = id,
    name = name,
    argumentsJson = argumentsJson,
)

fun ToolCall.toPersistedV1(): PersistedToolCallV1 = PersistedToolCallV1(
    schemaVersion = 1,
    id = id,
    name = name,
    argumentsJson = argumentsJson,
)

fun PersistedContentPartV1.toDomain(): ContentPart = when (type) {
    "text" -> ContentPart.TextPart(
        text = requireNotNull(text) { "persisted-content-part-text-missing" },
    )
    "image" -> ContentPart.ImagePart(
        mimeType = requireNotNull(mimeType) { "persisted-content-part-image-mime-missing" },
        data = Base64.getDecoder().decode(
            requireNotNull(dataBase64) { "persisted-content-part-image-data-missing" },
        ).copyOf(),
    )
    "image_url" -> ContentPart.ImageUrlContent(
        url = requireNotNull(url) { "persisted-content-part-image-url-missing" },
        mimeType = mimeType,
    )
    else -> error("persisted-content-part-type-unsupported")
}

fun ContentPart.toPersistedV1(): PersistedContentPartV1 = when (this) {
    is ContentPart.TextPart -> PersistedContentPartV1(
        schemaVersion = 1,
        type = "text",
        text = text,
        mimeType = null,
        dataBase64 = null,
        url = null,
    )
    is ContentPart.ImagePart -> PersistedContentPartV1(
        schemaVersion = 1,
        type = "image",
        text = null,
        mimeType = mimeType,
        dataBase64 = Base64.getEncoder().encodeToString(data),
        url = null,
    )
    is ContentPart.ImageUrlContent -> PersistedContentPartV1(
        schemaVersion = 1,
        type = "image_url",
        text = null,
        mimeType = mimeType,
        dataBase64 = null,
        url = url,
    )
}
