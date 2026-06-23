package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.IllegalApprovalTransitionException
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

class JdbcSovereignOpsApprovalMutationStore(
    private val dataSource: DataSource,
    private val payloadCodec: JdbcOpsAuditOutboxPayloadCodec,
    private val clock: Clock = Clock.systemUTC(),
) : SovereignOpsApprovalMutationStore {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    override suspend fun denyApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult {
        require(auditIntent.status == SovereignOpsAuditOutboxStatus.PREPARED) {
            "tramai-sovereign-ops-outbox-invalid-status: only PREPARED records can be appended"
        }

        return dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val current = selectApprovalForUpdate(conn, approvalId)
                    ?: throw IllegalStateException("tramai-sovereign-ops-invalid-approval-id")

                if (current.status != ApprovalStatus.PENDING.name) {
                    throw IllegalApprovalTransitionException(
                        approvalId = approvalId,
                        from = ApprovalStatus.valueOf(current.status),
                        to = ApprovalStatus.DENIED,
                        reason = "approval already ${current.status.lowercase()}",
                    )
                }

                if (current.version != expectedVersion) {
                    throw IllegalStateException("tramai-sovereign-ops-approval-version-conflict")
                }

                val preparedPayload = mapper.writeValueAsBytes(auditIntent.toPersistedOutbox())
                val preparedEncrypted = payloadCodec.encode(preparedPayload)
                insertPreparedOutbox(conn, auditIntent, preparedEncrypted)

                val now = clock.instant()
                val decidedAt = Timestamp.from(now)
                val nextVersion = incrementVersion(approvalId, expectedVersion)
                val metadataJson = updatedMetadataJson(current.sanitizedMetadataJson, actor, reason, current.createdAt)

                updateApprovalRow(
                    conn = conn,
                    approvalId = approvalId,
                    expectedVersion = expectedVersion,
                    decidedAt = decidedAt,
                    actorHash = sha256Hex(actor),
                    metadataJson = metadataJson,
                )

                val pendingAuditIntent = auditIntent.copy(
                    approvalStatus = ApprovalStatus.DENIED.name,
                    approvalVersion = nextVersion,
                    status = SovereignOpsAuditOutboxStatus.PENDING,
                )
                val pendingPayload = mapper.writeValueAsBytes(pendingAuditIntent.toPersistedOutbox())
                val pendingEncrypted = payloadCodec.encode(pendingPayload)

                markPreparedOutboxPending(conn, pendingAuditIntent, pendingEncrypted)

                val updated = selectApproval(conn, approvalId)
                    ?: throw IllegalStateException("tramai-sovereign-ops-invalid-approval-id")
                val approval = updated.toDomain()

                conn.commit()
                SovereignOpsApprovalMutationResult(
                    approval = approval,
                    auditOutboxRecord = pendingAuditIntent,
                )
            } catch (e: SQLException) {
                conn.rollback()
                throw mapDatabaseException(e, auditIntent)
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    private fun selectApprovalForUpdate(conn: Connection, approvalId: String): ApprovalRow? {
        val sql = """
            SELECT approval_id, status, created_at, decided_at, decision_actor_hash, decision_type,
                   sanitized_metadata, version
            FROM approvals
            WHERE approval_id = ?
            FOR UPDATE
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapApprovalRow(rs) else null
            }
        }
    }

    private fun selectApproval(conn: Connection, approvalId: String): ApprovalRow? {
        val sql = """
            SELECT approval_id, status, created_at, decided_at, decision_actor_hash, decision_type,
                   sanitized_metadata, version
            FROM approvals
            WHERE approval_id = ?
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) mapApprovalRow(rs) else null
            }
        }
    }

    private fun mapApprovalRow(rs: ResultSet): ApprovalRow = ApprovalRow(
        approvalId = rs.getString("approval_id"),
        status = rs.getString("status"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        decidedAt = rs.getObject("decided_at", OffsetDateTime::class.java),
        decisionActorHash = rs.getString("decision_actor_hash"),
        decisionType = rs.getString("decision_type"),
        sanitizedMetadataJson = rs.getString("sanitized_metadata"),
        version = rs.getLong("version"),
    )

    private fun updatedMetadataJson(
        currentJson: String?,
        actor: String,
        reason: String,
        createdAt: OffsetDateTime,
    ): String {
        val node = parseMetadataNode(currentJson)
        val mutable = if (node != null && node.isObject) {
            node.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        } else {
            mapper.createObjectNode()
        }
        mutable.put("decidedBy", actor)
        mutable.put("decisionComment", reason)
        if (!mutable.has("requestedAt")) {
            mutable.put("requestedAt", createdAt.toInstant().toString())
        }
        if (!mutable.has("expiresAt")) {
            mutable.put(
                "expiresAt",
                createdAt.toInstant().plus(DEFAULT_FALLBACK_EXPIRY).toString(),
            )
        }
        if (!mutable.has("requestedBy")) {
            mutable.put("requestedBy", DEFAULT_REQUESTED_BY)
        }
        return mapper.writeValueAsString(mutable)
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
            stmt.setTimestamp(5, Timestamp.from(record.createdAt))
            stmt.setBytes(6, encrypted.ciphertext)
            stmt.setString(7, encrypted.keyId)
            stmt.setString(8, encrypted.algorithm)
            stmt.setBytes(9, encrypted.nonce)
            stmt.setString(10, encrypted.payloadDigest)
            stmt.executeUpdate()
        }
    }

    private fun updateApprovalRow(
        conn: Connection,
        approvalId: String,
        expectedVersion: Long,
        decidedAt: Timestamp,
        actorHash: String,
        metadataJson: String,
    ) {
        val sql = """
            UPDATE approvals
            SET status = 'DENIED',
                decided_at = ?,
                decision_actor_hash = ?,
                decision_type = 'DENIED',
                sanitized_metadata = ?::jsonb,
                version = version + 1
            WHERE approval_id = ? AND version = ? AND status = 'PENDING'
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, decidedAt)
            stmt.setString(2, actorHash)
            stmt.setString(3, metadataJson)
            stmt.setString(4, approvalId)
            stmt.setLong(5, expectedVersion)
            val updated = stmt.executeUpdate()
            if (updated != 1) {
                // Version or status mismatch. Application-level guards above
                // check both, so this is belt-and-suspenders.
                throw IllegalStateException("tramai-sovereign-ops-approval-update-failed")
            }
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
            val updated = stmt.executeUpdate()
            check(updated == 1) { "tramai-sovereign-ops-outbox-concurrent-update" }
        }
    }

    private fun ApprovalRow.toDomain(): ApprovalRequest {
        val node = parseMetadataNode(sanitizedMetadataJson)
        val binding = bindingFrom(node)

        return ApprovalRequest(
            approvalId = approvalId,
            binding = binding,
            status = ApprovalStatus.valueOf(status),
            requestedBy = node.textValue("requestedBy") ?: DEFAULT_REQUESTED_BY,
            requestedAt = node.instantValue("requestedAt") ?: createdAt.toInstant(),
            expiresAt = node.instantValue("expiresAt")
                ?: createdAt.toInstant().plus(DEFAULT_FALLBACK_EXPIRY),
            decidedBy = node.textValue("decidedBy"),
            decidedAt = decidedAt?.toInstant(),
            decisionComment = node.textValue("decisionComment"),
            consumedBy = node.textValue("consumedBy"),
            consumedAt = node.instantValue("consumedAt"),
            version = version,
        )
    }

    private fun bindingFrom(node: JsonNode?): ApprovalBinding {
        val bindingNode = node?.get("binding")
        return ApprovalBinding(
            workflowRunId = bindingNode.textValue("workflowRunId") ?: DEFAULT_WORKFLOW_RUN_ID,
            toolName = bindingNode.textValue("toolName") ?: DEFAULT_TOOL_NAME,
            argumentsDigest = Sha256Digest.of(
                bindingNode.textValue("argumentsDigest") ?: DEFAULT_DIGEST,
            ),
            policyVersion = bindingNode.textValue("policyVersion") ?: DEFAULT_POLICY_VERSION,
            workflowDigest = Sha256Digest.of(
                bindingNode.textValue("workflowDigest") ?: DEFAULT_DIGEST,
            ),
            approvalTokenDigest = Sha256Digest.of(
                bindingNode.textValue("approvalTokenDigest") ?: DEFAULT_DIGEST,
            ),
        )
    }

    private fun parseMetadataNode(json: String?): JsonNode? {
        if (json.isNullOrBlank()) return null
        return mapper.readTree(json)
    }

    private fun JsonNode?.textValue(field: String): String? =
        this?.get(field)?.takeUnless { it.isNull }?.asText()

    private fun JsonNode?.instantValue(field: String): Instant? =
        textValue(field)?.let(Instant::parse)

    private fun incrementVersion(approvalId: String, version: Long): Long =
        try {
            Math.addExact(version, 1L)
        } catch (_: ArithmeticException) {
            throw IllegalStateException("tramai-sovereign-ops-approval-version-overflow")
        }

    private fun mapDatabaseException(
        e: SQLException,
        record: SovereignOpsAuditOutboxRecord,
    ): RuntimeException {
        val message = e.message ?: ""
        return when {
            message.contains("uq_audit_outbox_event_key") || message.contains("audit_outbox_event_key_key") ->
                IllegalStateException("tramai-sovereign-ops-outbox-duplicate-event-key: ${record.eventKey}")
            message.contains("audit_outbox_pkey") ->
                IllegalStateException("tramai-sovereign-ops-outbox-duplicate-id: ${record.outboxId}")
            else -> IllegalStateException("tramai-sovereign-ops-approval-mutation-database-failure", e)
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        return "sha256:${hashBytes.joinToString("") { "%02x".format(it) }}"
    }

    private data class ApprovalRow(
        val approvalId: String,
        val status: String,
        val createdAt: OffsetDateTime,
        val decidedAt: OffsetDateTime?,
        val decisionActorHash: String?,
        val decisionType: String?,
        val sanitizedMetadataJson: String?,
        val version: Long,
    )

    private companion object {
        val DEFAULT_FALLBACK_EXPIRY: Duration = Duration.ofMinutes(15)
        const val DEFAULT_REQUESTED_BY: String = "unknown-requestor"
        const val DEFAULT_WORKFLOW_RUN_ID: String = "unknown-workflow-run"
        const val DEFAULT_TOOL_NAME: String = "unknown-tool"
        const val DEFAULT_POLICY_VERSION: String = "unknown-policy"
        const val DEFAULT_DIGEST: String =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000"
    }
}
