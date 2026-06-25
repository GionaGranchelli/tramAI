package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.approval.Sha256Digest
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
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

class JdbcSovereignOpsApprovalMutationStore(
    private val dataSource: DataSource,
    private val payloadCodec: JdbcOpsAuditOutboxPayloadCodec,
    private val clock: Clock = Clock.systemUTC(),
    private val maxIdLength: Int = 256,
    private val maxCommentLength: Int = 4096,
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
        return mutateApprovalWithAuditIntent(
            approvalId = approvalId,
            expectedVersion = expectedVersion,
            actor = actor,
            reason = reason,
            auditIntent = auditIntent,
            transition = ApprovalTransition.Deny(decidedBy = actor, comment = reason),
        )
    }

    override suspend fun approveApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsApprovalMutationResult {
        return mutateApprovalWithAuditIntent(
            approvalId = approvalId,
            expectedVersion = expectedVersion,
            actor = actor,
            reason = reason,
            auditIntent = auditIntent,
            transition = ApprovalTransition.Approve(decidedBy = actor, comment = reason),
        )
    }

    private suspend fun mutateApprovalWithAuditIntent(
        approvalId: String,
        expectedVersion: Long,
        actor: String,
        reason: String,
        auditIntent: SovereignOpsAuditOutboxRecord,
        transition: ApprovalTransition,
    ): SovereignOpsApprovalMutationResult {
        // ── Input validation ──────────────────────────────────────────
        validateIdField(approvalId, "approvalId")
        validateIdField(actor, "decidedBy")
        SafeActorIdPolicy.validateActorId(actor, "decidedBy")

        require(reason.length <= maxCommentLength) {
            "Comment exceeds maximum length of $maxCommentLength"
        }

        require(auditIntent.outboxId.isNotBlank()) {
            "Outbox ID must not be blank"
        }
        require(auditIntent.eventKey.isNotBlank()) {
            "Event key must not be blank"
        }
        require(auditIntent.status == SovereignOpsAuditOutboxStatus.PREPARED) {
            "tramai-sovereign-ops-outbox-invalid-status: only PREPARED records can be appended"
        }

        // ── Transactional mutation ────────────────────────────────────
        return dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val current = selectApprovalForUpdate(conn, approvalId)
                    ?: throw ApprovalStoreNotFoundException(approvalId)

                // Guard: status
                if (current.status != ApprovalStatus.PENDING.name) {
                    val targetStatus = transition.targetStatus()
                    throw IllegalApprovalTransitionException(
                        approvalId = approvalId,
                        from = ApprovalStatus.valueOf(current.status),
                        to = targetStatus,
                        reason = "approval already ${current.status.lowercase()}",
                    )
                }

                // Guard: version
                if (current.version != expectedVersion) {
                    throw IllegalStateException("tramai-sovereign-ops-approval-version-conflict")
                }

                // Guard: expiry (match JdbcApprovalStore.transition behavior)
                val metadata = parseMetadata(current.sanitizedMetadataJson)
                val expiresAt = Instant.parse(metadata.expiresAt)
                val now = clock.instant()
                if (!now.isBefore(expiresAt)) {
                    val targetStatus = transition.targetStatus()
                    throw IllegalApprovalTransitionException(
                        approvalId = approvalId,
                        from = ApprovalStatus.PENDING,
                        to = targetStatus,
                        reason = "approval has expired at $expiresAt",
                    )
                }

                // Insert outbox as PREPARED
                val preparedPayload = mapper.writeValueAsBytes(auditIntent.toPersistedOutbox())
                val preparedEncrypted = payloadCodec.encode(preparedPayload)
                insertPreparedOutbox(conn, auditIntent, preparedEncrypted)

                // Update approval
                val decidedAt = Timestamp.from(now)
                val nextVersion = incrementVersion(approvalId, expectedVersion)
                val updatedMetadata = metadata.copy(
                    decidedBy = actor,
                    decisionComment = reason,
                )
                val metadataJson = mapper.writeValueAsString(updatedMetadata)
                val targetStatus = transition.targetStatus()

                updateApprovalRow(
                    conn = conn,
                    approvalId = approvalId,
                    expectedVersion = expectedVersion,
                    decidedAt = decidedAt,
                    actorHash = sha256Hex(actor),
                    metadataJson = metadataJson,
                    targetStatus = targetStatus,
                )

                // Mark outbox PENDING
                val pendingAuditIntent = auditIntent.copy(
                    approvalStatus = targetStatus.name,
                    approvalVersion = nextVersion,
                    status = SovereignOpsAuditOutboxStatus.PENDING,
                )
                val pendingPayload = mapper.writeValueAsBytes(pendingAuditIntent.toPersistedOutbox())
                val pendingEncrypted = payloadCodec.encode(pendingPayload)

                markPreparedOutboxPending(conn, pendingAuditIntent, pendingEncrypted)

                // Re-read for return value
                val updated = selectApproval(conn, approvalId)
                    ?: throw ApprovalStoreNotFoundException(approvalId)
                val approval = mapToApprovalRequest(updated)

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

    // ── SQL helpers ──────────────────────────────────────────────────

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
        targetStatus: ApprovalStatus,
    ) {
        val sql = """
            UPDATE approvals
            SET status = ?,
                decided_at = ?,
                decision_actor_hash = ?,
                decision_type = ?,
                sanitized_metadata = ?::jsonb,
                version = version + 1
            WHERE approval_id = ? AND version = ? AND status = 'PENDING'
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, targetStatus.name)
            stmt.setTimestamp(2, decidedAt)
            stmt.setString(3, actorHash)
            stmt.setString(4, targetStatus.name)
            stmt.setString(5, metadataJson)
            stmt.setString(6, approvalId)
            stmt.setLong(7, expectedVersion)
            val updated = stmt.executeUpdate()
            if (updated != 1) {
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

    // ── Domain mapping ───────────────────────────────────────────────

    private fun mapToApprovalRequest(row: ApprovalRow): ApprovalRequest {
        val metadata = parseMetadata(row.sanitizedMetadataJson)

        return ApprovalRequest(
            approvalId = row.approvalId,
            binding = ApprovalBinding(
                workflowRunId = metadata.binding.workflowRunId,
                toolName = metadata.binding.toolName,
                argumentsDigest = Sha256Digest.of(metadata.binding.argumentsDigest),
                policyVersion = metadata.binding.policyVersion,
                workflowDigest = Sha256Digest.of(metadata.binding.workflowDigest),
                approvalTokenDigest = Sha256Digest.of(metadata.binding.approvalTokenDigest),
            ),
            status = ApprovalStatus.valueOf(row.status),
            requestedBy = metadata.requestedBy,
            requestedAt = Instant.parse(metadata.requestedAt),
            expiresAt = Instant.parse(metadata.expiresAt),
            decidedBy = metadata.decidedBy,
            decidedAt = row.decidedAt?.toInstant(),
            decisionComment = metadata.decisionComment,
            consumedBy = metadata.consumedBy,
            consumedAt = metadata.consumedAt?.let { Instant.parse(it) },
            version = row.version,
        )
    }

    private fun parseMetadata(json: String?): ApprovalMetadata {
        check(!json.isNullOrBlank()) {
            "sanitized_metadata must not be null for a stored approval"
        }
        return mapper.readValue(json)
    }

    // ── Validation ───────────────────────────────────────────────────

    private fun validateIdField(value: String, fieldName: String) {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= maxIdLength) { "$fieldName exceeds maximum length of $maxIdLength" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
    }

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

    // ── Data classes ─────────────────────────────────────────────────

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

    /**
     * Typed metadata model matching [JdbcApprovalStore.ApprovalMetadata].
     * Absence of any required field is a fail-closed condition — no fallbacks.
     */
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
        val decidedBy: String? = null,
        val decisionComment: String? = null,
        val consumedBy: String? = null,
        val consumedAt: String? = null,
    )
}
