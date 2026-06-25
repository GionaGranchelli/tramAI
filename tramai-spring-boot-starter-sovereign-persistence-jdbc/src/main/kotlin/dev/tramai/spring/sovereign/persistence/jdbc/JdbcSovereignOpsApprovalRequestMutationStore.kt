package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ToolCall
import dev.tramai.engine.EngineExecutionIdentity
import dev.tramai.engine.ExecutionSecurityContext
import dev.tramai.engine.ResumeOperationReference
import dev.tramai.engine.ResumeToolReference
import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.ReplayEnvelopeDigestHelper
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.TokenBudgetSnapshot
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.persistence.jdbc.JdbcContinuationArgumentsCodec
import dev.tramai.persistence.jdbc.JdbcEncryptedContinuationArguments
import dev.tramai.persistence.jdbc.JdbcEncryptedReplayEnvelope
import dev.tramai.persistence.jdbc.JdbcReplayEnvelopeCodec
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalRequestMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

class JdbcSovereignOpsApprovalRequestMutationStore(
    private val dataSource: DataSource,
    private val replayEnvelopeCodec: JdbcReplayEnvelopeCodec,
    private val continuationArgumentsCodec: JdbcContinuationArgumentsCodec,
    private val outboxPayloadCodec: JdbcOpsAuditOutboxPayloadCodec,
    private val clock: Clock = Clock.systemUTC(),
) : SovereignOpsApprovalRequestMutationStore {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    override suspend fun createApprovalRequest(
        request: ApprovalGatewayPersistenceRequest,
        auditIntent: SovereignOpsAuditOutboxRecord?,
    ): SovereignOpsApprovalRequestMutationResult {
        validateRequest(request, auditIntent)

        return dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val existing = selectApproval(conn, request.approvalRequest.approvalId)
                if (existing != null) {
                    conn.commit()
                    return@use SovereignOpsApprovalRequestMutationResult.Existing(existing)
                }

                insertApproval(conn, request.approvalRequest)
                insertSuspendedInvocation(
                    conn = conn,
                    metadata = request.suspendedInvocationMetadata,
                    replayEnvelope = request.replayEnvelope,
                )
                insertContinuation(
                    conn = conn,
                    continuation = request.continuation,
                    sensitiveArguments = request.sensitiveArguments,
                )

                if (auditIntent != null) {
                    val preparedPayload = mapper.writeValueAsBytes(auditIntent.toPersistedOutbox())
                    val preparedEncrypted = outboxPayloadCodec.encode(preparedPayload)
                    insertPreparedOutbox(conn, auditIntent, preparedEncrypted)

                    val pendingAuditIntent = auditIntent.copy(
                        status = SovereignOpsAuditOutboxStatus.PENDING,
                    )
                    val pendingPayload = mapper.writeValueAsBytes(pendingAuditIntent.toPersistedOutbox())
                    val pendingEncrypted = outboxPayloadCodec.encode(pendingPayload)
                    markPreparedOutboxPending(conn, pendingAuditIntent, pendingEncrypted)
                }

                conn.commit()
                SovereignOpsApprovalRequestMutationResult.Created(
                    approvalId = request.approvalRequest.approvalId,
                    correlationId = request.suspendedInvocationMetadata.correlationId,
                    resumeToken = request.resumeToken,
                )
            } catch (e: SQLException) {
                conn.rollback()
                if (isApprovalPrimaryKeyViolation(e)) {
                    val existing = selectApprovalAfterRollback(request.approvalRequest.approvalId)
                    if (existing != null) {
                        return@use SovereignOpsApprovalRequestMutationResult.Existing(existing)
                    }
                }
                throw IllegalStateException(
                    "tramai-sovereign-ops-approval-request-mutation-database-failure",
                    e,
                )
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    private fun validateRequest(
        request: ApprovalGatewayPersistenceRequest,
        auditIntent: SovereignOpsAuditOutboxRecord?,
    ) {
        val approval = request.approvalRequest
        val continuation = request.continuation
        val suspended = request.suspendedInvocationMetadata
        validateIdField(approval.approvalId, "approvalId")
        validateIdField(approval.requestedBy, "requestedBy")
        SafeActorIdPolicy.validateActorId(approval.requestedBy, "requestedBy")
        require(approval.status == ApprovalStatus.PENDING) {
            "tramai-sovereign-ops-approval-request-invalid-status"
        }
        require(approval.version == 0L) {
            "tramai-sovereign-ops-approval-request-invalid-version"
        }
        require(approval.decidedBy == null && approval.decidedAt == null && approval.decisionComment == null) {
            "tramai-sovereign-ops-approval-request-must-not-be-decided"
        }
        require(approval.consumedBy == null && approval.consumedAt == null) {
            "tramai-sovereign-ops-approval-request-must-not-be-consumed"
        }

        val now = clock.instant()
        require(approval.requestedAt <= now) {
            "tramai-sovereign-ops-approval-request-requested-at-in-future"
        }
        require(approval.expiresAt > now) {
            "tramai-sovereign-ops-approval-request-expired-at-creation"
        }
        require(approval.expiresAt > approval.requestedAt) {
            "tramai-sovereign-ops-approval-request-invalid-expiry"
        }
        require(Duration.between(approval.requestedAt, approval.expiresAt) <= Duration.ofMinutes(15)) {
            "tramai-sovereign-ops-approval-request-exceeds-max-ttl"
        }

        require(continuation.approvalExpiresAt > now) {
            "tramai-sovereign-ops-approval-request-continuation-expired-at-creation"
        }

        // Replay-envelope digest verification: recompute canonical digest from actual
        // replay-envelope messages and verify it matches the metadata digest.
        // This mirrors JdbcSuspendedInvocationStore.validateReplayEnvelopeDigest.
        val replayMessages = request.replayEnvelope.revealForResume().messages
        val canonicalDigest = ReplayEnvelopeDigestHelper.compute(
            request.suspendedInvocationMetadata.operationReference,
            replayMessages,
        )
        require(canonicalDigest == request.suspendedInvocationMetadata.replayEnvelopeDigest) {
            "tramai-sovereign-ops-replay-envelope-digest-mismatch: " +
                "canonical=$canonicalDigest, provided=${request.suspendedInvocationMetadata.replayEnvelopeDigest}"
        }

        require(continuation.approvalId == approval.approvalId) {
            "tramai-sovereign-ops-approval-request-continuation-id-mismatch"
        }
        require(continuation.status == ApprovalContinuationStatus.PENDING) {
            "tramai-sovereign-ops-approval-request-invalid-continuation-status"
        }
        require(continuation.version == 0L) {
            "tramai-sovereign-ops-approval-request-invalid-continuation-version"
        }
        // Mirrors JdbcApprovalContinuationStore.create validation
        require(continuation.claimedBy == null && continuation.claimedAt == null) {
            "tramai-sovereign-ops-approval-request-continuation-must-not-be-claimed"
        }
        require(continuation.completedAt == null) {
            "tramai-sovereign-ops-approval-request-continuation-must-not-be-completed"
        }
        validateIdField(continuation.workflowRunId, "continuation.workflowRunId")
        validateIdField(continuation.correlationId, "continuation.correlationId")
        validateIdField(continuation.toolCallId, "continuation.toolCallId")
        validateIdField(continuation.toolName, "continuation.toolName")
        validateDigestField(continuation.argumentsDigest.value)
        require(!continuation.policyVersion.isNullOrBlank()) {
            "tramai-sovereign-ops-approval-request-continuation-missing-policy-version"
        }
        validateDigestField(continuation.workflowDigest.value)
        require(continuation.approvalExpiresAt > continuation.createdAt) {
            "tramai-sovereign-ops-approval-request-continuation-invalid-expiry"
        }
        require(Duration.between(continuation.createdAt, continuation.approvalExpiresAt) <= Duration.ofMinutes(15)) {
            "tramai-sovereign-ops-approval-request-continuation-exceeds-max-ttl"
        }
        require(suspended.approvalId == approval.approvalId) {
            "tramai-sovereign-ops-approval-request-suspended-id-mismatch"
        }

        auditIntent?.let {
            require(it.status == SovereignOpsAuditOutboxStatus.PREPARED) {
                "tramai-sovereign-ops-outbox-invalid-status"
            }
        }
    }

    private fun insertApproval(conn: Connection, request: ApprovalRequest) {
        val now = clock.instant()
        val nowOdt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        val metadata = ApprovalMetadata(
            binding = BindingMetadata(
                workflowRunId = request.binding.workflowRunId,
                toolName = request.binding.toolName,
                argumentsDigest = request.binding.argumentsDigest.value,
                policyVersion = request.binding.policyVersion,
                workflowDigest = request.binding.workflowDigest.value,
                approvalTokenDigest = request.binding.approvalTokenDigest.value,
            ),
            requestedBy = request.requestedBy,
            expiresAt = request.expiresAt.toString(),
            requestedAt = request.requestedAt.toString(),
            decidedBy = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
        )
        val sql = """
            INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
            VALUES (?, 'PENDING', ?, ?::jsonb, 0)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, request.approvalId)
            stmt.setObject(2, nowOdt)
            stmt.setString(3, mapper.writeValueAsString(metadata))
            stmt.executeUpdate()
        }
    }

    private fun insertSuspendedInvocation(
        conn: Connection,
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) {
        val payload = SuspendedPayload(
            metadata = SuspendedPayloadMetadata.fromDomain(metadata, mapper),
            persistedMessages = replayEnvelope.revealForResume().messages.map { it.toPersisted() },
        )
        val encrypted = replayEnvelopeCodec.encode(mapper.writeValueAsBytes(payload))
        val sql = """
            INSERT INTO suspended_invocations (
                invocation_id, status, service_key, operation_key, descriptor_hash,
                replay_envelope_digest, encrypted_replay_envelope,
                encryption_key_id, encryption_algorithm, encryption_nonce, payload_digest,
                version, created_at
            ) VALUES (
                ?, 'PENDING', ?, ?, ?,
                ?, ?,
                ?, ?, ?, ?,
                1, ?
            )
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, metadata.approvalId)
            stmt.setString(2, metadata.operationReference.serviceInterface)
            stmt.setString(3, metadata.operationReference.methodName)
            stmt.setString(4, metadata.operationReference.resumeDefinitionDigest.value)
            stmt.setString(5, metadata.replayEnvelopeDigest.value)
            stmt.setBytes(6, encrypted.ciphertext)
            stmt.setString(7, encrypted.keyId)
            stmt.setString(8, encrypted.algorithm)
            stmt.setBytes(9, encrypted.nonce)
            stmt.setString(10, encrypted.payloadDigest)
            stmt.setObject(11, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            stmt.executeUpdate()
        }
    }

    private fun insertContinuation(
        conn: Connection,
        continuation: ApprovalContinuation,
        sensitiveArguments: SensitiveToolArguments,
    ) {
        val encrypted = continuationArgumentsCodec.encode(
            sensitiveArguments.reveal().toByteArray(Charsets.UTF_8),
        )
        val sql = """
            INSERT INTO approval_continuations (
                approval_id, status, version, created_at, approval_expires_at,
                workflow_run_id, correlation_id, tool_call_id, tool_name,
                arguments_digest, policy_version, workflow_digest,
                encrypted_arguments, encryption_key_id, encryption_algorithm,
                encryption_nonce, payload_digest
            ) VALUES (
                ?, 'PENDING', 0, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?,
                ?, ?
            )
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, continuation.approvalId)
            stmt.setObject(2, OffsetDateTime.ofInstant(continuation.createdAt, ZoneOffset.UTC))
            stmt.setObject(3, OffsetDateTime.ofInstant(continuation.approvalExpiresAt, ZoneOffset.UTC))
            stmt.setString(4, continuation.workflowRunId)
            stmt.setString(5, continuation.correlationId)
            stmt.setString(6, continuation.toolCallId)
            stmt.setString(7, continuation.toolName)
            stmt.setString(8, continuation.argumentsDigest.value)
            stmt.setString(9, continuation.policyVersion)
            stmt.setString(10, continuation.workflowDigest.value)
            bindEncryptedArguments(stmt, encrypted)
            stmt.executeUpdate()
        }
    }

    private fun bindEncryptedArguments(
        stmt: java.sql.PreparedStatement,
        encrypted: JdbcEncryptedContinuationArguments,
    ) {
        stmt.setBytes(11, encrypted.ciphertext)
        stmt.setString(12, encrypted.keyId)
        stmt.setString(13, encrypted.algorithm)
        stmt.setBytes(14, encrypted.nonce)
        stmt.setString(15, encrypted.payloadDigest)
    }

    private fun selectApproval(conn: Connection, approvalId: String): ApprovalRequest? {
        val sql = """
            SELECT approval_id, status, created_at, decided_at, decision_actor_hash, decision_type,
                   sanitized_metadata, version
            FROM approvals
            WHERE approval_id = ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapToApprovalRequest(rs) else null
            }
        }
    }

    private fun selectApprovalAfterRollback(approvalId: String): ApprovalRequest? =
        dataSource.connection.use { conn -> selectApproval(conn, approvalId) }

    private fun mapToApprovalRequest(rs: ResultSet): ApprovalRequest {
        val metadata = mapper.readValue<ApprovalMetadata>(rs.getString("sanitized_metadata"))
        return ApprovalRequest(
            approvalId = rs.getString("approval_id"),
            binding = ApprovalBinding(
                workflowRunId = metadata.binding.workflowRunId,
                toolName = metadata.binding.toolName,
                argumentsDigest = Sha256Digest.of(metadata.binding.argumentsDigest),
                policyVersion = metadata.binding.policyVersion,
                workflowDigest = Sha256Digest.of(metadata.binding.workflowDigest),
                approvalTokenDigest = Sha256Digest.of(metadata.binding.approvalTokenDigest),
            ),
            status = ApprovalStatus.valueOf(rs.getString("status")),
            requestedBy = metadata.requestedBy,
            requestedAt = Instant.parse(metadata.requestedAt),
            expiresAt = Instant.parse(metadata.expiresAt),
            decidedBy = metadata.decidedBy,
            decidedAt = rs.getObject("decided_at", OffsetDateTime::class.java)?.toInstant(),
            decisionComment = metadata.decisionComment,
            consumedBy = metadata.consumedBy,
            consumedAt = metadata.consumedAt?.let(Instant::parse),
            version = rs.getLong("version"),
        )
    }

    private fun insertPreparedOutbox(
        conn: Connection,
        record: SovereignOpsAuditOutboxRecord,
        encrypted: JdbcEncryptedAuditOutboxPayload,
    ) {
        val sql = """
            INSERT INTO audit_outbox (
                outbox_id, event_key, status, correlation_key_hash,
                created_at, attempt_count,
                encrypted_payload, encryption_key_id, encryption_algorithm,
                encryption_nonce, payload_digest, version
            ) VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, 1)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, record.outboxId)
            stmt.setString(2, record.eventKey)
            stmt.setString(3, record.status.name)
            stmt.setString(4, record.aggregateIdDigest)
            stmt.setTimestamp(5, java.sql.Timestamp.from(record.createdAt))
            stmt.setBytes(6, encrypted.ciphertext)
            stmt.setString(7, encrypted.keyId)
            stmt.setString(8, encrypted.algorithm)
            stmt.setBytes(9, encrypted.nonce)
            stmt.setString(10, encrypted.payloadDigest)
            stmt.executeUpdate()
        }
    }

    private fun markPreparedOutboxPending(
        conn: Connection,
        record: SovereignOpsAuditOutboxRecord,
        encrypted: JdbcEncryptedAuditOutboxPayload,
    ) {
        val sql = """
            UPDATE audit_outbox
            SET status = 'PENDING',
                encrypted_payload = ?,
                encryption_key_id = ?,
                encryption_algorithm = ?,
                encryption_nonce = ?,
                payload_digest = ?,
                version = version + 1
            WHERE outbox_id = ? AND status = 'PREPARED'
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setBytes(1, encrypted.ciphertext)
            stmt.setString(2, encrypted.keyId)
            stmt.setString(3, encrypted.algorithm)
            stmt.setBytes(4, encrypted.nonce)
            stmt.setString(5, encrypted.payloadDigest)
            stmt.setString(6, record.outboxId)
            check(stmt.executeUpdate() == 1) {
                "tramai-sovereign-ops-outbox-concurrent-update"
            }
        }
    }

    private fun isApprovalPrimaryKeyViolation(error: SQLException): Boolean =
        error.sqlState == "23505" &&
            (error.message?.contains("approvals_pkey", ignoreCase = true) == true ||
                error.message?.contains("approval_id", ignoreCase = true) == true)

    private fun validateIdField(value: String, fieldName: String) {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= 256) { "$fieldName exceeds maximum length of 256" }
    }

    private fun validateDigestField(value: String) {
        require(value.matches(Regex("^sha256:[0-9a-f]{64}$"))) {
            "tramai-sovereign-ops-digest-field-must-be-sha256"
        }
    }
}

