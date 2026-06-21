package dev.tramai.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotConsumableException
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.ApprovalStoreTokenRejectedException
import dev.tramai.core.exception.IllegalApprovalTransitionException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * JDBC-backed [ApprovalStore] implementation using the `approvals` table in PostgreSQL.
 *
 * Approval request data that does not map to dedicated columns is stored as structured
 * JSONB in [ApprovalMetadata.sanitizedMetadata]. The [encrypted_payload] and associated
 * encryption metadata columns remain NULL — this store does not implement payload encryption.
 *
 * Optimistic concurrency is enforced via the `version` column using `WHERE version = ?`.
 *
 * @param dataSource The [DataSource] providing connections to the PostgreSQL database.
 * @param clock The clock used for timestamp generation.
 * @param maxIdLength Maximum length for identifier fields (default 256).
 * @param maxCommentLength Maximum length for decision comments (default 4096).
 * @param maxCreationTtl Maximum TTL for approval request creation (default 15 minutes).
 */
class JdbcApprovalStore(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    private val maxIdLength: Int = 256,
    private val maxCommentLength: Int = 4096,
    private val maxCreationTtl: Duration = Duration.ofMinutes(15),
) : ApprovalStore {

    init {
        require(maxCreationTtl > Duration.ZERO) {
            "maxCreationTtl must be positive"
        }
    }

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        require(request.version == 0L) { "Initial approval version must be 0, got ${request.version}" }
        require(request.status == ApprovalStatus.PENDING) { "Initial approval status must be PENDING, got ${request.status}" }
        require(request.decidedBy == null) { "Initial approval must not have decidedBy set" }
        require(request.decidedAt == null) { "Initial approval must not have decidedAt set" }
        require(request.decisionComment == null) { "Initial approval must not have decisionComment set" }

        validateIdField(request.approvalId, "approvalId", maxIdLength)
        validateIdField(request.requestedBy, "requestedBy", maxIdLength)
        SafeActorIdPolicy.validateActorId(request.requestedBy, "requestedBy")

        val binding = request.binding
        validateIdField(binding.workflowRunId, "workflowRunId", maxIdLength)
        validateIdField(binding.toolName, "toolName", maxIdLength)
        validateIdField(binding.policyVersion, "policyVersion", maxIdLength)

        val now = clock.instant()
        require(request.expiresAt > now) { "expiresAt must be in the future, got $now for expiry ${request.expiresAt}" }
        require(request.expiresAt > request.requestedAt) { "expiresAt must be after requestedAt" }
        require(request.requestedAt <= now) { "requestedAt must not be in the future, got ${request.requestedAt} for now $now" }
        require(request.consumedBy == null) { "Initial approval must not have consumedBy set" }
        require(request.consumedAt == null) { "Initial approval must not have consumedAt set" }

        val ttl = Duration.between(request.requestedAt, request.expiresAt)
        require(ttl <= maxCreationTtl) {
            "expiresAt exceeds maximum creation TTL of $maxCreationTtl"
        }

        val metadata = ApprovalMetadata(
            binding = BindingMetadata(
                workflowRunId = binding.workflowRunId,
                toolName = binding.toolName,
                argumentsDigest = binding.argumentsDigest.value,
                policyVersion = binding.policyVersion,
                workflowDigest = binding.workflowDigest.value,
                approvalTokenDigest = binding.approvalTokenDigest.value,
            ),
            requestedBy = request.requestedBy,
            expiresAt = request.expiresAt.toString(),
            decidedBy = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
        )
        val metadataJson = mapper.writeValueAsString(metadata)
        val nowOdt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)

        dataSource.connection.use { conn ->
            val sql = """
                INSERT INTO approvals (approval_id, status, created_at, sanitized_metadata, version)
                VALUES (?, 'PENDING', ?, ?::jsonb, 0)
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, request.approvalId)
                stmt.setObject(2, nowOdt)
                stmt.setString(3, metadataJson)
                try {
                    stmt.executeUpdate()
                } catch (e: Exception) {
                    // If the PK already exists (duplicate approval_id), PostgreSQL will throw
                    // a unique violation. Remap to the domain exception.
                    throw ApprovalStoreConflictException(request.approvalId)
                }
            }
        }

        return request
    }

    override suspend fun get(approvalId: String): ApprovalRequest? {
        validateIdField(approvalId, "approvalId", maxIdLength)

        dataSource.connection.use { conn ->
            val sql = """
                SELECT approval_id, status, created_at, decided_at, decision_actor_hash, decision_type,
                       sanitized_metadata, version
                FROM approvals
                WHERE approval_id = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, approvalId)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return mapToApprovalRequest(mapToRow(rs))
                }
            }
        }
    }

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest {
        validateIdField(approvalId, "approvalId", maxIdLength)

        when (transition) {
            is ApprovalTransition.Approve -> transition.comment?.let {
                require(it.length <= maxCommentLength) { "Comment exceeds maximum length of $maxCommentLength" }
            }
            is ApprovalTransition.Deny -> transition.comment?.let {
                require(it.length <= maxCommentLength) { "Comment exceeds maximum length of $maxCommentLength" }
            }
            is ApprovalTransition.Timeout -> {}
        }

        when (transition) {
            is ApprovalTransition.Approve -> {
                validateIdField(transition.decidedBy, "decidedBy", maxIdLength)
                SafeActorIdPolicy.validateActorId(transition.decidedBy, "decidedBy")
            }
            is ApprovalTransition.Deny -> {
                validateIdField(transition.decidedBy, "decidedBy", maxIdLength)
                SafeActorIdPolicy.validateActorId(transition.decidedBy, "decidedBy")
            }
            is ApprovalTransition.Timeout -> {}
        }

        dataSource.connection.use { conn ->
            // Read current state
            val current = readCurrent(conn, approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)

            if (current.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)

            val now = clock.instant()
            resolveNextStatus(current, transition, now)

            val nextVersion = incrementVersion(approvalId, current.version)
            val nowOdt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)

            // Build updated metadata
            val metadata = parseMetadata(current.sanitizedMetadataJson)

            val decidedBy = when (transition) {
                is ApprovalTransition.Approve -> transition.decidedBy
                is ApprovalTransition.Deny -> transition.decidedBy
                is ApprovalTransition.Timeout -> null
            }
            val decisionComment = when (transition) {
                is ApprovalTransition.Approve -> transition.comment
                is ApprovalTransition.Deny -> transition.comment
                is ApprovalTransition.Timeout -> null
            }
            val decisionType = when (transition) {
                is ApprovalTransition.Approve -> "APPROVED"
                is ApprovalTransition.Deny -> "DENIED"
                is ApprovalTransition.Timeout -> "TIMED_OUT"
            }
            val targetStatus = when (transition) {
                is ApprovalTransition.Approve -> "APPROVED"
                is ApprovalTransition.Deny -> "DENIED"
                is ApprovalTransition.Timeout -> "TIMED_OUT"
            }

            // Compute sanitized actor hash
            val actorHash = decidedBy?.let { sha256Hex(it) }

            // Update metadata with decision fields
            val updatedMetadata = metadata.copy(
                decidedBy = decidedBy,
                decisionComment = decisionComment,
            )
            val metadataJson = mapper.writeValueAsString(updatedMetadata)

            // Atomic CAS update
            val sql = """
                UPDATE approvals
                SET status = ?,
                    decided_at = ?,
                    decision_actor_hash = ?,
                    decision_type = ?,
                    sanitized_metadata = ?::jsonb,
                    version = ?
                WHERE approval_id = ? AND version = ?
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, targetStatus)
                stmt.setObject(2, nowOdt)
                if (actorHash != null) stmt.setString(3, actorHash) else stmt.setNull(3, java.sql.Types.VARCHAR)
                stmt.setString(4, decisionType)
                stmt.setString(5, metadataJson)
                stmt.setLong(6, nextVersion)
                stmt.setString(7, approvalId)
                stmt.setLong(8, expectedVersion)

                val updated = stmt.executeUpdate()
                if (updated == 0) throw ApprovalStoreConflictException(approvalId)
            }

            val updatedCurrent = readCurrent(conn, approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
            return mapToApprovalRequest(updatedCurrent)
        }
    }

    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalConsumptionReceipt {
        validateIdField(approvalId, "approvalId", maxIdLength)
        validateIdField(consumedBy, "consumedBy", maxIdLength)
        SafeActorIdPolicy.validateActorId(consumedBy, "consumedBy")

        dataSource.connection.use { conn ->
            val current = readCurrent(conn, approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
            val req = mapToApprovalRequest(current)

            if (req.status != ApprovalStatus.APPROVED) throw ApprovalStoreNotConsumableException(approvalId)

            if (!tokenDigestsMatch(presentedTokenDigest, req.binding.approvalTokenDigest)) {
                throw ApprovalStoreTokenRejectedException(approvalId)
            }

            if (req.consumedAt == null && req.consumedBy == null) {
                // Fresh consumption
                if (req.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)

                val now = clock.instant()
                if (now >= req.expiresAt) throw ApprovalStoreNotConsumableException(approvalId)

                val nextVersion = incrementVersion(approvalId, req.version)
                val nowOdt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)

                val metadata = parseMetadata(current.sanitizedMetadataJson)
                val updatedMetadata = metadata.copy(
                    consumedBy = consumedBy,
                    consumedAt = now.toString(),
                )
                val metadataJson = mapper.writeValueAsString(updatedMetadata)

                val sql = """
                    UPDATE approvals
                    SET sanitized_metadata = ?::jsonb,
                        version = ?
                    WHERE approval_id = ? AND version = ?
                """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, metadataJson)
                    stmt.setLong(2, nextVersion)
                    stmt.setString(3, approvalId)
                    stmt.setLong(4, expectedVersion)

                    val updated = stmt.executeUpdate()
                    if (updated == 0) throw ApprovalStoreConflictException(approvalId)
                }

                val updatedCurrent = readCurrent(conn, approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
                return ApprovalConsumptionReceipt(
                    request = mapToApprovalRequest(updatedCurrent),
                    replayed = false,
                )
            } else {
                // Replay path
                if (req.consumedAt == null || req.consumedBy == null) {
                    throw ApprovalStoreNotConsumableException(approvalId)
                }
                if (req.consumedBy != consumedBy) throw ApprovalStoreNotConsumableException(approvalId)

                val replayVersion = try {
                    Math.addExact(expectedVersion, 1L)
                } catch (_: ArithmeticException) {
                    throw ApprovalStoreConflictException(approvalId)
                }
                if (req.version != replayVersion) throw ApprovalStoreConflictException(approvalId)

                return ApprovalConsumptionReceipt(request = req, replayed = true)
            }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────

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
        val decidedBy: String?,
        val decisionComment: String?,
        val consumedBy: String?,
        val consumedAt: String?,
    )

    private fun readCurrent(conn: java.sql.Connection, approvalId: String): ApprovalRow? {
        val sql = """
            SELECT approval_id, status, created_at, decided_at, decision_actor_hash, decision_type,
                   sanitized_metadata, version
            FROM approvals
            WHERE approval_id = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                return mapToRow(rs)
            }
        }
    }

    private fun mapToRow(rs: ResultSet): ApprovalRow = ApprovalRow(
        approvalId = rs.getString("approval_id"),
        status = rs.getString("status"),
        createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
        decidedAt = rs.getObject("decided_at", OffsetDateTime::class.java),
        decisionActorHash = rs.getString("decision_actor_hash"),
        decisionType = rs.getString("decision_type"),
        sanitizedMetadataJson = rs.getString("sanitized_metadata"),
        version = rs.getLong("version"),
    )

    private fun mapToApprovalRequest(row: ApprovalRow): ApprovalRequest {
        val metadata = parseMetadata(row.sanitizedMetadataJson)

        val status = ApprovalStatus.valueOf(row.status)
        val decidedBy = metadata.decidedBy
        val decidedAt = row.decidedAt?.toInstant()
        val decisionComment = metadata.decisionComment
        val consumedBy = metadata.consumedBy
        val consumedAt = metadata.consumedAt?.let { Instant.parse(it) }

        val binding = ApprovalBinding(
            workflowRunId = metadata.binding.workflowRunId,
            toolName = metadata.binding.toolName,
            argumentsDigest = Sha256Digest.of(metadata.binding.argumentsDigest),
            policyVersion = metadata.binding.policyVersion,
            workflowDigest = Sha256Digest.of(metadata.binding.workflowDigest),
            approvalTokenDigest = Sha256Digest.of(metadata.binding.approvalTokenDigest),
        )

        return ApprovalRequest(
            approvalId = row.approvalId,
            binding = binding,
            status = status,
            requestedBy = metadata.requestedBy,
            requestedAt = row.createdAt.toInstant(),
            expiresAt = Instant.parse(metadata.expiresAt),
            decidedBy = decidedBy,
            decidedAt = decidedAt,
            decisionComment = decisionComment,
            consumedBy = consumedBy,
            consumedAt = consumedAt,
            version = row.version,
        )
    }

    private fun parseMetadata(json: String?): ApprovalMetadata {
        if (json == null || json.isBlank()) {
            throw IllegalStateException("sanitized_metadata must not be null for a stored approval")
        }
        return mapper.readValue(json)
    }

    private fun incrementVersion(approvalId: String, version: Long): Long =
        try {
            Math.addExact(version, 1L)
        } catch (_: ArithmeticException) {
            throw ApprovalStoreConflictException(approvalId)
        }

    private fun tokenDigestsMatch(
        presentedTokenDigest: Sha256Digest,
        storedTokenDigest: Sha256Digest,
    ): Boolean =
        MessageDigest.isEqual(
            presentedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
            storedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
        )

    private fun resolveNextStatus(
        current: ApprovalRow,
        transition: ApprovalTransition,
        now: Instant,
    ): ApprovalStatus {
        val status = ApprovalStatus.valueOf(current.status)
        val expiresAt = parseMetadata(current.sanitizedMetadataJson).expiresAt.let { Instant.parse(it) }

        return when (status) {
            ApprovalStatus.PENDING -> {
                if (now >= expiresAt) {
                    if (transition is ApprovalTransition.Timeout) {
                        return ApprovalStatus.TIMED_OUT
                    }
                    throw IllegalApprovalTransitionException(
                        current.approvalId, status, transition.targetStatus(),
                        "approval has expired at $expiresAt",
                    )
                }
                when (transition) {
                    is ApprovalTransition.Approve -> ApprovalStatus.APPROVED
                    is ApprovalTransition.Deny -> ApprovalStatus.DENIED
                    is ApprovalTransition.Timeout -> {
                        throw IllegalApprovalTransitionException(
                            current.approvalId, status, transition.targetStatus(),
                            "Cannot time out approval before expiry at $expiresAt",
                        )
                    }
                }
            }
            ApprovalStatus.APPROVED -> throw IllegalApprovalTransitionException(
                current.approvalId, status, transition.targetStatus(), "approval already granted",
            )
            ApprovalStatus.DENIED -> throw IllegalApprovalTransitionException(
                current.approvalId, status, transition.targetStatus(), "approval already denied",
            )
            ApprovalStatus.TIMED_OUT -> throw IllegalApprovalTransitionException(
                current.approvalId, status, transition.targetStatus(), "approval already timed out",
            )
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        val hex = hashBytes.joinToString("") { "%02x".format(it) }
        return "sha256:$hex"
    }

    private fun validateIdField(value: String, fieldName: String, maxLength: Int): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= maxLength) { "$fieldName exceeds maximum length of $maxLength" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
        return trimmed
    }
}
