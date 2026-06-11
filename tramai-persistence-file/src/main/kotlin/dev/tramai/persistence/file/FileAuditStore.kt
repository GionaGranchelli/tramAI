package dev.tramai.persistence.file

import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.CURRENT_AUDIT_SCHEMA_VERSION
import dev.tramai.security.audit.calculateHash
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.notExists

/**
 * File-backed [AuditStore] with per-event encrypted files, hash chain validation,
 * and stream ordering.
 *
 * ## On-disk layout
 * ```
 * {root}/audit/<sha256-stream-id>/
 *   <00000000000000000001>-<sha256-event-id>.tram.enc
 *   <00000000000000000002>-<sha256-event-id>.tram.enc
 *   ...
 * ```
 *
 * - `sha256-stream-id` = `SHA-256("audit-stream:" + auditStreamId)`
 * - Filename format: `<20-digit-zero-padded-sequence>-<sha256-event-id>.tram.enc`
 * - `sha256-event-id` = `SHA-256("audit-event:" + eventId)` (also used as record key digest)
 * - Record type constant: `"audit-event"`
 *
 * ## Thread safety
 * Uses per-stream [ReentrantLock] to serialize append operations within each stream.
 * Read operations do not acquire locks (immutable files are safe to read concurrently).
 *
 * ## Chain integrity
 * Every append and read validates:
 * - Sequence continuity (increments by exactly 1)
 * - Hash chain (previousEventHash matches previous event's eventHash)
 * - Event hash integrity (eventHash matches recalculated hash)
 * - Schema version support
 * - Unique eventId within stream
 * - auditStreamId consistency
 */
