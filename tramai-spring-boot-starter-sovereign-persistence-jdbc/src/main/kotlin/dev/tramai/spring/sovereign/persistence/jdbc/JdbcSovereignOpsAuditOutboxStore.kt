@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.persistence.jdbc

import com.fasterxml.jackson.annotation.JsonInclude
import dev.tramai.core.identity.GovernedRunIdentity
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.QuarantineReport
import dev.tramai.spring.sovereign.ops.outbox.QuarantinedEvidenceSummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxGovernance
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxQuarantineStore
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
 * - Queryable columns contain hashes and identifiers -- no prompts,
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

    private companion object {
        /**
         * Maximum candidates considered by the blocking fallback in
         * [selectClaimableSerialized] before giving up. Each candidate is
         * acquired with a blocking (non-SKIP) `FOR UPDATE` and re-checked
         * under the lock; a candidate that was claimed by a concurrent
         * transaction while we waited is skipped. The bound is a liveness
         * guard against pathological churn, not a correctness mechanism.
         */
        const val CLAIM_BLOCKING_FALLBACK_LIMIT: Int = 5

        /** JDBC parameter indices start at 1; named so the insert reads positionally without literals. */
        private const val FIRST_PARAMETER: Int = 1

        /**
         * 0.7.6f: quarantined rows are excluded by anti-join rather than by attempting to decode them.
         * A decoded listing cannot include a record that cannot be decoded, so one damaged row would
         * otherwise make every page throw.
         */
        private const val NOT_QUARANTINED: String =
            "NOT EXISTS (SELECT 1 FROM audit_outbox_quarantine q WHERE q.outbox_id = audit_outbox.outbox_id)"

        private const val QUARANTINE_INSERT_SQL: String =
            "INSERT INTO audit_outbox_quarantine (outbox_id, label, quarantined_at, failure_detail, payload_digest) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (outbox_id) DO NOTHING"

        private const val QUARANTINE_LIST_SQL: String =
            "SELECT q.outbox_id, q.label, q.quarantined_at, q.failure_detail, q.payload_digest, o.event_key " +
                "FROM audit_outbox_quarantine q JOIN audit_outbox o ON o.outbox_id = q.outbox_id " +
                "ORDER BY q.quarantined_at DESC, q.outbox_id ASC LIMIT ?"

        private const val QUARANTINE_CIPHERTEXT_SQL: String =
            "SELECT o.encrypted_payload FROM audit_outbox o " +
                "JOIN audit_outbox_quarantine q ON q.outbox_id = o.outbox_id WHERE o.outbox_id = ?"

        /**
         * Candidates a quarantine scan may consider: dispatchable, never PREPARED, never already held.
         *
         * Scan eligibility mirrors claiming exactly: an EMITTING row whose lease expired is claimable, so
         * its evidence must be recoverable too -- otherwise an unreadable expired claim would keep failing
         * `claimPending` and healthy records would stall behind it.
         */
        private const val QUARANTINE_SCAN_WHERE: String =
            "$NOT_QUARANTINED AND (audit_outbox.status IN ('PENDING', 'FAILED_RETRYABLE') " +
                "OR (audit_outbox.status = 'EMITTING' AND audit_outbox.next_attempt_at IS NOT NULL " +
                "AND audit_outbox.next_attempt_at < ?))"

        /** Row lock used to revalidate the classified snapshot before the transition is written. */
        private const val QUARANTINE_LOCK_SQL: String =
            "SELECT status, version FROM audit_outbox WHERE outbox_id = ? FOR UPDATE"

        private const val QUARANTINE_MARK_GUARDED_SQL: String =
            "UPDATE audit_outbox SET status = 'FAILED_PERMANENT', last_failure_type = ?, " +
                "version = version + 1 WHERE outbox_id = ? AND status = ? AND version = ?"
    }

    // ══════════════════════════════════════════════════════════════════
    // SovereignOpsAuditOutboxStore SPI
    // ══════════════════════════════════════════════════════════════════

    override fun isDurable(): Boolean = true

    override suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord =
        appendRecord(record, runIdentity = null, requiredStatus = SovereignOpsAuditOutboxStatus.PREPARED)

    /**
     * 0.7.6f: commits a refusal record directly as PENDING -- one encrypted row, one transaction, no
     * separate transition to wait for. Only refusal evidence may take this path.
     */
    override suspend fun appendDispatchable(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord {
        require(record.evidenceKind == SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL) {
            "tramai-sovereign-ops-outbox-invalid-evidence-kind"
        }
        return appendRecord(record, runIdentity = null, requiredStatus = SovereignOpsAuditOutboxStatus.PENDING)
    }

    /**
     * 0.7.1d: appends a governed record -- the ordinary outbox fields and the complete canonical
     * identity in one encrypted row of one transaction. No side table, no identity columns.
     */
    internal suspend fun appendGoverned(entry: GovernedSovereignOpsAuditOutboxRecord) {
        appendRecord(
            entry.record,
            entry.runIdentity,
            requiredStatus = SovereignOpsAuditOutboxStatus.PREPARED,
        )
    }

    /**
     * 0.7.1d: resolves durable provenance from record existence first, so an absent record is never
     * mistaken for a legacy one and a governed row is never reported without its identity.
     */
    internal suspend fun governanceById(outboxId: String): SovereignOpsAuditOutboxGovernance =
        dataSource.connection.use { conn ->
            val row =
                selectById(conn, outboxId)
                    ?: return@use SovereignOpsAuditOutboxGovernance.NoRecord
            val decoded = row.toDecoded()
            validateQueryableColumns(decoded.record, row)
            when (val identity = decoded.runIdentity) {
                null -> {
                    SovereignOpsAuditOutboxGovernance.Legacy(decoded.record)
                }

                else -> {
                    SovereignOpsAuditOutboxGovernance.Governed(
                        GovernedSovereignOpsAuditOutboxRecord(decoded.record, identity),
                    )
                }
            }
        }

    private suspend fun appendRecord(
        record: SovereignOpsAuditOutboxRecord,
        runIdentity: GovernedRunIdentity?,
        requiredStatus: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord {
        require(record.outboxId.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-id" }
        require(record.eventKey.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-event-key" }
        require(record.status == requiredStatus) { "tramai-sovereign-ops-outbox-invalid-status" }

        return dataSource.connection.use { conn ->
            try {
                inOutboxTransaction(conn) { c ->
                    val encrypted = payloadCodec.encode(encodeOutboxRecord(record, runIdentity))

                    insertAppend(c, record, encrypted)
                    record
                }
            } catch (e: SQLException) {
                throw mapAppendException(e)
            }
        }
    }

    override suspend fun markReadyForDispatch(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord =
        dataSource.connection.use { conn ->
            inOutboxTransaction(conn) { c ->
                val row =
                    selectForUpdate(c, outboxId)
                        ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")

                val decoded = row.toDecoded()
                val domain = decoded.record
                validateQueryableColumns(domain, row)

                require(expectedStatus == SovereignOpsAuditOutboxStatus.PREPARED) {
                    "tramai-sovereign-ops-outbox-status-mismatch"
                }
                require(domain.status == expectedStatus) {
                    "tramai-sovereign-ops-outbox-status-mismatch"
                }

                val updated = domain.copy(status = SovereignOpsAuditOutboxStatus.PENDING)
                val encrypted = payloadCodec.encode(encodeOutboxRecord(updated, decoded.runIdentity))

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
                // Fast path: non-blocking SKIP LOCKED pass. When it finds rows,
                // claim them normally.
                var selected = selectClaimableForUpdateSkipLocked(c, actualLimit, now)
                if (selected.isEmpty()) {
                    // A concurrent terminal mutation may be holding the only
                    // eligible row's lock, making it invisible to SKIP LOCKED.
                    // Serialize against it (or prove no candidate exists)
                    // instead of polling: the row is claimable on both sides
                    // of the terminal transition, so a lock cannot legally
                    // create a "nothing claimable" window.
                    selected = selectClaimableSerialized(c, actualLimit, now)
                }

                val claimed =
                    selected.map { row ->
                        val decoded = row.toDecoded()
                        val record = decoded.record
                        validateQueryableColumns(record, row)
                        require(record.isDispatchable(now)) {
                            "tramai-sovereign-ops-outbox-not-dispatchable"
                        }
                        val updated =
                            record.copy(
                                status = SovereignOpsAuditOutboxStatus.EMITTING,
                                attemptCount = record.attemptCount + 1,
                                claimedBy = claimedBy,
                                claimedAt = now,
                                claimExpiresAt = claimExpiresAt,
                                lastErrorCode = null,
                            )
                        val encrypted = payloadCodec.encode(encodeOutboxRecord(updated, decoded.runIdentity))

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
        expectedAttemptCount: Int,
        emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord =
        dataSource.connection.use { conn ->
            inOutboxTransaction(conn) { conn ->
                val row =
                    selectForUpdate(conn, outboxId)
                        ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")

                val decoded = row.toDecoded()
                val domain = decoded.record
                validateQueryableColumns(domain, row)

                require(expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING) {
                    "tramai-sovereign-ops-outbox-status-mismatch"
                }
                require(domain.status == expectedStatus) {
                    "tramai-sovereign-ops-outbox-status-mismatch"
                }
                check(domain.attemptCount == expectedAttemptCount) {
                    "tramai-sovereign-ops-outbox-concurrent-update"
                }

                val updated =
                    domain.copy(
                        status = SovereignOpsAuditOutboxStatus.EMITTED,
                        emittedAt = emittedAt,
                    )
                val encrypted = payloadCodec.encode(encodeOutboxRecord(updated, decoded.runIdentity))

                val sql =
                    """
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
        expectedAttemptCount: Int,
        errorCode: String,
        retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord =
        dataSource.connection.use { conn ->
            inOutboxTransaction(conn) { conn ->
                val row =
                    selectForUpdate(conn, outboxId)
                        ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")

                val decoded = row.toDecoded()
                val domain = decoded.record
                validateQueryableColumns(domain, row)

                if (retryable) {
                    require(expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING) {
                        "tramai-sovereign-ops-outbox-status-mismatch"
                    }
                } else {
                    require(
                        expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING ||
                            expectedStatus == SovereignOpsAuditOutboxStatus.PREPARED,
                    ) {
                        "tramai-sovereign-ops-outbox-status-mismatch"
                    }
                }
                require(domain.status == expectedStatus) {
                    "tramai-sovereign-ops-outbox-status-mismatch"
                }
                check(domain.attemptCount == expectedAttemptCount) {
                    "tramai-sovereign-ops-outbox-concurrent-update"
                }

                val targetStatus =
                    if (retryable) {
                        SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE
                    } else {
                        SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
                    }

                val updated =
                    domain.copy(
                        status = targetStatus,
                        lastErrorCode = errorCode,
                    )
                val encrypted = payloadCodec.encode(encodeOutboxRecord(updated, decoded.runIdentity))

                val sql =
                    """
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
    ): List<SovereignOpsAuditOutboxRecord> = if (limit <= 0) emptyList() else listByExactStatus(status, limit)

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

    /**
     * 0.7.6f: the recovery surface, implemented as an inner class so this store's own shape (and its
     * quality baseline) is unchanged: quarantine is a separate contract, not another store operation.
     */
    private inner class JdbcQuarantine : SovereignOpsAuditOutboxQuarantineStore {
        /**
         * Classifies the scan batch before mutating anything: a batch in which nothing decodes is treated
         * as a deployment-key problem and aborts, so a wrong key can never quarantine healthy evidence.
         */
        override suspend fun quarantineUndecodableEvidence(
            limit: Int,
            now: Instant,
        ): QuarantineReport {
            if (limit <= 0) return QuarantineReport(0, 0, emptyList(), 0)
            val (candidates, skipped) =
                dataSource.connection.use { conn ->
                    selectQuarantineCandidates(conn, limit, now) to countQuarantined(conn)
                }
            val classified = candidates.map { row -> row to classify(row) }
            val failures =
                classified.mapNotNull { (_, verdict) -> (verdict as? QuarantineVerdict.Failure)?.failure }
            val decodable = classified.count { (_, verdict) -> verdict is QuarantineVerdict.Readable }
            requireUsableKey(classified.size, decodable, failures)
            val quarantined =
                classified.mapNotNull { (row, verdict) ->
                    (verdict as? QuarantineVerdict.Failure)?.let { quarantineOne(row, it.failure, now) }
                }
            return QuarantineReport(
                scanned = classified.size,
                decodable = decodable,
                quarantined = quarantined,
                skipped = skipped,
            )
        }

        override suspend fun listQuarantined(limit: Int): List<QuarantinedEvidenceSummary> {
            if (limit <= 0) return emptyList()
            return dataSource.connection.use { conn ->
                conn.prepareStatement(QUARANTINE_LIST_SQL).use { stmt ->
                    stmt.setInt(1, limit)
                    stmt.executeQuery().use { rs ->
                        val summaries =
                            generateSequence<QuarantinedEvidenceSummary> {
                                if (rs.next()) quarantinedSummaryOf(rs) else null
                            }
                        summaries.toList()
                    }
                }
            }
        }

        override suspend fun readQuarantinedCiphertext(locator: String): ByteArray? =
            dataSource.connection.use { conn -> selectQuarantinedCiphertext(conn, locator) }

        /**
         * One row's verdict, or null when it decodes and its columns agree.
         *
         * The two labels stay distinct: a payload that cannot be decrypted or decoded is
         * [SovereignOpsAuditOutboxQuarantineStore.LABEL_UNDECODABLE], while a payload that decodes but
         * contradicts its queryable columns is [SovereignOpsAuditOutboxQuarantineStore.LABEL_CORRUPT].
         */
        private fun classify(row: OutboxRow): QuarantineVerdict =
            runCatching { row.toDecoded() }.fold(
                onSuccess = { decoded -> columnsVerdict(decoded, row) },
                onFailure = { e -> decodeVerdict(e) },
            )

        /**
         * The control requirement and the identical-signature safeguard, both fail-closed.
         *
         * No control record means the batch behaves like a wrong deployment key, so nothing is quarantined.
         * The signature rule is stated separately: when every scanned row failed the same way the batch is
         * systemic too. A single successful decode is the control that proves the key works, and per-record
         * failures after that are quarantined even if their details happen to coincide.
         */
        private fun requireUsableKey(
            scanned: Int,
            decodable: Int,
            failures: List<QuarantineFailure>,
        ) {
            if (failures.isEmpty()) return
            val everyRowFailedIdentically = failures.size == scanned && failures.map { it.detail }.distinct().size == 1
            if (decodable == 0 || everyRowFailedIdentically) {
                throw IllegalStateException(SovereignOpsAuditOutboxQuarantineStore.ERROR_DECRYPTION_UNAVAILABLE)
            }
        }

        /**
         * Quarantines one row, or returns null when another worker got there first.
         *
         * Classification happens outside the writing transaction, so the row is locked and its snapshot
         * revalidated (status + version) before anything is written: two concurrent recovery workers must
         * not both report the same record as newly quarantined, and neither may transition a row whose
         * state changed since classification.
         */
        private fun quarantineOne(
            row: OutboxRow,
            failure: QuarantineFailure,
            now: Instant,
        ): QuarantinedEvidenceSummary? =
            dataSource.connection.use { conn ->
                inOutboxTransaction(conn) { c ->
                    val current = lockStatusAndVersion(c, row.outboxId)
                    if (current == null || current.first != row.status || current.second != row.version) {
                        return@inOutboxTransaction null
                    }
                    persistQuarantine(c, row, failure, now)
                    quarantinedSummary(row, failure, now)
                }
            }

        private fun quarantinedSummary(
            row: OutboxRow,
            failure: QuarantineFailure,
            now: Instant,
        ): QuarantinedEvidenceSummary =
            QuarantinedEvidenceSummary(
                locator = row.outboxId,
                outboxId = row.outboxId,
                eventKey = row.eventKey,
                payloadDigest = row.payloadDigest,
                label = failure.label,
                quarantinedAt = now,
                failureDetail = failure.detail,
            )

        /**
         * Records the metadata and marks the row FAILED_PERMANENT in one transaction.
         *
         * Nothing else changes: `encrypted_payload`, its encryption columns, `outbox_id` and `event_key`
         * are never rewritten, which is why the stored ciphertext stays byte-identical.
         */
        private fun persistQuarantine(
            conn: Connection,
            row: OutboxRow,
            failure: QuarantineFailure,
            now: Instant,
        ) {
            conn.prepareStatement(QUARANTINE_INSERT_SQL).use { stmt ->
                var index = FIRST_PARAMETER
                stmt.setString(index++, row.outboxId)
                stmt.setString(index++, failure.label)
                stmt.setTimestamp(index++, Timestamp.from(now))
                stmt.setString(index++, failure.detail)
                stmt.setString(index, row.payloadDigest)
                stmt.executeUpdate()
            }
            val transitioned =
                conn.prepareStatement(QUARANTINE_MARK_GUARDED_SQL).use { stmt ->
                    var index = FIRST_PARAMETER
                    stmt.setString(index++, failure.label)
                    stmt.setString(index++, row.outboxId)
                    stmt.setString(index++, row.status)
                    stmt.setLong(index, row.version)
                    stmt.executeUpdate()
                }
            check(transitioned == 1) { "tramai-outbox-quarantine-transition-lost" }
        }

        private fun selectQuarantineCandidates(
            conn: Connection,
            limit: Int,
            now: Instant,
        ): List<OutboxRow> {
            val sql =
                "$selectColumns FROM audit_outbox WHERE $QUARANTINE_SCAN_WHERE ORDER BY created_at ASC LIMIT ?"
            return conn.prepareStatement(sql).use { stmt ->
                stmt.setTimestamp(1, Timestamp.from(now))
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<OutboxRow>()
                    while (rs.next()) results.add(mapRow(rs))
                    results
                }
            }
        }

        private fun countQuarantined(conn: Connection): Int =
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT count(*) FROM audit_outbox_quarantine").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
    }

    override fun quarantineStore(): SovereignOpsAuditOutboxQuarantineStore = JdbcQuarantine()

    /** Locks one row and reports the state it was in, so a stale classification cannot transition it. */
    private fun lockStatusAndVersion(
        conn: Connection,
        outboxId: String,
    ): Pair<String, Long>? =
        conn.prepareStatement(QUARANTINE_LOCK_SQL).use { stmt ->
            stmt.setString(FIRST_PARAMETER, outboxId)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getLong(2) else null }
        }

    /** Bounded, operator-readable detail: never an unbounded stack of internal state. */
    private fun boundedDetail(e: Exception): String =
        (e.message ?: e::class.simpleName ?: "undecodable-evidence")
            .take(SovereignOpsAuditOutboxQuarantineStore.MAX_FAILURE_DETAIL)

    /** A payload this build cannot read is undecodable evidence -- unless a newer build wrote it. */
    private fun decodeVerdict(e: Throwable): QuarantineVerdict {
        val newerBuild =
            e is IllegalStateException && e.message?.startsWith(ERROR_UNSUPPORTED_SCHEMA_VERSION) == true
        return if (newerBuild) {
            QuarantineVerdict.Unsupported
        } else {
            QuarantineVerdict.Failure(
                QuarantineFailure(
                    SovereignOpsAuditOutboxQuarantineStore.LABEL_UNDECODABLE,
                    boundedDetail(e as? Exception ?: IllegalStateException(e)),
                ),
            )
        }
    }

    /** A payload that decodes but contradicts its queryable columns is corrupt evidence, not undecodable. */
    private fun columnsVerdict(
        decoded: DecodedOutboxRecord,
        row: OutboxRow,
    ): QuarantineVerdict =
        runCatching { validateQueryableColumns(decoded.record, row) }.fold(
            onSuccess = { QuarantineVerdict.Readable },
            onFailure = { e ->
                QuarantineVerdict.Failure(
                    QuarantineFailure(
                        SovereignOpsAuditOutboxQuarantineStore.LABEL_CORRUPT,
                        boundedDetail(e as? Exception ?: IllegalStateException(e)),
                    ),
                )
            },
        )

    private fun quarantinedSummaryOf(rs: ResultSet): QuarantinedEvidenceSummary =
        QuarantinedEvidenceSummary(
            locator = rs.getString("outbox_id"),
            outboxId = rs.getString("outbox_id"),
            eventKey = rs.getString("event_key"),
            payloadDigest = rs.getString("payload_digest") ?: "",
            label = rs.getString("label"),
            quarantinedAt = rs.getTimestamp("quarantined_at").toInstant(),
            failureDetail = rs.getString("failure_detail"),
        )

    private fun selectQuarantinedCiphertext(
        conn: Connection,
        locator: String,
    ): ByteArray? =
        conn.prepareStatement(QUARANTINE_CIPHERTEXT_SQL).use { stmt ->
            stmt.setString(1, locator)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getBytes(1) else null }
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

    /**
     * 0.7.1d: decrypts and decodes one row's payload, keeping the canonical identity alongside the
     * ordinary record so a transition re-encodes exactly the shape it read.
     *
     * Only the envelope step is wrapped here, so the codec's own failure contract (corruption vs
     * unsupported schema version) reaches callers unchanged instead of being flattened.
     */
    private fun OutboxRow.toDecoded(): DecodedOutboxRecord {
        val encrypted =
            JdbcEncryptedAuditOutboxPayload(
                ciphertext = encryptedPayload,
                keyId = encryptionKeyId,
                algorithm = encryptionAlgorithm,
                nonce = encryptionNonce,
                payloadDigest = payloadDigest,
            )
        val plaintext =
            try {
                payloadCodec.decode(encrypted)
            } catch (e: Exception) {
                throw IllegalStateException("audit-outbox-payload-decryption-failed", e)
            }
        return decodeOutboxRecord(plaintext)
    }

    private fun OutboxRow.toDomain(): SovereignOpsAuditOutboxRecord = toDecoded().record

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
            -> {
                true
            }

            SovereignOpsAuditOutboxStatus.EMITTING -> {
                val expiresAt = claimExpiresAt
                expiresAt != null && expiresAt.isBefore(now)
            }

            SovereignOpsAuditOutboxStatus.PREPARED,
            SovereignOpsAuditOutboxStatus.EMITTED,
            SovereignOpsAuditOutboxStatus.FAILED_PERMANENT,
            -> {
                false
            }
        }

    // ══════════════════════════════════════════════════════════════════
    // SQL -- INSERT
    // ══════════════════════════════════════════════════════════════════

    private fun insertAppend(
        conn: Connection,
        record: SovereignOpsAuditOutboxRecord,
        encrypted: JdbcEncryptedAuditOutboxPayload,
    ) {
        val sql =
            """
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
    // SQL -- SELECT helpers
    // ══════════════════════════════════════════════════════════════════

    private val selectColumns =
        """
        SELECT outbox_id, event_key, status, correlation_key_hash,
               created_at, claimed_at, dispatched_at,
               attempt_count, last_failure_type, next_attempt_at,
               encrypted_payload, encryption_key_id, encryption_algorithm,
               encryption_nonce, payload_digest, version
        """.trimIndent()

    private fun selectForUpdate(
        conn: Connection,
        outboxId: String,
    ): OutboxRow? {
        val sql = "$selectColumns FROM audit_outbox WHERE outbox_id = ? AND $NOT_QUARANTINED FOR UPDATE"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, outboxId)
            stmt.executeQuery().let { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    private fun selectById(
        conn: Connection,
        outboxId: String,
    ): OutboxRow? {
        val sql = "$selectColumns FROM audit_outbox WHERE outbox_id = ? AND $NOT_QUARANTINED"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, outboxId)
            stmt.executeQuery().let { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    private fun selectByEventKey(
        conn: Connection,
        eventKey: String,
    ): OutboxRow? {
        val sql = "$selectColumns FROM audit_outbox WHERE event_key = ? AND $NOT_QUARANTINED"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, eventKey)
            stmt.executeQuery().let { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    private fun selectByStatus(
        conn: Connection,
        status: String,
        limit: Int,
    ): List<OutboxRow> {
        val sql =
            "$selectColumns FROM audit_outbox WHERE status = ? AND $NOT_QUARANTINED " +
                "ORDER BY created_at ASC LIMIT ?"
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
        val sql =
            """
            $selectColumns FROM audit_outbox
            WHERE
                $NOT_QUARANTINED
                AND (
                status IN ('PENDING', 'FAILED_RETRYABLE')
                OR (
                    status = 'EMITTING'
                    AND next_attempt_at IS NOT NULL
                    AND next_attempt_at < ?
                )
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

    /**
     * Blocking fallback for [claimPending] when the non-blocking fast path
     * found nothing.
     *
     * A concurrent terminal mutation can hold the only eligible row's lock;
     * SKIP LOCKED would pretend the row does not exist, which is not a legal
     * answer here because the row is claimable on both sides of the terminal
     * transition. Instead:
     *
     * 1. probe for a claimable candidate with a non-locking read -- if no
     *    candidate exists, the empty result is proven, not guessed;
     * 2. if a candidate exists, block on THAT candidate by primary key with a
     *    plain `FOR UPDATE` (no SKIP LOCKED). The caller holds no outbox row
     *    locks at this point (the fast path selected zero rows), so this wait
     *    cannot participate in a claim-vs-claim lock cycle;
     * 3. re-read the row under the acquired lock; if it is still claimable,
     *    claim it; otherwise a concurrent transaction took it while we
     *    waited -- skip and re-evaluate the next candidate.
     *
     * The bound [CLAIM_BLOCKING_FALLBACK_LIMIT] is a liveness guard only.
     */
    private fun selectClaimableSerialized(
        conn: Connection,
        limit: Int,
        now: Instant,
    ): List<OutboxRow> {
        val candidates = selectClaimableCandidateIds(conn, now, limit)
        if (candidates.isEmpty()) return emptyList()

        var checked = 0
        for (candidateId in candidates) {
            if (checked >= CLAIM_BLOCKING_FALLBACK_LIMIT) break
            val row = selectByIdForUpdate(conn, candidateId) ?: continue
            val record = row.toDomain()
            if (record.isDispatchable(now)) return listOf(row)
            checked++
        }
        return emptyList()
    }

    /**
     * Non-locking probe for claimable candidate ids: the same eligibility
     * predicate as [selectClaimableForUpdateSkipLocked] but without the
     * `FOR UPDATE SKIP LOCKED` clause, so a hidden locked row is still
     * visible. Returns ids in claim order.
     */
    private fun selectClaimableCandidateIds(
        conn: Connection,
        now: Instant,
        limit: Int,
    ): List<String> {
        val sql =
            """
            SELECT outbox_id FROM audit_outbox
            WHERE
                $NOT_QUARANTINED
                AND (
                    status IN ('PENDING', 'FAILED_RETRYABLE')
                    OR (
                        status = 'EMITTING'
                        AND next_attempt_at IS NOT NULL
                        AND next_attempt_at < ?
                    )
                )
            ORDER BY created_at ASC
            LIMIT ?
            """.trimIndent()

        return conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(now))
            stmt.setInt(2, limit)
            stmt.executeQuery().let { rs ->
                val results = mutableListOf<String>()
                while (rs.next()) results.add(rs.getString(1))
                results
            }
        }
    }

    private fun selectByIdForUpdate(
        conn: Connection,
        outboxId: String,
    ): OutboxRow? {
        val sql = "$selectColumns FROM audit_outbox WHERE outbox_id = ? AND $NOT_QUARANTINED FOR UPDATE"
        return conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, outboxId)
            stmt.executeQuery().let { rs ->
                if (rs.next()) mapRow(rs) else null
            }
        }
    }

    private fun selectExpiredEmitting(
        conn: Connection,
        now: Instant,
        limit: Int,
    ): List<OutboxRow> {
        val sql =
            """
            $selectColumns FROM audit_outbox
            WHERE status = 'EMITTING'
              AND $NOT_QUARANTINED
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
    // SQL -- UPDATE helpers
    // ══════════════════════════════════════════════════════════════════

    private fun updateStatusAndPayload(
        conn: Connection,
        outboxId: String,
        expectedVersion: Long,
        newStatus: String,
        encrypted: JdbcEncryptedAuditOutboxPayload,
    ) {
        val sql =
            """
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
        val sql =
            """
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

    /**
     * One row's recovery verdict.
     *
     * [Failure] is evidence this build cannot read and that is therefore quarantined; [Readable] is the
     * control that proves the deployment key works; [Unsupported] is a payload written by a newer build,
     * which is preserved untouched rather than permanently condemned by a rollback.
     */
    private sealed interface QuarantineVerdict {
        class Failure(
            val failure: QuarantineFailure,
        ) : QuarantineVerdict

        object Readable : QuarantineVerdict

        object Unsupported : QuarantineVerdict
    }

    /** One row's quarantine verdict: a label and a bounded detail. */
    private data class QuarantineFailure(
        val label: String,
        val detail: String,
    )

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
        val createdAt =
            rs
                .getTimestamp("created_at")
                ?.toInstant()
                ?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) }
                ?: throw IllegalStateException("audit-outbox-missing-created-at")

        return OutboxRow(
            outboxId =
                rs.getString("outbox_id")
                    ?: throw IllegalStateException("audit-outbox-missing-outbox-id"),
            eventKey =
                rs.getString("event_key")
                    ?: throw IllegalStateException("audit-outbox-missing-event-key"),
            status =
                rs.getString("status")
                    ?: throw IllegalStateException("audit-outbox-missing-status"),
            correlationKeyHash = rs.getString("correlation_key_hash"),
            createdAt = createdAt,
            claimedAt =
                rs
                    .getTimestamp("claimed_at")
                    ?.toInstant()
                    ?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            dispatchedAt =
                rs
                    .getTimestamp("dispatched_at")
                    ?.toInstant()
                    ?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            attemptCount = rs.getInt("attempt_count"),
            lastFailureType = rs.getString("last_failure_type"),
            nextAttemptAt =
                rs
                    .getTimestamp("next_attempt_at")
                    ?.toInstant()
                    ?.let { OffsetDateTime.ofInstant(it, ZoneOffset.UTC) },
            encryptedPayload = rs.getBytes("encrypted_payload") ?: ByteArray(0),
            encryptionKeyId =
                rs.getString("encryption_key_id")
                    ?: throw IllegalStateException("audit-outbox-missing-key-id"),
            encryptionAlgorithm =
                rs.getString("encryption_algorithm")
                    ?: throw IllegalStateException("audit-outbox-missing-algorithm"),
            encryptionNonce = rs.getBytes("encryption_nonce") ?: ByteArray(0),
            payloadDigest =
                rs.getString("payload_digest")
                    ?: throw IllegalStateException("audit-outbox-missing-payload-digest"),
            version = rs.getLong("version"),
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Exception mapping
    // ══════════════════════════════════════════════════════════════════

    private fun mapAppendException(e: SQLException): RuntimeException {
        val message = e.message ?: ""
        return when {
            message.contains("uq_audit_outbox_event_key") || message.contains("audit_outbox_event_key_key") -> {
                IllegalArgumentException("tramai-sovereign-ops-outbox-duplicate-event-key")
            }

            message.contains("audit_outbox_pkey") -> {
                IllegalArgumentException("tramai-sovereign-ops-outbox-duplicate-id")
            }

            else -> {
                IllegalStateException("tramai-sovereign-ops-outbox-database-failure", e)
            }
        }
    }

    /**
     * Deliberately non-suspend helper (the #267/#271 cleanup model): runs
     * [block] in one explicit transaction. Failure precedence is primary
     * operation / cancellation, then rollback failure, then
     * autoCommit-restore failure -- later cleanup failures are attached as
     * suppressed to the primary, never replacing it.
     */
    private fun <T> inOutboxTransaction(
        conn: Connection,
        block: (Connection) -> T,
    ): T {
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
    val approvalStatus: String?,
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
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val actorIssuer: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val actorIdentityDigest: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val previousApprovalStatus: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val previousApprovalVersion: Long? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val evidenceKind: String? = null,
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
        actorIssuer = actorIssuer,
        actorIdentityDigest = actorIdentityDigest,
        previousApprovalStatus = previousApprovalStatus,
        previousApprovalVersion = previousApprovalVersion,
        evidenceKind = evidenceKind?.name,
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
        createdAt =
            try {
                Instant.parse(createdAt)
            } catch (_: Exception) {
                OffsetDateTime.parse(createdAt).toInstant()
            },
        status = SovereignOpsAuditOutboxStatus.valueOf(status),
        attemptCount = attemptCount,
        lastErrorCode = lastErrorCode,
        claimedBy = claimedBy,
        claimedAt =
            claimedAt?.let {
                try {
                    Instant.parse(it)
                } catch (_: Exception) {
                    null
                }
            },
        claimExpiresAt =
            claimExpiresAt?.let {
                try {
                    Instant.parse(it)
                } catch (_: Exception) {
                    null
                }
            },
        emittedAt =
            emittedAt?.let {
                try {
                    Instant.parse(it)
                } catch (_: Exception) {
                    null
                }
            },
        actorIssuer = actorIssuer,
        actorIdentityDigest = actorIdentityDigest,
        previousApprovalStatus = previousApprovalStatus,
        previousApprovalVersion = previousApprovalVersion,
        evidenceKind = evidenceKind?.let { SovereignOpsAuditEvidenceKind.valueOf(it) },
    )
}
