package dev.tramai.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.CURRENT_AUDIT_SCHEMA_VERSION
import dev.tramai.security.audit.calculateHash
import java.util.concurrent.CancellationException
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * JDBC-backed [AuditStore] implementation using the `audit_events` and
 * `audit_stream_heads` tables in PostgreSQL (V1 + V3 schema).
 *
 * ## Data model
 * Audit events are persisted as JSON-serialized [PersistedAuditEventV1] payloads
 * encrypted via an injectable [JdbcAuditPayloadCodec]. Non-sensitive event metadata
 * (stream_id, event_id, sequence_number, event_hash, previous_event_hash, schema_version)
 * is stored in dedicated columns for queryability and hash-chain verification.
 *
 * A companion `audit_stream_heads` table provides:
 * - Stream-level serialization via `SELECT ... FOR UPDATE`
 * - Deterministic sequence allocation without scanning all events
 * - Concurrency safety for same-stream appends
 *
 * ## Append algorithm
 * ```
 * transaction {
 *     INSERT stream head row if not exists (idempotent)
 *     SELECT ... FROM audit_stream_heads WHERE stream_id = ? FOR UPDATE
 *
 *     // Stream lock acquired — eventFactory called here
 *     val latest = if head.latestSequence > 0 then read latest event else null
 *     val event = eventFactory(latest)
 *
 *     validate(event, latest, stream head)
 *     encrypt and INSERT INTO audit_events
 *     UPDATE audit_stream_heads
 *     commit
 * }
 * ```
 *
 * ## Security model
 * - Full audit event payloads are encrypted at rest via the injected codec.
 * - Queryable columns (stream_id, event_id, sequence_number, event_hash,
 *   previous_event_hash) contain hashes and identifiers — no prompts,
 *   model outputs, or PII.
 * - Raw metadata, actor, decision, reason code and other sensitive fields
 *   are stored only in the encrypted payload.
 * - All encryption metadata columns are populated when a payload is stored.
 * - Decryption failure fails closed — corrupted events cannot be read silently.
 *
 * ## Concurrency
 * - [appendNext] uses an explicit transaction with `SELECT ... FOR UPDATE` on
 *   the stream head row, ensuring exactly one appender at a time per stream.
 * - [readStream], [readStreamPage], and [latestEvent] do not lock (immutable
 *   events are safe to read concurrently).
 *
 * @param dataSource The [DataSource] providing PostgreSQL connections.
 * @param payloadCodec The codec for encrypting/decrypting event payloads.
 * @param clock The clock for timestamp generation.
 * @param maxPageSize Maximum page size for [readStreamPage] (default 500).
 */