class FileAuditStore internal constructor(
    private val root: Path,
    private val key: SecretKey,
    private val configuration: FileBackedStoreConfiguration,
) : AuditStore {

    private val auditDir: Path = root.resolve("audit")

    /** Per-stream locks for serialized appends. */
    private val streamLocks = ConcurrentHashMap<String, ReentrantLock>()

    companion object {
        private const val RECORD_TYPE = "audit-event"
        private const val STREAM_PREFIX = "audit-stream:"
        private const val EVENT_PREFIX = "audit-event:"
        private const val FILE_EXTENSION = ".tram.enc"
        private const val SEQUENCE_PADDING = 20

        /** SHA-256 hex of the stream identifier. */
        private fun streamDigest(auditStreamId: String): String =
            FileStoreUtil.sha256Hex("$STREAM_PREFIX$auditStreamId")

        /** SHA-256 hex of the event identifier (also used as record key digest). */
        private fun eventDigest(eventId: String): String =
            FileStoreUtil.sha256Hex("$EVENT_PREFIX$eventId")
    }

    // ── Stream directory helpers ──

    private fun streamDir(auditStreamId: String): Path =
        auditDir.resolve(streamDigest(auditStreamId))

    private fun eventFileName(sequenceNumber: Long, eventId: String): String {
        val paddedSeq = sequenceNumber.toString().padStart(SEQUENCE_PADDING, '0')
        return "$paddedSeq-${eventDigest(eventId)}$FILE_EXTENSION"
    }

    private fun eventFilePath(auditStreamId: String, sequenceNumber: Long, eventId: String): Path =
        streamDir(auditStreamId).resolve(eventFileName(sequenceNumber, eventId))

    // ── Filename parsing ──

    /**
     * Represents a parsed audit file entry.
     */
    private data class AuditFileEntry(
        val sequenceNumber: Long,
        val eventIdDigest: String,
        val path: Path,
    )

    /**
     * Scans the stream directory and returns parsed entries sorted by sequence number.
     */
    private fun scanStreamEntries(auditStreamId: String): List<AuditFileEntry> {
        val dir = streamDir(auditStreamId)
        if (dir.notExists() || !dir.isDirectory()) return emptyList()

        val regex = Regex("""^(\d{$SEQUENCE_PADDING})-([a-f0-9]{64})$FILE_EXTENSION$""")
        return dir.listDirectoryEntries("*$FILE_EXTENSION")
            .mapNotNull { file ->
                val name = file.fileName.toString()
                regex.matchEntire(name)?.destructured?.let { (seq, digest) ->
                    AuditFileEntry(
                        sequenceNumber = seq.toLong(),
                        eventIdDigest = digest,
                        path = file,
                    )
                }
            }
            .sortedBy { it.sequenceNumber }
    }

    // ── Read / decrypt helpers ──

    /**
     * Reads, decrypts, and parses an audit event from its file.
     * Validates the record type and record key digest.
     */
    private fun readAuditEvent(filePath: Path, auditStreamId: String, eventId: String): AuditEvent {
        val expectedDigest = eventDigest(eventId)
        val plaintext = FileStoreUtil.readAndDecrypt(
            path = filePath,
            recordType = RECORD_TYPE,
            expectedRecordKeyDigest = expectedDigest,
            key = key,
        )
        val json = plaintext.toString(Charsets.UTF_8)
        val persisted = PersistedAuditEventV1.fromJson(json)
        val event = persisted.toDomain()
        return event
    }

    /**
     * Reads, decrypts, and parses an audit event from its file using the
     * event ID digest stored in the filename.
     */
    private fun readAuditEventFromEntry(entry: AuditFileEntry): AuditEvent {
        val json = FileStoreUtil.readAndDecrypt(
            path = entry.path,
            recordType = RECORD_TYPE,
            expectedRecordKeyDigest = entry.eventIdDigest,
            key = key,
        )
        return PersistedAuditEventV1.fromJson(json.toString(Charsets.UTF_8)).toDomain()
    }

    // ── Validation helpers ──

    /**
     * Validates an event against the chain invariants.
     *
     * @throws IllegalArgumentException on any validation failure.
     */
    private fun validateEvent(
        event: AuditEvent,
        auditStreamId: String,
        previousEvent: AuditEvent?,
        allEventIds: Set<String>,
    ) {
        require(event.auditStreamId == auditStreamId) {
            "Event auditStreamId '${event.auditStreamId}' does not match expected '$auditStreamId'"
        }

        require(event.schemaVersion == CURRENT_AUDIT_SCHEMA_VERSION) {
            "Unsupported audit schema version ${event.schemaVersion}, expected $CURRENT_AUDIT_SCHEMA_VERSION"
        }

        val expectedSequence = (previousEvent?.sequenceNumber ?: 0L) + 1L
        require(event.sequenceNumber == expectedSequence) {
            "Expected sequenceNumber $expectedSequence for stream '$auditStreamId' but got ${event.sequenceNumber}"
        }

        require(event.previousEventHash == previousEvent?.eventHash) {
            "previousEventHash does not match previous event's eventHash"
        }

        require(event.eventHash == event.calculateHash()) {
            "eventHash does not match recalculated hash"
        }

        require(event.eventId !in allEventIds) {
            "Duplicate eventId '${event.eventId}' in stream '$auditStreamId'"
        }
    }

    /**
     * Validates the full chain integrity of a stream's events.
     *
     * @throws IllegalArgumentException on any validation failure.
     */
    private fun validateChain(events: List<AuditEvent>) {
        val seenEventIds = mutableSetOf<String>()
        var previousEvent: AuditEvent? = null

        for (event in events) {
            validateEvent(event, events.first().auditStreamId, previousEvent, seenEventIds)
            seenEventIds.add(event.eventId)
            previousEvent = event
        }
    }

    // ── AuditStore SPI ──

    override suspend fun appendNext(
        auditStreamId: String,
        eventFactory: (latest: AuditEvent?) -> AuditEvent,
    ): AuditEvent {
        val streamDigest = streamDigest(auditStreamId)
        val lock = streamLocks.computeIfAbsent(streamDigest) { FileStoreUtil.perKeyLock() }
        lock.lock()
        try {
            // Ensure stream directory exists
            val dir = streamDir(auditStreamId)
            if (dir.notExists()) {
                Files.createDirectories(dir)
            }

            // Find the latest event
            val entries = scanStreamEntries(auditStreamId)
            val latestEntry = entries.lastOrNull()
            val latestEvent = if (latestEntry != null) {
                readAuditEventFromEntry(latestEntry)
            } else {
                null
            }

            // Call the factory to get the new event
            val rawEvent = eventFactory(latestEvent)
            val allEventIds = entries.mapTo(mutableSetOf()) { it.eventIdDigest }

            // Validate
            validateEvent(rawEvent, auditStreamId, latestEvent, allEventIds)

            // Convert to persisted DTO, serialize, encrypt, write
            val persisted = rawEvent.toPersistedV1()
            val persistedJson = persisted.toJson()
            val targetPath = eventFilePath(auditStreamId, rawEvent.sequenceNumber, rawEvent.eventId)

            FileStoreUtil.atomicEncryptWrite(
                targetPath = targetPath,
                recordType = RECORD_TYPE,
                recordKeyDigest = eventDigest(rawEvent.eventId),
                keyId = configuration.encryption.activeKeyId,
                key = key,
                plaintextBytes = persistedJson.toByteArray(Charsets.UTF_8),
            )

            return rawEvent
        } finally {
            lock.unlock()
        }
    }

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> {
        val entries = scanStreamEntries(auditStreamId)
        if (entries.isEmpty()) return emptyList()

        val events = entries.map { readAuditEventFromEntry(it) }

        // Validate full chain integrity
        validateChain(events)

        // Return defensive copies (immutable maps via copy)
        return events.map { it.copy(metadata = java.util.Collections.unmodifiableMap(java.util.LinkedHashMap(it.metadata))) }
    }

    override suspend fun latestEvent(auditStreamId: String): AuditEvent? {
        val entries = scanStreamEntries(auditStreamId)
        val latestEntry = entries.lastOrNull() ?: return null
        return readAuditEventFromEntry(latestEntry)
    }

    // ── Verification ──

    /**
     * Verifies all existing audit records across all stream directories.
     * Called during [FileBackedSovereignStores.open] when verifyOnOpen is true.
     *
     * @throws IllegalArgumentException if any stream fails chain validation.
     * @throws FileStoreCorruptionException if any file fails decryption or integrity check.
     */
    fun verifyAll() {
        if (auditDir.notExists() || !auditDir.isDirectory()) return

        val streamDirs = auditDir.listDirectoryEntries()
            .filter { it.isDirectory() }

        for (dir in streamDirs) {
            val regex = Regex("""^(\d{$SEQUENCE_PADDING})-([a-f0-9]{64})$FILE_EXTENSION$""")
            val entries = dir.listDirectoryEntries("*$FILE_EXTENSION")
                .mapNotNull { file ->
                    val name = file.fileName.toString()
                    regex.matchEntire(name)?.destructured?.let { (seq, digest) ->
                        AuditFileEntry(seq.toLong(), digest, file)
                    }
                }
                .sortedBy { it.sequenceNumber }

            if (entries.isEmpty()) continue

            val events = entries.map { readAuditEventFromEntry(it) }
            validateChain(events)
        }
    }
}
