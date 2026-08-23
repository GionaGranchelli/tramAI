package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * JDBC-backed [SovereignOpsAuditOutboxStore] using PostgreSQL's `audit_outbox` table
 * (V1 + V4 schema).
 *
 * ## Data model
 * Full outbox records are serialised to JSON, encrypted via an injectable
 * [JdbcOpsAuditOutboxPayloadCodec], and stored in the `encrypted_payload` BLOB.
 * Non-sensitive metadata (outbox_id, event_key, status, timestamps) is
 * stored in dedicated indexed columns for fast filtering.
 *
 * ## Concurrency
 * - [claimPending] uses `SELECT ... FOR UPDATE SKIP LOCKED` so multiple workers
 *   can dispatch concurrently without double-claiming.
 * - [markReadyForDispatch], [markEmitted], and [markFailed] use row-level locking
 *   within explicit transactions.
 * - [append] uses the unique constraint on `outbox_id` and `event_key` for
 *   idempotency rejection without explicit locking.
 *
 * ## Security model
 * - Full record payloads are encrypted at rest via the injected codec.
 * - Queryable columns contain hashes and identifiers — no prompts,
 *   model outputs, or PII.
 * - All encryption metadata columns are populated when a payload is stored.
 * - Decryption failure fails closed.
 * - On every read, queryable columns are validated against the decrypted payload.
 *
 * @param dataSource The [DataSource] providing PostgreSQL connections.
 * @param payloadCodec The codec for encrypting/decrypting outbox record payloads.
 * @param claimLeaseDuration Duration after which an EMITTING claim expires
 *   and becomes re-claimable (default 5 minutes).
 * @param maxClaimLimit Maximum records claimed in one [claimPending] call
 *   (default 500).
 */
