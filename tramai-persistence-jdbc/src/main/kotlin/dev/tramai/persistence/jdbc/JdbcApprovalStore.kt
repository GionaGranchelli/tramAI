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
import java.sql.SQLException
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
 * JSONB in `sanitized_metadata`. The `encrypted_payload` and associated encryption metadata
 * columns remain NULL — this store does not implement payload encryption.
 *
 * ## Security model: actor identity
 * - The indexed `decision_actor_hash` column stores a SHA-256 hash of the deciding
 *   actor identity. This column is queryable and does not contain raw actor IDs.
 * - Safe actor IDs (`requestedBy`, `decidedBy`, `consumedBy`) are persisted in the
 *   `sanitized_metadata` JSONB field to satisfy the [ApprovalStore] SPI contract.
 * - `decisionComment` is also stored in `sanitized_metadata`. It must not contain
 *   prompts, model output, PII, secrets, or raw tool arguments.
 *
 * ## Concurrency
 * Optimistic concurrency is enforced via the `version` column using `WHERE version = ?`
 * (CAS update). Operations run under default JDBC autocommit — each statement is an
 * implicit transaction. The CAS guard prevents lost updates even without explicit
 * transaction boundaries.
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
            requestedAt = request.requestedAt.toString(),
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
                } catch (e: SQLException) {
                    // Map PostgreSQL unique violation (23505) to domain exception;
                    // rethrow all other SQL failures accurately.
                    if (e.sqlState == "23505") {
                        throw ApprovalStoreConflictException(request.approvalId)
                    }
                    throw e
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
        validateTransitionInput(transition)

        dataSource.connection.use { conn ->
            val current = readCurrent(conn, approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
            if (current.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)

            val now = clock.instant()
            resolveNextStatus(current, transition, now)

            val nextVersion = incrementVersion(approvalId, current.version)
            val nowOdt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
            val metadata = parseMetadata(current.sanitizedMetadataJson)
            val decisionFields = transition.toDecisionFields()
            val actorHash = decisionFields.decidedBy?.let { sha256Hex(it) }
            val updatedMetadata = metadata.copy(
                decidedBy = decisionFields.decidedBy,
                decisionComment = decisionFields.comment,
            )
            val metadataJson = mapper.writeValueAsString(updatedMetadata)

            updateDecisionRow(
                DecisionRowUpdate(
                    conn = conn,
                    target = DecisionRowTarget(
                        approvalId = approvalId,
                        expectedVersion = expectedVersion,
                    ),
                    fields = DecisionRowFields(
                        decisionFields = decisionFields,
                        decidedAt = nowOdt,
                        actorHash = actorHash,
                        metadataJson = metadataJson,
                        nextVersion = nextVersion,
                    ),
                ),
            )

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
            }

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
        val requestedAt: String,
        val decidedBy: String?,
        val decisionComment: String?,
        val consumedBy: String?,
        val consumedAt: String?,
    )

    private data class DecisionFields(
        val decidedBy: String?,
        val comment: String?,
        val decisionType: String,
        val targetStatus: String,
    )

    private data class DecisionRowUpdate(
        val conn: java.sql.Connection,
        val target: DecisionRowTarget,
        val fields: DecisionRowFields,
    )

    private data class DecisionRowTarget(
        val approvalId: String,
        val expectedVersion: Long,
    )

    private data class DecisionRowFields(
        val decisionFields: DecisionFields,
        val decidedAt: OffsetDateTime,
        val actorHash: String?,
        val metadataJson: String,
        val nextVersion: Long,
    )

    /**
     * Validates actor and comment fields for a requested approval transition.
     */
    private fun validateTransitionInput(transition: ApprovalTransition) {
        transition.commentOrNull()?.let {
            require(it.length <= maxCommentLength) { "Comment exceeds maximum length of $maxCommentLength" }
        }
        transition.decidedByOrNull()?.let {
            validateIdField(it, "decidedBy", maxIdLength)
            SafeActorIdPolicy.validateActorId(it, "decidedBy")
        }
    }

    /**
     * Converts a domain transition into stored decision fields.
     */
    private fun ApprovalTransition.toDecisionFields(): DecisionFields =
        DecisionFields(
            decidedBy = decidedByOrNull(),
            comment = commentOrNull(),
            decisionType = targetStatusWireValue(),
            targetStatus = targetStatusWireValue(),
        )

    /**
     * Extracts the human decision actor from non-timeout transitions.
     */
    private fun ApprovalTransition.decidedByOrNull(): String? = when (this) {
        is ApprovalTransition.Approve -> decidedBy
        is ApprovalTransition.Deny -> decidedBy
        is ApprovalTransition.Timeout -> null
    }

    /**
     * Extracts the optional human decision comment from non-timeout transitions.
     */
    private fun ApprovalTransition.commentOrNull(): String? = when (this) {
        is ApprovalTransition.Approve -> comment
        is ApprovalTransition.Deny -> comment
        is ApprovalTransition.Timeout -> null
    }

    /**
     * Returns the database status and decision type value for a transition.
     */
    private fun ApprovalTransition.targetStatusWireValue(): String = when (this) {
        is ApprovalTransition.Approve -> "APPROVED"
        is ApprovalTransition.Deny -> "DENIED"
        is ApprovalTransition.Timeout -> "TIMED_OUT"
    }

    /**
     * Performs the optimistic-concurrency decision update.
     */
    private fun updateDecisionRow(update: DecisionRowUpdate) {
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
        update.conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, update.fields.decisionFields.targetStatus)
            stmt.setObject(2, update.fields.decidedAt)
            if (update.fields.actorHash != null) {
                stmt.setString(3, update.fields.actorHash)
            } else {
                stmt.setNull(3, java.sql.Types.VARCHAR)
            }
            stmt.setString(4, update.fields.decisionFields.decisionType)
            stmt.setString(5, update.fields.metadataJson)
            stmt.setLong(6, update.fields.nextVersion)
            stmt.setString(7, update.target.approvalId)
            stmt.setLong(8, update.target.expectedVersion)

            val updated = stmt.executeUpdate()
            if (updated == 0) throw ApprovalStoreConflictException(update.target.approvalId)
        }
    }

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
            requestedAt = Instant.parse(metadata.requestedAt),
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
        check(!json.isNullOrBlank()) {
            "sanitized_metadata must not be null for a stored approval"
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