class JdbcAuditStore(
    private val dataSource: DataSource,
    private val payloadCodec: JdbcAuditPayloadCodec,
    private val clock: Clock = Clock.systemUTC(),
    private val maxPageSize: Int = 500,
) : AuditStore {

    init {
        require(maxPageSize > 0) { "maxPageSize must be positive" }
    }

    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    // ══════════════════════════════════════════════════════════════════
    // AuditStore SPI
    // ══════════════════════════════════════════════════════════════════

    override suspend fun appendNext(
        auditStreamId: String,
        eventFactory: (latest: AuditEvent?) -> AuditEvent,
    ): AuditEvent {
        require(auditStreamId.isNotBlank()) { "audit-store-invalid-stream-id" }

        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            var primaryFailure: Exception? = null
            try {
                // Ensure stream head row exists (idempotent)
                ensureStreamHead(conn, auditStreamId)

                // Acquire the stream-level lock via FOR UPDATE on the head row.
                // This serializes all concurrent appenders to the same stream.
                val head = selectHeadForUpdate(conn, auditStreamId)
                val latest = resolveLatestFromHead(conn, auditStreamId, head)

                // eventFactory is called INSIDE the transaction, AFTER the
                // stream-level lock is acquired. Factories should be
                // deterministic or side-effect-free — external side effects
                // (webhooks, file writes, API calls) performed inside the
                // factory will not be rolled back.
                val event = eventFactory(latest)

                // Validate the event against the stream head and latest
                validateEvent(event, auditStreamId, latest, head)

                // Serialize and encrypt
                val payloadJson = mapper.writeValueAsBytes(
                    PersistedAuditEventV1(
                        schemaVersion = event.schemaVersion,
                        hashAlgorithm = event.hashAlgorithm.wireName,
                        auditStreamId = event.auditStreamId,
                        eventId = event.eventId,
                        sequenceNumber = event.sequenceNumber,
                        workflowRunId = event.workflowRunId,
                        correlationId = event.correlationId,
                        actor = event.actor,
                        enforcementPoint = event.enforcementPoint,
                        decision = event.decision,
                        policyVersion = event.policyVersion,
                        workflowDigest = event.workflowDigest,
                        previousEventHash = event.previousEventHash,
                        eventHash = event.eventHash,
                        timestamp = event.timestamp.toString(),
                        reasonCode = event.reasonCode,
                        metadata = event.metadata,
                    ),
                )
                val encrypted = payloadCodec.encode(payloadJson)
                val nowOdt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

                // Insert the audit event
                insertEvent(conn, event, encrypted, nowOdt)

                // Update the stream head
                updateHead(conn, auditStreamId, event, nowOdt)

                conn.commit()
                return event
            } catch (e: Exception) {
                if (e is CancellationException) {
                    primaryFailure = e
                    rollbackSuppressing(conn, e)
                    throw e
                }
                primaryFailure = e
                rollbackSuppressing(conn, e)
                throw e
            } finally {
                restoreAutoCommitSuppressing(conn, previousAutoCommit, primaryFailure)
            }
        }
    }

    /**
     * Deliberately non-suspend helper (the #267 cleanup model): rolls back,
     * attaching a rollback failure as suppressed to [primary] so cleanup can
     * never mask the primary exception or cancellation. Runs for
     * cancellations too — a cancelled append leaves no partial stream head.
     */
    private fun rollbackSuppressing(conn: Connection, primary: Exception) {
        try {
            conn.rollback()
        } catch (rollbackFailure: Exception) {
            primary.addSuppressed(rollbackFailure)
        }
    }

    /**
     * Deliberately non-suspend helper (the #267 cleanup model): restores the
     * connection's autoCommit state, attaching the restore failure as
     * suppressed to the primary when one exists (otherwise the restore
     * failure is the only failure and propagates).
     */
    private fun restoreAutoCommitSuppressing(
        conn: Connection,
        previousAutoCommit: Boolean,
        primary: Exception?,
    ) {
        try {
            conn.autoCommit = previousAutoCommit
        } catch (restoreFailure: Exception) {
            if (primary != null) {
                primary.addSuppressed(restoreFailure)
            } else {
                throw restoreFailure
            }
        }
    }

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> {
        require(auditStreamId.isNotBlank()) { "audit-store-invalid-stream-id" }

        dataSource.connection.use { conn ->
            val events = readAllEvents(conn, auditStreamId)
            if (events.isEmpty()) return emptyList()

            // Full chain validation
            validateChain(auditStreamId, events)

            return events.map { immutableCopy(it) }
        }
    }

    override suspend fun readStreamPage(
        auditStreamId: String,
        afterSequenceNumber: Long?,
        limit: Int,
    ): List<AuditEvent> {
        require(auditStreamId.isNotBlank()) { "audit-store-invalid-stream-id" }
        require(limit > 0) { "audit-store-invalid-limit" }
        require(afterSequenceNumber == null || afterSequenceNumber >= 0) {
            "audit-store-invalid-cursor"
        }

        dataSource.connection.use { conn ->
            val events = readEventPage(conn, auditStreamId, afterSequenceNumber, limit)
            if (events.isEmpty()) return emptyList()

            // Page-level validation: local invariants + chain continuity within page
            validatePage(auditStreamId, events)

            return events.map { immutableCopy(it) }
        }
    }

    override suspend fun latestEvent(auditStreamId: String): AuditEvent? {
        require(auditStreamId.isNotBlank()) { "audit-store-invalid-stream-id" }

        dataSource.connection.use { conn ->
            val latest = readLatestEvent(conn, auditStreamId) ?: return null

            // Validate self-hash only (no full-chain scan).
            // Full chain validation is the responsibility of readStream().
            require(latest.eventHash == latest.copy(eventHash = "").calculateHash()) {
                "audit-event-hash-mismatch"
            }

            return immutableCopy(latest)
        }
    }

    // ── Internal data types ──────────────────────────────────────────

    private data class StreamHead(
        val streamId: String,
        val latestSequence: Long,
        val latestEventId: String?,
        val latestEventHash: String?,
        val updatedAt: OffsetDateTime,
    )

    /** JSON-serializable event DTO matching the file-backed [PersistedAuditEventV1]. */
    private data class PersistedAuditEventV1(
        val schemaVersion: Int,
        val hashAlgorithm: String,
        val auditStreamId: String,
        val eventId: String,
        val sequenceNumber: Long,
        val workflowRunId: String? = null,
        val correlationId: String? = null,
        val actor: String? = null,
        val enforcementPoint: String,
        val decision: String,
        val policyVersion: String? = null,
        val workflowDigest: String? = null,
        val previousEventHash: String? = null,
        val eventHash: String,
        val timestamp: String,
        val reasonCode: String? = null,
        val metadata: Map<String, String> = emptyMap(),
    )

    private fun AuditEvent.toPersistedV1(): PersistedAuditEventV1 = PersistedAuditEventV1(
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

    private fun PersistedAuditEventV1.toDomain(): AuditEvent = AuditEvent(
        schemaVersion = schemaVersion,
        hashAlgorithm = AuditHashAlgorithm.entries.first { it.wireName == hashAlgorithm },
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

    // ── Stream head operations ───────────────────────────────────────

    /**
     * Ensures a stream head row exists. Idempotent — uses INSERT ... ON CONFLICT DO NOTHING.
     */
    private fun ensureStreamHead(conn: Connection, auditStreamId: String) {
        val sql = """
            INSERT INTO audit_stream_heads (stream_id, latest_sequence, updated_at)
            VALUES (?, 0, NOW())
            ON CONFLICT (stream_id) DO NOTHING
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, auditStreamId)
            stmt.executeUpdate()
        }
    }

    /**
     * Selects the stream head row with FOR UPDATE — acquires the stream-level lock.
     */
    private fun selectHeadForUpdate(conn: Connection, auditStreamId: String): StreamHead {
        val sql = """
            SELECT stream_id, latest_sequence, latest_event_id, latest_event_hash, updated_at
            FROM audit_stream_heads
            WHERE stream_id = ?
            FOR UPDATE
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, auditStreamId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) {
                    // Should not happen after ensureStreamHead, but guard anyway
                    throw IllegalStateException("audit-stream-head-not-found: $auditStreamId")
                }
                return mapHeadRow(rs)
            }
        }
    }

    private fun mapHeadRow(rs: ResultSet): StreamHead = StreamHead(
        streamId = rs.getString("stream_id"),
        latestSequence = rs.getLong("latest_sequence"),
        latestEventId = rs.getString("latest_event_id"),
        latestEventHash = rs.getString("latest_event_hash"),
        updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java),
    )

    /**
     * Updates the stream head after a successful event append.
     */
    private fun updateHead(
        conn: Connection,
        auditStreamId: String,
        event: AuditEvent,
        nowOdt: OffsetDateTime,
    ) {
        val sql = """
            UPDATE audit_stream_heads
            SET latest_sequence = ?,
                latest_event_id = ?,
                latest_event_hash = ?,
                updated_at = ?
            WHERE stream_id = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setLong(1, event.sequenceNumber)
            stmt.setString(2, event.eventId)
            stmt.setString(3, event.eventHash)
            stmt.setObject(4, nowOdt)
            stmt.setString(5, auditStreamId)
            val updated = stmt.executeUpdate()
            require(updated == 1) { "audit-stream-head-update-failed" }
        }
    }

    // ── Event validation ─────────────────────────────────────────────

    private fun validateEvent(
        event: AuditEvent,
        expectedAuditStreamId: String,
        previousEvent: AuditEvent?,
        head: StreamHead,
    ) {
        require(event.eventId.isNotBlank()) { "audit-store-invalid-event-id" }
        require(event.auditStreamId == expectedAuditStreamId) { "audit-stream-id-mismatch" }
        require(event.schemaVersion == CURRENT_AUDIT_SCHEMA_VERSION) {
            "audit-schema-version-unsupported"
        }

        val expectedSequence = head.latestSequence + 1L
        require(event.sequenceNumber == expectedSequence) { "audit-sequence-gap" }

        val expectedPreviousHash = head.latestEventHash
        require(event.previousEventHash == expectedPreviousHash) { "audit-hash-chain-broken" }

        require(event.eventHash == event.copy(eventHash = "").calculateHash()) {
            "audit-event-hash-mismatch"
        }
    }

    /**
     * Validates the full event chain: all events in the stream have correct
     * stream binding, schema version, sequence ordering, hash chain continuity,
     * and self-hash correctness. Also checks for duplicate event IDs.
     */
    private fun validateChain(expectedAuditStreamId: String, events: List<AuditEvent>) {
        val seenEventIds = mutableSetOf<String>()
        var previousEvent: AuditEvent? = null
        for (event in events) {
            require(event.auditStreamId == expectedAuditStreamId) { "audit-stream-id-mismatch" }
            require(event.schemaVersion == CURRENT_AUDIT_SCHEMA_VERSION) {
                "audit-schema-version-unsupported"
            }

            val expectedSequence = (previousEvent?.sequenceNumber ?: 0L) + 1L
            require(event.sequenceNumber == expectedSequence) { "audit-sequence-gap" }

            val expectedPreviousHash = previousEvent?.eventHash
            require(event.previousEventHash == expectedPreviousHash) { "audit-hash-chain-broken" }

            require(event.eventHash == event.copy(eventHash = "").calculateHash()) {
                "audit-event-hash-mismatch"
            }

            require(event.eventId !in seenEventIds) { "audit-duplicate-event-id" }
            seenEventIds.add(event.eventId)

            previousEvent = event
        }
    }

    /**
     * Page-level validation: validates local invariants (stream binding, schema
     * version, sequence ordering, self-hash) and chain continuity within the page.
     * Full-chain validation is deferred to [readStream].
     */
    private fun validatePage(expectedAuditStreamId: String, events: List<AuditEvent>) {
        var previousEvent: AuditEvent? = null
        for (event in events) {
            require(event.auditStreamId == expectedAuditStreamId) { "audit-stream-id-mismatch" }
            require(event.schemaVersion == CURRENT_AUDIT_SCHEMA_VERSION) {
                "audit-schema-version-unsupported"
            }
            require(event.eventHash == event.copy(eventHash = "").calculateHash()) {
                "audit-event-hash-mismatch"
            }

            // Chain continuity within the page
            if (previousEvent != null) {
                require(event.previousEventHash == previousEvent.eventHash) {
                    "audit-hash-chain-broken-within-page"
                }
                require(event.sequenceNumber == previousEvent.sequenceNumber + 1) {
                    "audit-sequence-gap-within-page"
                }
            }

            previousEvent = event
        }
    }

    // ── Event insertion ──────────────────────────────────────────────

    private fun insertEvent(
        conn: Connection,
        event: AuditEvent,
        encrypted: JdbcEncryptedAuditPayload,
        nowOdt: OffsetDateTime,
    ) {
        val sql = """
            INSERT INTO audit_events (
                stream_id, sequence_number, event_id, event_type, event_hash,
                previous_event_hash, occurred_at, sanitized_actor,
                encrypted_payload, encryption_key_id, encryption_algorithm,
                encryption_nonce, payload_digest, schema_version
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?,
                ?, ?, ?
            )
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, event.auditStreamId)
            stmt.setLong(2, event.sequenceNumber)
            stmt.setString(3, event.eventId)
            stmt.setString(4, event.decision) // event_type maps to decision in V1
            stmt.setString(5, event.eventHash)
            stmt.setString(6, event.previousEventHash)
            stmt.setObject(7, nowOdt)
            stmt.setString(8, event.actor?.let { sha256Hex(it) })
            stmt.setBytes(9, encrypted.ciphertext)
            stmt.setString(10, encrypted.keyId)
            stmt.setString(11, encrypted.algorithm)
            stmt.setBytes(12, encrypted.nonce)
            stmt.setString(13, encrypted.payloadDigest)
            stmt.setString(14, event.schemaVersion.toString())

            try {
                stmt.executeUpdate()
            } catch (e: SQLException) {
                if (e.sqlState == "23505") {
                    throw IllegalArgumentException("audit-duplicate-event-id")
                }
                throw e
            }
        }
    }

    // ── Event reads ──────────────────────────────────────────────────

    /**
     * Resolves the latest event from the stream head, validating that the head
     * metadata matches the actual latest event row. Fails closed on corruption.
     */
    private fun resolveLatestFromHead(
        conn: Connection,
        auditStreamId: String,
        head: StreamHead,
    ): AuditEvent? {
        if (head.latestSequence == 0L) {
            require(head.latestEventId == null) { "audit-stream-head-corrupted: sequence 0 with non-null event id" }
            require(head.latestEventHash == null) { "audit-stream-head-corrupted: sequence 0 with non-null event hash" }
            return null
        }

        val latest = readEventBySequence(conn, auditStreamId, head.latestSequence)
            ?: throw IllegalStateException("audit-stream-head-latest-event-missing")

        require(latest.eventId == head.latestEventId) {
            "audit-stream-head-event-id-mismatch"
        }
        require(latest.eventHash == head.latestEventHash) {
            "audit-stream-head-event-hash-mismatch"
        }

        return latest
    }

    private fun readAllEvents(conn: Connection, auditStreamId: String): List<AuditEvent> {
        val sql = """
            SELECT stream_id, sequence_number, event_id, event_type, event_hash,
                   previous_event_hash, occurred_at, sanitized_actor,
                   encrypted_payload, encryption_key_id, encryption_algorithm,
                   encryption_nonce, payload_digest, schema_version
            FROM audit_events
            WHERE stream_id = ?
            ORDER BY sequence_number ASC
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, auditStreamId)
            stmt.executeQuery().use { rs ->
                val events = mutableListOf<AuditEvent>()
                while (rs.next()) {
                    events.add(mapEventRow(rs))
                }
                return events
            }
        }
    }

    private fun readEventPage(
        conn: Connection,
        auditStreamId: String,
        afterSequenceNumber: Long?,
        limit: Int,
    ): List<AuditEvent> {
        val cappedLimit = minOf(limit, maxPageSize)
        val sql = """
            SELECT stream_id, sequence_number, event_id, event_type, event_hash,
                   previous_event_hash, occurred_at, sanitized_actor,
                   encrypted_payload, encryption_key_id, encryption_algorithm,
                   encryption_nonce, payload_digest, schema_version
            FROM audit_events
            WHERE stream_id = ?
              AND (? IS NULL OR sequence_number > ?)
            ORDER BY sequence_number ASC
            LIMIT ?
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, auditStreamId)
            if (afterSequenceNumber != null) {
                stmt.setObject(2, afterSequenceNumber)
                stmt.setObject(3, afterSequenceNumber)
            } else {
                stmt.setNull(2, java.sql.Types.BIGINT)
                stmt.setNull(3, java.sql.Types.BIGINT)
            }
            stmt.setInt(4, cappedLimit)
            stmt.executeQuery().use { rs ->
                val events = mutableListOf<AuditEvent>()
                while (rs.next()) {
                    events.add(mapEventRow(rs))
                }
                return events
            }
        }
    }

    private fun readEventBySequence(
        conn: Connection,
        auditStreamId: String,
        sequenceNumber: Long,
    ): AuditEvent? {
        val sql = """
            SELECT stream_id, sequence_number, event_id, event_type, event_hash,
                   previous_event_hash, occurred_at, sanitized_actor,
                   encrypted_payload, encryption_key_id, encryption_algorithm,
                   encryption_nonce, payload_digest, schema_version
            FROM audit_events
            WHERE stream_id = ? AND sequence_number = ?
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, auditStreamId)
            stmt.setLong(2, sequenceNumber)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                return mapEventRow(rs)
            }
        }
    }

    private fun readLatestEvent(conn: Connection, auditStreamId: String): AuditEvent? {
        val sql = """
            SELECT stream_id, sequence_number, event_id, event_type, event_hash,
                   previous_event_hash, occurred_at, sanitized_actor,
                   encrypted_payload, encryption_key_id, encryption_algorithm,
                   encryption_nonce, payload_digest, schema_version
            FROM audit_events
            WHERE stream_id = ?
            ORDER BY sequence_number DESC
            LIMIT 1
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, auditStreamId)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                return mapEventRow(rs)
            }
        }
    }

    private fun mapEventRow(rs: ResultSet): AuditEvent {
        val encrypted = JdbcEncryptedAuditPayload(
            ciphertext = rs.getBytes("encrypted_payload")
                ?: throw IllegalStateException("audit-event-corrupted: encrypted_payload is null"),
            keyId = rs.getString("encryption_key_id")
                ?: throw IllegalStateException("audit-event-corrupted: encryption_key_id is null"),
            algorithm = rs.getString("encryption_algorithm")
                ?: throw IllegalStateException("audit-event-corrupted: encryption_algorithm is null"),
            nonce = rs.getBytes("encryption_nonce")
                ?: throw IllegalStateException("audit-event-corrupted: encryption_nonce is null"),
            payloadDigest = rs.getString("payload_digest")
                ?: throw IllegalStateException("audit-event-corrupted: payload_digest is null"),
        )
        val plaintext = try {
            payloadCodec.decode(encrypted)
        } catch (e: Exception) {
            throw IllegalStateException("audit-event-decryption-failed", e)
        }
        val persisted: PersistedAuditEventV1 = try {
            mapper.readValue(plaintext)
        } catch (e: Exception) {
            throw IllegalStateException("audit-event-deserialization-failed", e)
        }
        val domainEvent = persisted.toDomain()
        require(domainEvent.auditStreamId == rs.getString("stream_id")) {
            "audit-stream-id-mismatch"
        }
        require(domainEvent.sequenceNumber == rs.getLong("sequence_number")) {
            "audit-event-sequence-mismatch"
        }
        require(domainEvent.eventId == rs.getString("event_id")) {
            "audit-event-id-mismatch"
        }
        require(domainEvent.eventHash == rs.getString("event_hash")) {
            "audit-event-hash-column-mismatch"
        }
        require(domainEvent.previousEventHash == rs.getString("previous_event_hash")) {
            "audit-previous-event-hash-column-mismatch"
        }
        require(domainEvent.schemaVersion.toString() == rs.getString("schema_version")) {
            "audit-schema-version-column-mismatch"
        }
        require(domainEvent.decision == rs.getString("event_type")) {
            "audit-event-type-column-mismatch"
        }
        return domainEvent
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun immutableCopy(event: AuditEvent): AuditEvent =
        event.copy(metadata = java.util.Collections.unmodifiableMap(java.util.LinkedHashMap(event.metadata)))

    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        return "sha256:${hashBytes.joinToString("") { "%02x".format(it) }}"
    }
}
