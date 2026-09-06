package dev.tramai.persistence.jdbc

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import dev.tramai.core.exception.ApprovalContinuationNotCompletableException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
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

/**
 * JDBC-backed [ApprovalContinuationStore] implementation using the
 * `approval_continuations` table in PostgreSQL (V2 schema).
 *
 * ## Data model
 * Non-sensitive continuation metadata is stored in dedicated columns.
 * Raw tool arguments ([SensitiveToolArguments]) are encrypted via an injectable
 * [JdbcContinuationArgumentsCodec] before persistence and stored in the
 * `encrypted_arguments` BYTEA column with full encryption metadata.
 *
 * ## State machine
 * ```
 * PENDING (arguments encrypted, version=0 on create)
 *   → claimForExecution → CLAIMED (arguments cleared, version++)
 *   → expire (only after approvalExpiresAt) → EXPIRED
 *   → cancel → CANCELLED
 *
 * CLAIMED (arguments already released)
 *   → complete → COMPLETED
 *   → findStaleClaimed + forceCancelClaimed → CANCELLED_UNCERTAIN
 *
 * COMPLETED / EXPIRED / CANCELLED / CANCELLED_UNCERTAIN → terminal
 * ```
 *
 * ## Concurrency
 * Optimistic concurrency via `UPDATE ... WHERE version = ?` (CAS).
 * Each mutation increments `version` and checks the expected version.
 * The CAS guard prevents lost updates even without explicit transaction boundaries.
 *
 * ## Security model
 * - Raw tool arguments are encrypted at rest via the injected codec.
 * - All encryption metadata columns (`encryption_key_id`, `encryption_algorithm`,
 *   `encryption_nonce`, `payload_digest`) are populated when arguments are stored.
 * - On [claimForExecution], arguments are atomically released: the record is updated
 *   with `encrypted_arguments = NULL` and the decrypted arguments are returned
 *   via [ClaimedApprovalContinuation].
 * - Actor identities (`claimedBy`, `completedBy`, `cancelledBy`) are validated via
 *   [SafeActorIdPolicy].
 * - Digests (`argumentsDigest`, `workflowDigest`) are stored in queryable columns
 *   as `sha256:<hex>` strings — they contain hashes, not raw values.
 *
 * @param dataSource The [DataSource] providing PostgreSQL connections.
 * @param argumentsCodec The codec for encrypting/decrypting [SensitiveToolArguments].
 * @param clock The clock for timestamp generation.
 * @param maxIdLength Maximum length for identifier fields (default 256).
 * @param maxContinuationTtl Maximum TTL for pending continuations (default 15 minutes).
 */