private data class BindingMetadata(
    val workflowRunId: String,
    val toolName: String,
    val argumentsDigest: String,
    val policyVersion: String,
    val workflowDigest: String,
    val approvalTokenDigest: String,
)

private data class ApprovalMetadata(
    val binding: BindingMetadata,
    val requestedBy: String,
    val expiresAt: String,
    val requestedAt: String,
    val decidedBy: String?,
    val decisionComment: String?,
    val consumedBy: String?,
    val consumedAt: String?,
)

private data class PersistedMessage(
    val role: String,
    val content: String,
    val toolCalls: List<PersistedToolCall>?,
)

private data class PersistedToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

private fun Message.toPersisted(): PersistedMessage = PersistedMessage(
    role = role.name,
    content = content,
    toolCalls = toolCalls?.map {
        PersistedToolCall(
            id = it.id,
            name = it.name,
            argumentsJson = it.argumentsJson,
        )
    },
)

private data class SuspendedPayload(
    val metadata: SuspendedPayloadMetadata,
    val persistedMessages: List<PersistedMessage>,
)

private data class SuspendedPayloadMetadata(
    val approvalId: String,
    val toolCallId: String,
    val toolName: String,
    val toolCallIndex: Int,
    val correlationId: String,
    val identityWorkflowRunId: String,
    val identityCorrelationId: String,
    val identityWorkflowDigest: String,
    val identityPolicyVersion: String,
    val identityActorId: String,
    val securityDataClassification: String?,
    val securityClassificationSource: String?,
    val operationServiceInterface: String,
    val operationMethodName: String,
    val operationJvmMethodDescriptor: String,
    val operationResumeDefinitionDigest: String,
    val replayEnvelopeDigest: String,
    val conversationId: String?,
    val historySize: Int,
    val tokenBudgetTotalInputTokens: Long?,
    val tokenBudgetTotalOutputTokens: Long?,
    val tokenBudgetTotalInputCost: Double?,
    val tokenBudgetTotalOutputCost: Double?,
    val tokenBudgetWarnIfExceeded: Boolean?,
    val toolReferenceName: String,
    val toolReferenceDeclarationDigest: String,
    val toolSecurity: String?,
) {
    companion object {
        fun fromDomain(
            metadata: SuspendedInvocationMetadata,
            mapper: ObjectMapper,
        ): SuspendedPayloadMetadata = SuspendedPayloadMetadata(
            approvalId = metadata.approvalId,
            toolCallId = metadata.toolCallId,
            toolName = metadata.toolName,
            toolCallIndex = metadata.toolCallIndex,
            correlationId = metadata.correlationId,
            identityWorkflowRunId = metadata.identity.workflowRunId,
            identityCorrelationId = metadata.identity.correlationId,
            identityWorkflowDigest = metadata.identity.workflowDigest.value,
            identityPolicyVersion = metadata.identity.policyVersion,
            identityActorId = metadata.identity.actorId,
            securityDataClassification = metadata.securityContext.dataClassification?.name,
            securityClassificationSource = metadata.securityContext.classificationSource?.name,
            operationServiceInterface = metadata.operationReference.serviceInterface,
            operationMethodName = metadata.operationReference.methodName,
            operationJvmMethodDescriptor = metadata.operationReference.jvmMethodDescriptor,
            operationResumeDefinitionDigest = metadata.operationReference.resumeDefinitionDigest.value,
            replayEnvelopeDigest = metadata.replayEnvelopeDigest.value,
            conversationId = metadata.conversationId,
            historySize = metadata.historySize,
            tokenBudgetTotalInputTokens = metadata.tokenBudgetSnapshot?.totalInputTokens,
            tokenBudgetTotalOutputTokens = metadata.tokenBudgetSnapshot?.totalOutputTokens,
            tokenBudgetTotalInputCost = metadata.tokenBudgetSnapshot?.totalInputCost,
            tokenBudgetTotalOutputCost = metadata.tokenBudgetSnapshot?.totalOutputCost,
            tokenBudgetWarnIfExceeded = metadata.tokenBudgetSnapshot?.warnIfExceeded,
            toolReferenceName = metadata.toolReference.toolName,
            toolReferenceDeclarationDigest = metadata.toolReference.declarationDigest.value,
            toolSecurity = metadata.toolSecurity?.let { mapper.writeValueAsString(it) },
        )
    }
}