class JdbcSovereignOpsAuditOutboxStore(
    private val dataSource: DataSource,
    private val payloadCodec: JdbcOpsAuditOutboxPayloadCodec,
    private val claimLeaseDuration: Duration = SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY,
    private val maxClaimLimit: Int = 500,
) : SovereignOpsAuditOutboxStore {

    init {
        require(!claimLeaseDuration.isNegative) { "claimLeaseDuration must not be negative" }
        require(maxClaimLimit > 0) { "maxClaimLimit must be positive" }
    }

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)

    // ══════════════════════════════════════════════════════════════════
    // SovereignOpsAuditOutboxStore SPI
    // ══════════════════════════════════════════════════════════════════

    override fun isDurable(): Boolean = true

    override suspend fun append(
        record: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsAuditOutboxRecord {
        require(record.outboxId.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-id" }
        require(record.eventKey.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-event-key" }
        require(record.status == SovereignOpsAuditOutboxStatus.PREPARED) {
            "tramai-sovereign-ops-outbox-invalid-status"
        }

        return dataSource.connection.use { conn ->
            inOutboxTransaction(conn) { c ->
                val payloadJson = mapper.writeValueAsBytes(record.toPersistedOutbox())
                val encrypted = payloadCodec.encode(payloadJson)

                try {
                    insertAppend(c, record, encrypted)
                } catch (e: SQLException) {
                    throw mapAppendException(e)
                }
                record
            }
        }
    }

    override suspend fun markReadyForDispatch(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord = dataSource.connection.use { conn ->
        inOutboxTransaction(conn) { c ->
            val row = selectForUpdate(c, outboxId)
                ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")

            val domain = row.toDomain()
            validateQueryableColumns(domain, row)

            require(expectedStatus == SovereignOpsAuditOutboxStatus.PREPARED) {
                "tramai-sovereign-ops-outbox-status-mismatch"
            }
            require(domain.status == expectedStatus) {
                "tramai-sovereign-ops-outbox-status-mismatch"
            }

            val updated = domain.copy(status = SovereignOpsAuditOutboxStatus.PENDING)
            val payloadJson = mapper.writeValueAsBytes(updated.toPersistedOutbox())
            val encrypted = payloadCodec.encode(payloadJson)

            updateStatusAndPayload(c, outboxId, row.version, updated.status.name, encrypted)
            updated
        }
    }

    override suspend fun claimPending(
        claimedBy: String,
        limit: Int,
        now: Instant,
    ): List<SovereignOpsAuditOutboxRecord> {
        if (limit <= 0) return emptyList()
        val actualLimit = minOf(limit, maxClaimLimit)

        return dataSource.connection.use { conn ->
            inOutboxTransaction(conn) { c ->
                val claimExpiresAt = now.plus(claimLeaseDuration)
                val selected = selectClaimableForUpdateSkipLocked(c, actualLimit, now)

                val claimed = selected.map { row ->
                    val record = row.toDomain()
                    validateQueryableColumns(record, row)
                    require(record.isDispatchable(now)) {
                        "tramai-sovereign-ops-outbox-not-dispatchable"
                    }
                    val updated = record.copy(
                        status = SovereignOpsAuditOutboxStatus.EMITTING,
                        attemptCount = record.attemptCount + 1,
                        claimedBy = claimedBy,
                        claimedAt = now,
                        claimExpiresAt = claimExpiresAt,
                        lastErrorCode = null,
                    )
                    val payloadJson = mapper.writeValueAsBytes(updated.toPersistedOutbox())
                    val encrypted = payloadCodec.encode(payloadJson)

                    updateClaimed(c, updated.outboxId, encrypted, now, claimExpiresAt)
                    updated
                }
                claimed
            }
        }
    }

    override suspend fun markEmitted(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord = dataSource.connection.use { conn ->
        inOutboxTransaction(conn) { conn ->
            val row = selectForUpdate(conn, outboxId)
                ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")

            val domain = row.toDomain()
            validateQueryableColumns(domain, row)

            require(expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING) {
                "tramai-sovereign-ops-outbox-status-mismatch"
            }
            require(domain.status == expectedStatus) {
                "tramai-sovereign-ops-outbox-status-mismatch"
            }

            val updated = domain.copy(
                status = SovereignOpsAuditOutboxStatus.EMITTED,
                emittedAt = emittedAt,
            )
            val payloadJson = mapper.writeValueAsBytes(updated.toPersistedOutbox())
            val encrypted = payloadCodec.encode(payloadJson)

            val sql = """
                UPDATE audit_outbox
                SET status = 'EMITTED',
                    dispatched_at = ?,
                    encrypted_payload = ?,
                    encryption_key_id = ?,
                    encryption_algorithm = ?,
                    encryption_nonce = ?,
                    payload_digest = ?,
                    version = version + 1
                WHERE outbox_id = ? AND version = ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(emittedAt))
                stmt.setBytes(2, encrypted.ciphertext)
                stmt.setString(3, encrypted.keyId)
                stmt.setString(4, encrypted.algorithm)
                stmt.setBytes(5, encrypted.nonce)
                stmt.setString(6, encrypted.payloadDigest)
                stmt.setString(7, outboxId)
                stmt.setLong(8, row.version)
                val updatedCount = stmt.executeUpdate()
                require(updatedCount == 1) { "tramai-sovereign-ops-outbox-concurrent-update" }
            }
            updated
        }
    }

    override suspend fun markFailed(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        errorCode: String,
        retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord = dataSource.connection.use { conn ->
        inOutboxTransaction(conn) { conn ->
            val row = selectForUpdate(conn, outboxId)
                ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")

            val domain = row.toDomain()
            validateQueryableColumns(domain, row)

            if (retryable) {
                require(expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING) {
                    "tramai-sovereign-ops-outbox-status-mismatch"
                }
            } else {
                require(
                    expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING ||
                        expectedStatus == SovereignOpsAuditOutboxStatus.PREPARED
                ) {
                    "tramai-sovereign-ops-outbox-status-mismatch"
                }
            }
            require(domain.status == expectedStatus) {
                "tramai-sovereign-ops-outbox-status-mismatch"
            }

            val targetStatus = if (retryable) {
                SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE
            } else {
                SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
            }

            val updated = domain.copy(
                status = targetStatus,
                lastErrorCode = errorCode,
            )
            val payloadJson = mapper.writeValueAsBytes(updated.toPersistedOutbox())
            val encrypted = payloadCodec.encode(payloadJson)

            val sql = """
                UPDATE audit_outbox
                SET status = ?,
                    last_failure_type = ?,
                    encrypted_payload = ?,
                    encryption_key_id = ?,
                    encryption_algorithm = ?,
                    encryption_nonce = ?,
                    payload_digest = ?,
                    version = version + 1
                WHERE outbox_id = ? AND version = ?
            """.trimIndent()

            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, targetStatus.name)
                stmt.setString(2, errorCode)
                stmt.setBytes(3, encrypted.ciphertext)
                stmt.setString(4, encrypted.keyId)
                stmt.setString(5, encrypted.algorithm)
                stmt.setBytes(6, encrypted.nonce)
                stmt.setString(7, encrypted.payloadDigest)
                stmt.setString(8, outboxId)
                stmt.setLong(9, row.version)
                val updatedCount = stmt.executeUpdate()
                require(updatedCount == 1) { "tramai-sovereign-ops-outbox-concurrent-update" }
            }
            updated
        }
    }

    override suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord? =
        dataSource.connection.use { conn ->
            selectById(conn, outboxId)?.let { row ->
                val domain = row.toDomain()
                validateQueryableColumns(domain, row)
                domain
            }
        }

    override suspend fun findByEventKey(eventKey: String): SovereignOpsAuditOutboxRecord? =
        dataSource.connection.use { conn ->
            selectByEventKey(conn, eventKey)?.let { row ->
                val domain = row.toDomain()
                validateQueryableColumns(domain, row)
                domain
            }
        }

    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> =
        if (limit <= 0) emptyList() else listByExactStatus(SovereignOpsAuditOutboxStatus.PENDING, limit)

    override suspend fun listByStatus(
        status: SovereignOpsAuditOutboxStatus,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> =
        if (limit <= 0) emptyList() else listByExactStatus(status, limit)

    override suspend fun listExpiredEmitting(
        now: Instant,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> {
        if (limit <= 0) return emptyList()
        return dataSource.connection.use { conn ->
            selectExpiredEmitting(conn, now, limit).map { row ->
                val domain = row.toDomain()
                validateQueryableColumns(domain, row)
                domain
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Internal helpers
    // ══════════════════════════════════════════════════════════════════

    private fun listByExactStatus(
        status: SovereignOpsAuditOutboxStatus,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> =
        dataSource.connection.use { conn ->
            selectByStatus(conn, status.name, limit).map { row ->
                val domain = row.toDomain()
                validateQueryableColumns(domain, row)
                domain
            }
        }

    private fun OutboxRow.toDomain(): SovereignOpsAuditOutboxRecord {
        val encrypted = JdbcEncryptedAuditOutboxPayload(
            ciphertext = encryptedPayload,
            keyId = encryptionKeyId,
            algorithm = encryptionAlgorithm,
            nonce = encryptionNonce,
            payloadDigest = payloadDigest,
        )
        val plaintext = try {
            payloadCodec.decode(encrypted)
        } catch (e: Exception) {
            throw IllegalStateException("audit-outbox-payload-decryption-failed", e)
        }
        return try {
            mapper.readValue<PersistedSovereignOpsAuditOutboxRecordV1>(plaintext).toDomain()
        } catch (e: Exception) {
            throw IllegalStateException("audit-outbox-payload-deserialisation-failed", e)
        }
    }

    private fun validateQueryableColumns(
        domain: SovereignOpsAuditOutboxRecord,
        row: OutboxRow,
    ) {
        require(domain.outboxId == row.outboxId) { "audit-outbox-column-outbox-id-mismatch" }
        require(domain.eventKey == row.eventKey) { "audit-outbox-column-event-key-mismatch" }
        require(domain.status.name == row.status) { "audit-outbox-column-status-mismatch" }
        require(domain.attemptCount == row.attemptCount) { "audit-outbox-column-attempt-count-mismatch" }
        // correlationKeyHash is the DB-column name for the aggregate digest
        if (row.correlationKeyHash != null) {
            require(domain.aggregateIdDigest == row.correlationKeyHash) {
                "audit-outbox-column-correlation-key-hash-mismatch"
            }
        }
        require(domain.lastErrorCode == row.lastFailureType) {
            "audit-outbox-column-last-failure-type-mismatch"
        }
        // Timestamp columns may have millisecond precision differences;
        // allow up to 1-second tolerance for round-trip comparisons.
        validateTimestampMatch(
            dbValue = row.createdAt.toInstant(),
            domainValue = domain.createdAt,
            columnName = "created_at",
        )
        validateNullableTimestampMatch(
            dbValue = row.claimedAt?.toInstant(),
            domainValue = domain.claimedAt,
            columnName = "claimed_at",
        )
        validateNullableTimestampMatch(
            dbValue = row.dispatchedAt?.toInstant(),
            domainValue = domain.emittedAt,
            columnName = "dispatched_at",
        )
        validateNullableTimestampMatch(
            dbValue = row.nextAttemptAt?.toInstant(),
            domainValue = domain.claimExpiresAt,
            columnName = "next_attempt_at",
        )
    }

    private fun validateTimestampMatch(
        dbValue: Instant,
        domainValue: Instant,
        columnName: String,
    ) {
        val diff = Duration.between(dbValue, domainValue).abs()
        require(diff.seconds <= 1) {
            "audit-outbox-column-$columnName-mismatch"
        }
    }

    private fun validateNullableTimestampMatch(
        dbValue: Instant?,
        domainValue: Instant?,
        columnName: String,
    ) {
        require((dbValue == null) == (domainValue == null)) {
            "audit-outbox-column-$columnName-mismatch"
        }
        if (dbValue == null || domainValue == null) return
        val diff = Duration.between(dbValue, domainValue).abs()
        require(diff.seconds <= 1) {
            "audit-outbox-column-$columnName-mismatch"
        }
    }

    private fun SovereignOpsAuditOutboxRecord.isDispatchable(now: Instant): Boolean =
        when (status) {
            SovereignOpsAuditOutboxStatus.PENDING,
            SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE,
            -> true
            SovereignOpsAuditOutboxStatus.EMITTING -> {
                val expiresAt = claimExpiresAt
                expiresAt != null && expiresAt.isBefore(now)
            }
            SovereignOpsAuditOutboxStatus.PREPARED,
            SovereignOpsAuditOutboxStatus.EMITTED,
            SovereignOpsAuditOutboxStatus.FAILED_PERMANENT,
            -> false
        }

    // ══════════════════════════════════════════════════════════════════
    // SQL — INSERT
    // ══════════════════════════════════════════════════════════════════

    private fun insertAppend(
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

    // ══════════════════════════════════════════════════════════════════
    // SQL — SELECT helpers
    // ══════════════════════════════════════════════════════════════════

    private val SELECT_COLUMNS = """
        SELECT outbox_id, event_key, status, correlation_key_hash,
               created_at, claimed_at, dispatched_at,
               attempt_count, last_failure_type, next_attempt_at,
               encrypted_payload, encryption_key_id, encryption_algorithm,
               encryption_nonce, payload_digest, version
    """.trimIndent()

    private fun selectForUpdate(conn: Connection, outboxId: String): OutboxRow? {
        val sql = "$SELECT_COLUMNS FROM audit_outbox WHERE outbox_id = ? FOR UPDATE"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, outboxId)
            stmt.executeQuery().let { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    private fun selectById(conn: Connection, outboxId: String): OutboxRow? {
        val sql = "$SELECT_COLUMNS FROM audit_outbox WHERE outbox_id = ?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, outboxId)
            stmt.executeQuery().let { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    private fun selectByEventKey(conn: Connection, eventKey: String): OutboxRow? {
        val sql = "$SELECT_COLUMNS FROM audit_outbox WHERE event_key = ?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, eventKey)
            stmt.executeQuery().let { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    private fun selectByStatus(conn: Connection, status: String, limit: Int): List<OutboxRow> {
        val sql = "$SELECT_COLUMNS FROM audit_outbox WHERE status = ? ORDER BY created_at ASC LIMIT ?"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, status)
            stmt.setInt(2, limit)
            stmt.executeQuery().let { rs ->
                val results = mutableListOf<OutboxRow>()
                while (rs.next()) results.add(mapRow(rs))
                results
            }
        }
    }

    private fun selectClaimableForUpdateSkipLocked(
        conn: Connection,
        limit: Int,
        now: Instant,
    ): List<OutboxRow> {
        val sql = """
            $SELECT_COLUMNS FROM audit_outbox
            WHERE
                status IN ('PENDING', 'FAILED_RETRYABLE')
                OR (
                    status = 'EMITTING'
                    AND next_attempt_at IS NOT NULL
                    AND next_attempt_at < ?
                )
            ORDER BY created_at ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(now))
            stmt.setInt(2, limit)
            stmt.executeQuery().let { rs ->
                val results = mutableListOf<OutboxRow>()
                while (rs.next()) results.add(mapRow(rs))
                results
            }
        }
    }

    private fun selectExpiredEmitting(
        conn: Connection,
        now: Instant,
        limit: Int,
    ): List<OutboxRow> {
        val sql = """
            $SELECT_COLUMNS FROM audit_outbox
            WHERE status = 'EMITTING'
              AND next_attempt_at IS NOT NULL
              AND next_attempt_at < ?
            ORDER BY created_at ASC
            LIMIT ?
        """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(now))
            stmt.setInt(2, limit)
            stmt.executeQuery().let { rs ->
                val results = mutableListOf<OutboxRow>()
                while (rs.next()) results.add(mapRow(rs))
                results
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // SQL — UPDATE helpers
    // ══════════════════════════════════════════════════════════════════

    private fun updateStatusAndPayload(
        conn: Connection,
        outboxId: String,
        expectedVersion: Long,
        newStatus: String,
        encrypted: JdbcEncryptedAuditOutboxPayload,
    ) {
        val sql = """
            UPDATE audit_outbox
            SET status = ?,
                encrypted_payload = ?,
                encryption_key_id = ?,
                encryption_algorithm = ?,
                encryption_nonce = ?,
                payload_digest = ?,
                version = version + 1
            WHERE outbox_id = ? AND version = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, newStatus)
            stmt.setBytes(2, encrypted.ciphertext)
            stmt.setString(3, encrypted.keyId)
            stmt.setString(4, encrypted.algorithm)
            stmt.setBytes(5, encrypted.nonce)
            stmt.setString(6, encrypted.payloadDigest)
            stmt.setString(7, outboxId)
            stmt.setLong(8, expectedVersion)
            val updated = stmt.executeUpdate()
            require(updated == 1) { "tramai-sovereign-ops-outbox-concurrent-update" }
        }
    }

    private fun updateClaimed(
        conn: Connection,
        outboxId: String,
        encrypted: JdbcEncryptedAuditOutboxPayload,
        claimedAt: Instant,
        claimExpiresAt: Instant,
    ) {
        val sql = """
            UPDATE audit_outbox
            SET status = 'EMITTING',
                claimed_at = ?,
                next_attempt_at = ?,
                attempt_count = attempt_count + 1,
                last_failure_type = NULL,
                encrypted_payload = ?,
                encryption_key_id = ?,
                encryption_algorithm = ?,
                encryption_nonce = ?,
                payload_digest = ?,
                version = version + 1
            WHERE outbox_id = ?
        """.trimIndent()

        conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(claimedAt))
            stmt.setTimestamp(2, Timestamp.from(claimExpiresAt))
            stmt.setBytes(3, encrypted.ciphertext)
            stmt.setString(4, encrypted.keyId)
            stmt.setString(5, encrypted.algorithm)
            stmt.setBytes(6, encrypted.nonce)
            stmt.setString(7, encrypted.payloadDigest)
            stmt.setString(8, outboxId)
            val updated = stmt.executeUpdate()
            require(updated == 1) { "tramai-sovereign-ops-outbox-claim-update-failed" }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Row mapping
    // ══════════════════════════════════════════════════════════════════

    private data class OutboxRow(
        val outboxId: String,
        val eventKey: String,
        val status: String,
        val correlationKeyHash: String?,
        val createdAt: OffsetDateTime,
        val claimedAt: OffsetDateTime?,
        val dispatchedAt: OffsetDateTime?,
        val attemptCount: Int,
        val lastFailureType: String?,
        val nextAttemptAt: OffsetDateTime?,
        val encryptedPayload: ByteArray,
        val encryptionKeyId: String,
        val encryptionAlgorithm: String,
        val encryptionNonce: ByteArray,
        val payloadDigest: String,
        val version: Long,
    )

    private fun mapRow(rs: ResultSet): OutboxRow {
        val createdAt = rs.getTimestamp("created_at")?.toInstant()
            ?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) }
            ?: throw IllegalStateException("audit-outbox-missing-created-at")

        return OutboxRow(
            outboxId = rs.getString("outbox_id")
                ?: throw IllegalStateException("audit-outbox-missing-outbox-id"),
            eventKey = rs.getString("event_key")
                ?: throw IllegalStateException("audit-outbox-missing-event-key"),
            status = rs.getString("status")
                ?: throw IllegalStateException("audit-outbox-missing-status"),
            correlationKeyHash = rs.getString("correlation_key_hash"),
            createdAt = createdAt,
            claimedAt = rs.getTimestamp("claimed_at")?.toInstant()
                ?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            dispatchedAt = rs.getTimestamp("dispatched_at")?.toInstant()
                ?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            attemptCount = rs.getInt("attempt_count"),
            lastFailureType = rs.getString("last_failure_type"),
            nextAttemptAt = rs.getTimestamp("next_attempt_at")?.toInstant()
                ?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            encryptedPayload = rs.getBytes("encrypted_payload") ?: ByteArray(0),
            encryptionKeyId = rs.getString("encryption_key_id")
                ?: throw IllegalStateException("audit-outbox-missing-key-id"),
            encryptionAlgorithm = rs.getString("encryption_algorithm")
                ?: throw IllegalStateException("audit-outbox-missing-algorithm"),
            encryptionNonce = rs.getBytes("encryption_nonce") ?: ByteArray(0),
            payloadDigest = rs.getString("payload_digest")
                ?: throw IllegalStateException("audit-outbox-missing-payload-digest"),
            version = rs.getLong("version"),
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Exception mapping
    // ══════════════════════════════════════════════════════════════════

    private fun mapAppendException(
        e: SQLException,
    ): RuntimeException {
        val message = e.message ?: ""
        return when {
            message.contains("uq_audit_outbox_event_key") || message.contains("audit_outbox_event_key_key") ->
                IllegalArgumentException("tramai-sovereign-ops-outbox-duplicate-event-key")
            message.contains("audit_outbox_pkey") ->
                IllegalArgumentException("tramai-sovereign-ops-outbox-duplicate-id")
            else -> IllegalStateException("tramai-sovereign-ops-outbox-database-failure", e)
        }
    }

    /**
     * Deliberately non-suspend helper (the #267/#271 cleanup model): runs
     * [block] in one explicit transaction. Failure precedence is primary
     * operation / cancellation, then rollback failure, then
     * autoCommit-restore failure — later cleanup failures are attached as
     * suppressed to the primary, never replacing it.
     */
    private fun <T> inOutboxTransaction(conn: Connection, block: (Connection) -> T): T {
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        var primaryFailure: Exception? = null
        try {
            val result = block(conn)
            conn.commit()
            return result
        } catch (e: Exception) {
            primaryFailure = e
            try {
                conn.rollback()
            } catch (rollbackFailure: Exception) {
                e.addSuppressed(rollbackFailure)
            }
            throw e
        } finally {
            try {
                conn.autoCommit = previousAutoCommit
            } catch (restoreFailure: Exception) {
                val primary = primaryFailure
                if (primary != null) {
                    primary.addSuppressed(restoreFailure)
                } else {
                    throw restoreFailure
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// Persisted DTO (file-level, no instance member dependency)
// ══════════════════════════════════════════════════════════════════

/**
 * JSON-serialisable DTO for encrypted outbox record payloads.
 */
internal data class PersistedSovereignOpsAuditOutboxRecordV1(
    val schemaVersion: Int = 1,
    val outboxId: String,
    val aggregateType: String,
    val aggregateIdDigest: String,
    val operation: String,
    val eventKey: String,
    val actor: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val approvalStatus: String,
    val approvalVersion: Long?,
    val reasonDigest: String,
    val reasonLength: Int,
    val status: String,
    val attemptCount: Int,
    val lastErrorCode: String?,
    val claimedBy: String?,
    val claimedAt: String?,
    val claimExpiresAt: String?,
    val createdAt: String,
    val emittedAt: String?,
)

internal fun SovereignOpsAuditOutboxRecord.toPersistedOutbox(): PersistedSovereignOpsAuditOutboxRecordV1 =
    PersistedSovereignOpsAuditOutboxRecordV1(
        schemaVersion = 1,
        outboxId = outboxId,
        aggregateType = aggregateType,
        aggregateIdDigest = aggregateIdDigest,
        operation = operation,
        eventKey = eventKey,
        actor = actor,
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        approvalStatus = approvalStatus,
        approvalVersion = approvalVersion,
        reasonDigest = reasonDigest,
        reasonLength = reasonLength,
        status = status.name,
        attemptCount = attemptCount,
        lastErrorCode = lastErrorCode,
        claimedBy = claimedBy,
        claimedAt = claimedAt?.toString(),
        claimExpiresAt = claimExpiresAt?.toString(),
        createdAt = createdAt.toString(),
        emittedAt = emittedAt?.toString(),
    )

internal fun PersistedSovereignOpsAuditOutboxRecordV1.toDomain(): SovereignOpsAuditOutboxRecord {
    require(schemaVersion == 1) { "unsupported-outbox-schema-version" }
    return SovereignOpsAuditOutboxRecord(
        outboxId = outboxId,
        aggregateType = aggregateType,
        aggregateIdDigest = aggregateIdDigest,
        operation = operation,
        eventKey = eventKey,
        actor = actor,
        workflowRunId = workflowRunId,
        correlationId = correlationId,
        approvalStatus = approvalStatus,
        approvalVersion = approvalVersion,
        reasonDigest = reasonDigest,
        reasonLength = reasonLength,
        createdAt = try {
            Instant.parse(createdAt)
        } catch (_: Exception) {
            OffsetDateTime.parse(createdAt).toInstant()
        },
        status = SovereignOpsAuditOutboxStatus.valueOf(status),
        attemptCount = attemptCount,
        lastErrorCode = lastErrorCode,
        claimedBy = claimedBy,
        claimedAt = claimedAt?.let { try { Instant.parse(it) } catch (_: Exception) { null } },
        claimExpiresAt = claimExpiresAt?.let { try { Instant.parse(it) } catch (_: Exception) { null } },
        emittedAt = emittedAt?.let { try { Instant.parse(it) } catch (_: Exception) { null } },
    )
}