class JdbcApprovalContinuationStore(
    private val dataSource: DataSource,
    private val argumentsCodec: JdbcContinuationArgumentsCodec,
    private val clock: Clock = Clock.systemUTC(),
    private val maxIdLength: Int = 256,
    private val maxContinuationTtl: Duration = Duration.ofMinutes(15),
) : ApprovalContinuationStore {
    init {
        require(maxContinuationTtl > Duration.ZERO) {
            "maxContinuationTtl must be positive"
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ApprovalContinuationStore SPI
    // ══════════════════════════════════════════════════════════════════

    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation =
        withSafeJdbc({ "Database operation failed for continuation: ${continuation.approvalId}" }) {
            validateCreateInput(continuation, arguments)

            val now = clock.instant()
            validateTemporalInvariants(continuation, now)

            val encrypted =
                argumentsCodec.encode(
                    arguments.reveal().toByteArray(Charsets.UTF_8),
                )
            val nowOdt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)

            dataSource.connection.use { conn ->
                val sql =
                    """
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
                    stmt.setBytes(11, encrypted.ciphertext)
                    stmt.setString(12, encrypted.keyId)
                    stmt.setString(13, encrypted.algorithm)
                    stmt.setBytes(14, encrypted.nonce)
                    stmt.setString(15, encrypted.payloadDigest)

                    try {
                        stmt.executeUpdate()
                    } catch (e: SQLException) {
                        if (e.sqlState == "23505") {
                            throw ApprovalContinuationConflictException(continuation.approvalId)
                        }
                        throw e
                    }
                }
            }

            continuation
        }

    override suspend fun get(approvalId: String): ApprovalContinuation? =
        withSafeJdbc({ "Database operation failed for continuation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")

            dataSource.connection.use { conn ->
                val row = readCurrent(conn, approvalId) ?: return@use null

                // Lazy expiry: PENDING only — CLAIMED never lazily expires
                val now = clock.instant()
                val (updated, expired) = maybeExpireLazy(row, now)
                if (expired) {
                    val applied = applyLazyExpiry(updated, row.version)
                    if (!applied) {
                        // CAS lost: another writer changed the row first.
                        // Re-read to return the actual persisted state.
                        return@use readCurrent(conn, approvalId)?.toDomain()
                    }
                }

                updated.toDomain()
            }
        }

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ClaimedApprovalContinuation =
        withSafeJdbc({ "Database operation failed for continuation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")
            validateIdField(claimedBy, "claimedBy")
            SafeActorIdPolicy.validateActorId(claimedBy, "claimedBy")

            dataSource.connection.use { conn ->
                val row =
                    readCurrent(conn, approvalId)
                        ?: throw ApprovalContinuationNotFoundException(approvalId)

                val now = clock.instant()
                val (normalized, expired) = maybeExpireLazy(row, now)

                if (expired) {
                    // Best-effort: try to expire the row. If CAS loses (another
                    // writer won), the row is no longer PENDING — still not claimable.
                    applyLazyExpiry(normalized, row.version)
                    throw ApprovalContinuationNotClaimableException(approvalId)
                }

                val currentStatus = ApprovalContinuationStatus.valueOf(normalized.status)

                // Version before status: a stale expectedVersion is a concurrency
                // conflict regardless of what state the row is in (same contract
                // as the in-memory and file stores).
                if (normalized.version != expectedVersion) {
                    throw ApprovalContinuationConflictException(approvalId)
                }

                if (currentStatus != ApprovalContinuationStatus.PENDING) {
                    throw ApprovalContinuationNotClaimableException(approvalId)
                }

                val newVersion = incrementVersion(approvalId, normalized.version)
                val nowOdt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)

                // Decrypt arguments BEFORE the CAS update.
                // If decryption fails, no DB mutation has occurred — the row stays PENDING
                // with encrypted arguments intact.
                val capturedArguments = decryptArguments(row)

                // CAS update: must still be PENDING at the expected version
                val updateSql =
                    """
                    UPDATE approval_continuations
                    SET status = 'CLAIMED',
                        version = ?,
                        claimed_by = ?,
                        claimed_at = ?,
                        encrypted_arguments = NULL,
                        encryption_key_id = NULL,
                        encryption_algorithm = NULL,
                        encryption_nonce = NULL,
                        payload_digest = NULL
                    WHERE approval_id = ?
                      AND version = ?
                      AND status = 'PENDING'
                    """.trimIndent()
                conn.prepareStatement(updateSql).use { stmt ->
                    stmt.setLong(1, newVersion)
                    stmt.setString(2, claimedBy)
                    stmt.setObject(3, nowOdt)
                    stmt.setString(4, approvalId)
                    stmt.setLong(5, expectedVersion)

                    val updated = stmt.executeUpdate()
                    if (updated == 0) {
                        // CAS lost. Re-read and apply the same precedence as the
                        // initial path: version first, then status. In-memory and
                        // file stores serialize per ID, so a claim that loses to
                        // a cancel observes version 1 vs expected 0 and reports
                        // Conflict — the CAS-loss branch must not diverge into
                        // NotClaimable for the same interleaving.
                        val current =
                            readCurrent(conn, approvalId)
                                ?: throw ApprovalContinuationNotFoundException(approvalId)
                        if (current.version != expectedVersion) {
                            throw ApprovalContinuationConflictException(approvalId)
                        }
                        if (ApprovalContinuationStatus.valueOf(current.status) != ApprovalContinuationStatus.PENDING) {
                            throw ApprovalContinuationNotClaimableException(approvalId)
                        }
                        throw ApprovalContinuationConflictException(approvalId)
                    }
                }

                val claimedContinuation =
                    normalizeRow(
                        approvalId = approvalId,
                        status = "CLAIMED",
                        newVersion = newVersion,
                        claimedBy = claimedBy,
                        claimedAt = nowOdt,
                        completedAt = null,
                        base = normalized,
                    )

                ClaimedApprovalContinuation(
                    continuation = claimedContinuation.toDomain(),
                    arguments = SensitiveToolArguments.of(capturedArguments),
                )
            }
        }

    override suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ): ApprovalContinuation =
        withSafeJdbc({ "Database operation failed for continuation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")
            validateIdField(completedBy, "completedBy")
            SafeActorIdPolicy.validateActorId(completedBy, "completedBy")

            dataSource.connection.use { conn ->
                val row =
                    readCurrent(conn, approvalId)
                        ?: throw ApprovalContinuationNotFoundException(approvalId)

                if (row.version != expectedVersion) {
                    throw ApprovalContinuationConflictException(approvalId)
                }

                val status = ApprovalContinuationStatus.valueOf(row.status)
                if (status != ApprovalContinuationStatus.CLAIMED) {
                    throw ApprovalContinuationNotCompletableException(approvalId)
                }

                if (row.claimedBy != completedBy) {
                    throw ApprovalContinuationNotCompletableException(approvalId)
                }

                val newVersion = incrementVersion(approvalId, row.version)
                val nowOdt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

                val sql =
                    """
                    UPDATE approval_continuations
                    SET status = 'COMPLETED',
                        version = ?,
                        completed_at = ?
                    WHERE approval_id = ?
                      AND version = ?
                      AND status = 'CLAIMED'
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, newVersion)
                    stmt.setObject(2, nowOdt)
                    stmt.setString(3, approvalId)
                    stmt.setLong(4, expectedVersion)

                    val updated = stmt.executeUpdate()
                    if (updated == 0) {
                        throw ApprovalContinuationConflictException(approvalId)
                    }
                }

                val updatedContinuation =
                    normalizeRow(
                        approvalId = approvalId,
                        status = "COMPLETED",
                        newVersion = newVersion,
                        claimedBy = row.claimedBy,
                        claimedAt = row.claimedAt,
                        completedAt = nowOdt,
                        base = row,
                    )
                updatedContinuation.toDomain()
            }
        }

    override suspend fun expire(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation =
        withSafeJdbc({ "Database operation failed for continuation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")

            dataSource.connection.use { conn ->
                val row =
                    readCurrent(conn, approvalId)
                        ?: throw ApprovalContinuationNotFoundException(approvalId)

                if (row.version != expectedVersion) {
                    throw ApprovalContinuationConflictException(approvalId)
                }

                val status = ApprovalContinuationStatus.valueOf(row.status)
                if (status != ApprovalContinuationStatus.PENDING) {
                    throw ApprovalContinuationConflictException(approvalId)
                }

                val now = clock.instant()
                if (now < row.approvalExpiresAt.toInstant()) {
                    throw ApprovalContinuationConflictException(approvalId)
                }

                val newVersion = incrementVersion(approvalId, row.version)
                val sql =
                    """
                    UPDATE approval_continuations
                    SET status = 'EXPIRED',
                        version = ?,
                        encrypted_arguments = NULL,
                        encryption_key_id = NULL,
                        encryption_algorithm = NULL,
                        encryption_nonce = NULL,
                        payload_digest = NULL
                    WHERE approval_id = ?
                      AND version = ?
                      AND status = 'PENDING'
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, newVersion)
                    stmt.setString(2, approvalId)
                    stmt.setLong(3, expectedVersion)

                    val updated = stmt.executeUpdate()
                    if (updated == 0) {
                        throw ApprovalContinuationConflictException(approvalId)
                    }
                }

                val updatedContinuation =
                    normalizeRow(
                        approvalId = approvalId,
                        status = "EXPIRED",
                        newVersion = newVersion,
                        claimedBy = null,
                        claimedAt = null,
                        completedAt = null,
                        base = row,
                    )
                updatedContinuation.toDomain()
            }
        }

    override suspend fun cancel(
        approvalId: String,
        expectedVersion: Long,
    ): ApprovalContinuation =
        withSafeJdbc({ "Database operation failed for continuation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")

            dataSource.connection.use { conn ->
                val row =
                    readCurrent(conn, approvalId)
                        ?: throw ApprovalContinuationNotFoundException(approvalId)

                // Lazy expiry for PENDING only — a late cancel must first persist
                // EXPIRED, then fail with a conflict (same contract as the
                // in-memory and file stores).
                val now = clock.instant()
                val (normalized, expired) = maybeExpireLazy(row, now)
                if (expired) {
                    applyLazyExpiry(normalized, row.version)
                    throw ApprovalContinuationConflictException(approvalId)
                }

                val status = ApprovalContinuationStatus.valueOf(normalized.status)
                if (normalized.version != expectedVersion) {
                    throw ApprovalContinuationConflictException(approvalId)
                }
                if (status != ApprovalContinuationStatus.PENDING) {
                    throw ApprovalContinuationConflictException(approvalId)
                }

                val newVersion = incrementVersion(approvalId, normalized.version)
                val sql =
                    """
                    UPDATE approval_continuations
                    SET status = 'CANCELLED',
                        version = ?,
                        encrypted_arguments = NULL,
                        encryption_key_id = NULL,
                        encryption_algorithm = NULL,
                        encryption_nonce = NULL,
                        payload_digest = NULL
                    WHERE approval_id = ?
                      AND version = ?
                      AND status = 'PENDING'
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, newVersion)
                    stmt.setString(2, approvalId)
                    stmt.setLong(3, expectedVersion)

                    val updated = stmt.executeUpdate()
                    if (updated == 0) {
                        throw ApprovalContinuationConflictException(approvalId)
                    }
                }

                val updatedContinuation =
                    normalizeRow(
                        approvalId = approvalId,
                        status = "CANCELLED",
                        newVersion = newVersion,
                        claimedBy = null,
                        claimedAt = null,
                        completedAt = null,
                        base = normalized,
                    )
                updatedContinuation.toDomain()
            }
        }

    override suspend fun findStaleClaimed(
        claimedBefore: Instant,
        limit: Int,
    ): List<ApprovalContinuation> =
        withSafeJdbc({ "Database operation failed for finding stale continuations" }) {
            require(limit in 1..100) { "limit must be between 1 and 100" }

            dataSource.connection.use { conn ->
                val sql =
                    """
                    SELECT approval_id, status, version, created_at, approval_expires_at,
                           workflow_run_id, correlation_id, tool_call_id, tool_name,
                           arguments_digest, policy_version, workflow_digest,
                           claimed_by, claimed_at, completed_at,
                           recovery_resolved_by, recovery_resolved_at, recovery_reason_code
                    FROM approval_continuations
                    WHERE status = 'CLAIMED'
                      AND claimed_at IS NOT NULL
                      AND claimed_at <= ?
                    ORDER BY claimed_at ASC, approval_id ASC
                    LIMIT ?
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setObject(1, OffsetDateTime.ofInstant(claimedBefore, ZoneOffset.UTC))
                    stmt.setInt(2, limit)

                    stmt.executeQuery().use { rs ->
                        val result = mutableListOf<ApprovalContinuation>()
                        while (rs.next()) {
                            result.add(mapToDomain(rs))
                        }
                        result
                    }
                }
            }
        }

    @Suppress("LongMethod")
    override suspend fun forceCancelClaimed(
        approvalId: String,
        expectedVersion: Long,
        cancelledBy: String,
        reasonCode: String,
    ): ApprovalContinuation =
        withSafeJdbc({ "Database operation failed for continuation: $approvalId" }) {
            validateIdField(approvalId, "approvalId")
            validateIdField(cancelledBy, "cancelledBy")
            SafeActorIdPolicy.validateActorId(cancelledBy, "cancelledBy")
            require(SAFE_REASON_CODE.matches(reasonCode)) {
                "reasonCode must match [a-z0-9][a-z0-9._:-]{0,63}"
            }

            dataSource.connection.use { conn ->
                val row =
                    readCurrent(conn, approvalId)
                        ?: throw ApprovalContinuationNotFoundException(approvalId)

                if (row.version != expectedVersion) {
                    throw ApprovalContinuationConflictException(approvalId)
                }

                val status = ApprovalContinuationStatus.valueOf(row.status)
                if (status != ApprovalContinuationStatus.CLAIMED) {
                    throw ApprovalContinuationNotClaimableException(approvalId)
                }

                val newVersion = incrementVersion(approvalId, row.version)
                val nowOdt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

                val sql =
                    """
                    UPDATE approval_continuations
                    SET status = 'CANCELLED_UNCERTAIN',
                        version = ?,
                        recovery_resolved_by = ?,
                        recovery_resolved_at = ?,
                        recovery_reason_code = ?
                    WHERE approval_id = ?
                      AND version = ?
                      AND status = 'CLAIMED'
                    """.trimIndent()
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, newVersion)
                    stmt.setString(2, cancelledBy)
                    stmt.setObject(3, nowOdt)
                    stmt.setString(4, reasonCode)
                    stmt.setString(5, approvalId)
                    stmt.setLong(6, expectedVersion)

                    val updated = stmt.executeUpdate()
                    if (updated == 0) {
                        throw ApprovalContinuationConflictException(approvalId)
                    }
                }

                val updatedContinuation =
                    normalizeRow(
                        approvalId = approvalId,
                        status = "CANCELLED_UNCERTAIN",
                        newVersion = newVersion,
                        claimedBy = row.claimedBy,
                        claimedAt = row.claimedAt,
                        completedAt = null,
                        base = row,
                        recoveryResolvedBy = cancelledBy,
                        recoveryResolvedAt = nowOdt,
                        recoveryReasonCode = reasonCode,
                    )
                updatedContinuation.toDomain()
            }
        }

    override suspend fun sweepExpired(): Int =
        withSafeJdbc({ "Database operation failed for sweeping expired continuations" }) {
            val now = clock.instant()

            dataSource.connection.use { conn ->
                // Find all PENDING continuations past their approval_expires_at
                val selectSql =
                    """
                    SELECT approval_id, version
                    FROM approval_continuations
                    WHERE status = 'PENDING'
                      AND approval_expires_at <= ?
                    LIMIT 100
                    """.trimIndent()
                val candidates = mutableListOf<Pair<String, Long>>()
                conn.prepareStatement(selectSql).use { stmt ->
                    stmt.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            candidates.add(rs.getString("approval_id") to rs.getLong("version"))
                        }
                    }
                }

                if (candidates.isEmpty()) return@use 0

                var count = 0
                val updateSql =
                    """
                    UPDATE approval_continuations
                    SET status = 'EXPIRED',
                        version = version + 1,
                        encrypted_arguments = NULL,
                        encryption_key_id = NULL,
                        encryption_algorithm = NULL,
                        encryption_nonce = NULL,
                        payload_digest = NULL
                    WHERE approval_id = ?
                      AND version = ?
                      AND status = 'PENDING'
                    """.trimIndent()
                conn.prepareStatement(updateSql).use { stmt ->
                    for ((approvalId, version) in candidates) {
                        stmt.setString(1, approvalId)
                        stmt.setLong(2, version)
                        count += stmt.executeUpdate()
                    }
                }

                count
            }
        }

    // ── Internal helpers ──────────────────────────────────────────

    private data class ContinuationRow(
        val approvalId: String,
        val status: String,
        val version: Long,
        val createdAt: OffsetDateTime,
        val approvalExpiresAt: OffsetDateTime,
        val workflowRunId: String?,
        val correlationId: String?,
        val toolCallId: String?,
        val toolName: String?,
        val argumentsDigest: String,
        val policyVersion: String?,
        val workflowDigest: String?,
        val claimedBy: String?,
        val claimedAt: OffsetDateTime?,
        val completedAt: OffsetDateTime?,
        val recoveryResolvedBy: String?,
        val recoveryResolvedAt: OffsetDateTime?,
        val recoveryReasonCode: String?,
        // Encryption columns (not always populated on reads)
        val encryptedArguments: ByteArray? = null,
        val encryptionKeyId: String? = null,
        val encryptionAlgorithm: String? = null,
        val encryptionNonce: ByteArray? = null,
        val payloadDigest: String? = null,
    )

    /**
     * Validates all input fields for a create operation.
     * Mirrors the FileApprovalContinuationStore validation.
     */
    private fun validateCreateInput(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ) {
        validateIdField(continuation.approvalId, "approvalId", maxIdLength)
        validateIdField(continuation.workflowRunId, "workflowRunId", maxIdLength)
        validateIdField(continuation.correlationId, "correlationId", maxIdLength)
        validateIdField(continuation.toolCallId, "toolCallId", maxIdLength)
        validateIdField(continuation.toolName, "toolName", maxIdLength)
        validateIdField(continuation.policyVersion, "policyVersion", maxIdLength)

        require(continuation.version == 0L) {
            "Initial continuation version must be 0, got ${continuation.version}"
        }
        require(continuation.status == ApprovalContinuationStatus.PENDING) {
            "Initial continuation status must be PENDING, got ${continuation.status}"
        }
        require(continuation.claimedBy == null) { "Initial continuation must not have claimedBy set" }
        require(continuation.claimedAt == null) { "Initial continuation must not have claimedAt set" }
        require(continuation.completedAt == null) { "Initial continuation must not have completedAt set" }
        require(continuation.recoveryResolvedBy == null) { "Initial continuation must not have recoveryResolvedBy set" }
        require(continuation.recoveryResolvedAt == null) { "Initial continuation must not have recoveryResolvedAt set" }
        require(continuation.recoveryReasonCode == null) { "Initial continuation must not have recoveryReasonCode set" }

        // Arguments digest verification — the stored digest must match the
        // released payload (same contract as the in-memory and file stores).
        val actualDigest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(arguments.reveal().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        require(actualDigest == continuation.argumentsDigest.value.removePrefix("sha256:")) {
            "argumentsDigest does not match arguments"
        }
    }

    /**
     * Validates temporal invariants for create: expiresAt in future,
     * createdAt not in future, expiresAt after createdAt, TTL within bounds.
     */
    private fun validateTemporalInvariants(
        continuation: ApprovalContinuation,
        now: Instant,
    ) {
        require(!continuation.createdAt.isAfter(now)) { "createdAt must not be in the future" }
        require(continuation.approvalExpiresAt.isAfter(now)) { "approvalExpiresAt must be in the future" }
        require(continuation.approvalExpiresAt.isAfter(continuation.createdAt)) {
            "approvalExpiresAt must be after createdAt"
        }

        val ttl = Duration.between(continuation.createdAt, continuation.approvalExpiresAt)
        require(ttl <= maxContinuationTtl) {
            "approvalExpiresAt exceeds maximum continuation TTL of $maxContinuationTtl"
        }
    }

    /**
     * Checks whether a PENDING continuation has passed its approvalExpiresAt.
     * Only applies to PENDING — CLAIMED must never lazily expire.
     *
     * Returns the (possibly updated) domain continuation and whether expiry occurred.
     */
    private fun maybeExpireLazy(
        row: ContinuationRow,
        now: Instant,
    ): Pair<ContinuationRow, Boolean> {
        val status =
            try {
                ApprovalContinuationStatus.valueOf(row.status)
            } catch (_: Exception) {
                return row to false
            }
        if (status != ApprovalContinuationStatus.PENDING) {
            return row to false
        }
        val expiresAt = row.approvalExpiresAt.toInstant()
        if (now < expiresAt) return row to false

        val newVersion = incrementVersion(row.approvalId, row.version)
        val expiredRow =
            normalizeRow(
                approvalId = row.approvalId,
                status = "EXPIRED",
                newVersion = newVersion,
                claimedBy = null,
                claimedAt = null,
                completedAt = null,
                base = row,
            )
        return expiredRow to true
    }

    /**
     * Applies a lazy expiry transition to the database.
     *
     * @return true if the CAS update succeeded (row was expired), false if
     *   another writer changed the row first.
     */
    private fun applyLazyExpiry(
        row: ContinuationRow,
        expectedVersion: Long,
    ): Boolean {
        dataSource.connection.use { conn ->
            val sql =
                """
                UPDATE approval_continuations
                SET status = 'EXPIRED',
                    version = ?,
                    encrypted_arguments = NULL,
                    encryption_key_id = NULL,
                    encryption_algorithm = NULL,
                    encryption_nonce = NULL,
                    payload_digest = NULL
                WHERE approval_id = ?
                  AND version = ?
                  AND status = 'PENDING'
                """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, row.version)
                stmt.setString(2, row.approvalId)
                stmt.setLong(3, expectedVersion)
                return stmt.executeUpdate() == 1
            }
        }
    }

    /**
     * Decrypts the arguments from a stored row.
     */
    private fun decryptArguments(row: ContinuationRow): String {
        val encrypted =
            row.encryptedArguments
                ?: throw ApprovalContinuationConflictException(row.approvalId)
        val keyId =
            row.encryptionKeyId
                ?: throw ApprovalContinuationConflictException(row.approvalId)
        val algorithm =
            row.encryptionAlgorithm
                ?: throw ApprovalContinuationConflictException(row.approvalId)
        val nonce =
            row.encryptionNonce
                ?: throw ApprovalContinuationConflictException(row.approvalId)
        val payloadDigest =
            row.payloadDigest
                ?: throw ApprovalContinuationConflictException(row.approvalId)

        val envelope =
            JdbcEncryptedContinuationArguments(
                ciphertext = encrypted,
                keyId = keyId,
                algorithm = algorithm,
                nonce = nonce,
                payloadDigest = payloadDigest,
            )
        return argumentsCodec.decode(envelope).toString(Charsets.UTF_8)
    }

    /**
     * Reads the current row from the database, including encrypted arguments
     * (for claim operations) or without (for metadata-only reads).
     */
    private fun readCurrent(
        conn: Connection,
        approvalId: String,
        includeEncrypted: Boolean = true,
    ): ContinuationRow? {
        val columns =
            if (includeEncrypted) {
                """
            approval_id, status, version, created_at, approval_expires_at,
            workflow_run_id, correlation_id, tool_call_id, tool_name,
            arguments_digest, policy_version, workflow_digest,
            claimed_by, claimed_at, completed_at,
            recovery_resolved_by, recovery_resolved_at, recovery_reason_code,
            encrypted_arguments, encryption_key_id, encryption_algorithm,
            encryption_nonce, payload_digest
            """
            } else {
                """
            approval_id, status, version, created_at, approval_expires_at,
            workflow_run_id, correlation_id, tool_call_id, tool_name,
            arguments_digest, policy_version, workflow_digest,
            claimed_by, claimed_at, completed_at,
            recovery_resolved_by, recovery_resolved_at, recovery_reason_code
            """
            }

        val sql = "SELECT $columns FROM approval_continuations WHERE approval_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, approvalId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                return mapToRow(rs, includeEncrypted)
            }
        }
    }

    /**
     * Convenience read on a fresh connection (for the non-connection-scoped path).
     */
    private fun readCurrent(approvalId: String): ContinuationRow? {
        dataSource.connection.use { conn ->
            return readCurrent(conn, approvalId)
        }
    }

    private fun mapToRow(
        rs: ResultSet,
        includeEncrypted: Boolean,
    ): ContinuationRow =
        ContinuationRow(
            approvalId = rs.getString("approval_id"),
            status = rs.getString("status"),
            version = rs.getLong("version"),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
            approvalExpiresAt = rs.getObject("approval_expires_at", OffsetDateTime::class.java),
            workflowRunId = rs.getString("workflow_run_id"),
            correlationId = rs.getString("correlation_id"),
            toolCallId = rs.getString("tool_call_id"),
            toolName = rs.getString("tool_name"),
            argumentsDigest = rs.getString("arguments_digest"),
            policyVersion = rs.getString("policy_version"),
            workflowDigest = rs.getString("workflow_digest"),
            claimedBy = rs.getString("claimed_by"),
            claimedAt = rs.getObject("claimed_at", OffsetDateTime::class.java),
            completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
            recoveryResolvedBy = rs.getString("recovery_resolved_by"),
            recoveryResolvedAt = rs.getObject("recovery_resolved_at", OffsetDateTime::class.java),
            recoveryReasonCode = rs.getString("recovery_reason_code"),
            encryptedArguments = if (includeEncrypted) rs.getBytes("encrypted_arguments") else null,
            encryptionKeyId = if (includeEncrypted) rs.getString("encryption_key_id") else null,
            encryptionAlgorithm = if (includeEncrypted) rs.getString("encryption_algorithm") else null,
            encryptionNonce = if (includeEncrypted) rs.getBytes("encryption_nonce") else null,
            payloadDigest = if (includeEncrypted) rs.getString("payload_digest") else null,
        )

    /**
     * Maps a row to an [ApprovalContinuation] domain object.
     * Does NOT include arguments — use [claimForExecution] for that.
     */
    private fun mapToDomain(rs: ResultSet): ApprovalContinuation {
        val claimedAt = rs.getObject("claimed_at", OffsetDateTime::class.java)
        val completedAt = rs.getObject("completed_at", OffsetDateTime::class.java)
        val recoveryResolvedAt = rs.getObject("recovery_resolved_at", OffsetDateTime::class.java)

        return ApprovalContinuation(
            approvalId = rs.getString("approval_id"),
            workflowRunId = rs.getString("workflow_run_id") ?: "",
            correlationId = rs.getString("correlation_id") ?: "",
            toolCallId = rs.getString("tool_call_id") ?: "",
            toolName = rs.getString("tool_name") ?: "",
            argumentsDigest = Sha256Digest.of(rs.getString("arguments_digest")),
            policyVersion = rs.getString("policy_version") ?: "",
            workflowDigest = Sha256Digest.of(rs.getString("workflow_digest") ?: "sha256:${"0".repeat(64)}"),
            status = ApprovalContinuationStatus.valueOf(rs.getString("status")),
            createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
            approvalExpiresAt = rs.getObject("approval_expires_at", OffsetDateTime::class.java).toInstant(),
            claimedBy = rs.getString("claimed_by"),
            claimedAt = claimedAt?.toInstant(),
            completedAt = completedAt?.toInstant(),
            recoveryResolvedBy = rs.getString("recovery_resolved_by"),
            recoveryResolvedAt = recoveryResolvedAt?.toInstant(),
            recoveryReasonCode = rs.getString("recovery_reason_code"),
            version = rs.getLong("version"),
        )
    }

    /**
     * Converts a [ContinuationRow] to [ApprovalContinuation] domain object.
     * Arguments are NOT included — they were encrypted and are only exposed
     * via [claimForExecution].
     */
    private fun ContinuationRow.toDomain(): ApprovalContinuation =
        ApprovalContinuation(
            approvalId = approvalId,
            workflowRunId = workflowRunId ?: "",
            correlationId = correlationId ?: "",
            toolCallId = toolCallId ?: "",
            toolName = toolName ?: "",
            argumentsDigest = Sha256Digest.of(argumentsDigest),
            policyVersion = policyVersion ?: "",
            workflowDigest = Sha256Digest.of(workflowDigest ?: "sha256:${"0".repeat(64)}"),
            status = ApprovalContinuationStatus.valueOf(status),
            createdAt = createdAt.toInstant(),
            approvalExpiresAt = approvalExpiresAt.toInstant(),
            claimedBy = claimedBy,
            claimedAt = claimedAt?.toInstant(),
            completedAt = completedAt?.toInstant(),
            recoveryResolvedBy = recoveryResolvedBy,
            recoveryResolvedAt = recoveryResolvedAt?.toInstant(),
            recoveryReasonCode = recoveryReasonCode,
            version = version,
        )

    /**
     * Creates a normalized row with updated status/version fields,
     * preserving all other metadata from the base row.
     */
    private fun normalizeRow(
        approvalId: String,
        status: String,
        newVersion: Long,
        claimedBy: String?,
        claimedAt: OffsetDateTime?,
        completedAt: OffsetDateTime?,
        base: ContinuationRow,
        recoveryResolvedBy: String? = null,
        recoveryResolvedAt: OffsetDateTime? = null,
        recoveryReasonCode: String? = null,
    ): ContinuationRow =
        ContinuationRow(
            approvalId = approvalId,
            status = status,
            version = newVersion,
            createdAt = base.createdAt,
            approvalExpiresAt = base.approvalExpiresAt,
            workflowRunId = base.workflowRunId,
            correlationId = base.correlationId,
            toolCallId = base.toolCallId,
            toolName = base.toolName,
            argumentsDigest = base.argumentsDigest,
            policyVersion = base.policyVersion,
            workflowDigest = base.workflowDigest,
            claimedBy = claimedBy,
            claimedAt = claimedAt,
            completedAt = completedAt,
            recoveryResolvedBy = recoveryResolvedBy,
            recoveryResolvedAt = recoveryResolvedAt,
            recoveryReasonCode = recoveryReasonCode,
        )

    private fun incrementVersion(
        approvalId: String,
        version: Long,
    ): Long =
        try {
            Math.addExact(version, 1L)
        } catch (_: ArithmeticException) {
            throw ApprovalContinuationConflictException(approvalId)
        }

    private fun validateIdField(
        value: String,
        fieldName: String,
        maxLength: Int = maxIdLength,
    ): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= maxLength) { "$fieldName exceeds maximum length of $maxLength" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
        return trimmed
    }

    companion object {
        private val SAFE_REASON_CODE = Regex("[a-z0-9][a-z0-9._:-]{0,63}")
    }
}

/**
 * Minimal codec for encrypting and decrypting [SensitiveToolArguments]
 * before storing them in the `encrypted_arguments` column.
 *
 * Implementations are responsible for key management, encryption algorithm
 * selection, and nonce generation.
 */
interface JdbcContinuationArgumentsCodec {
    /**
     * Encrypt [plaintext] and produce a [JdbcEncryptedContinuationArguments]
     * suitable for storage in the `approval_continuations` table.
     */
    fun encode(plaintext: ByteArray): JdbcEncryptedContinuationArguments

    /**
     * Decrypt the [envelope] and return the original plaintext.
     *
     * @throws IllegalStateException if decryption fails (wrong key,
     *   tampered ciphertext, algorithm mismatch, etc.).
     */
    fun decode(envelope: JdbcEncryptedContinuationArguments): ByteArray
}

/**
 * Encrypted continuation arguments with all metadata fields required by the
 * `approval_continuations` table schema.
 */
data class JdbcEncryptedContinuationArguments(
    val ciphertext: ByteArray,
    val keyId: String,
    val algorithm: String,
    val nonce: ByteArray,
    val payloadDigest: String,
)
