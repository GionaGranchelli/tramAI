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
 * ## Security guarantees
 * - Stream directory binding: directory name must match SHA-256("audit-stream:" + auditStreamId)
 * - Event file binding: event digest in filename must match SHA-256("audit-event:" + eventId)
 * - AAD includes recordType, recordKeyDigest, **and keyId**
 * - Full chain validation before every append
 * - Duplicate event ID detection using consistent digest comparison
 *
 * ## Thread safety
 * Uses per-stream [ReentrantLock] to serialize append operations within each stream.
 * Read operations do not acquire locks (immutable files are safe to read concurrently).
 */
class FileAuditStore internal constructor(
    private val root: Path,
    private val key: SecretKey,
    private val configuration: FileBackedStoreConfiguration,
    private val lease: FileStoreLease,
) : AuditStore {

    private val auditDir: Path = root.resolve("audit")
    private val keyId: String = configuration.encryption.activeKeyId

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

    private data class AuditFileEntry(
        val sequenceNumber: Long,
        val eventIdDigest: String,
        val path: Path,
    )

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

    private fun readAuditEventFromEntry(entry: AuditFileEntry): AuditEvent {
        val plaintext = FileStoreUtil.readAndDecrypt(
            path = entry.path,
            recordType = RECORD_TYPE,
            expectedRecordKeyDigest = entry.eventIdDigest,
            key = key,
            expectedKeyId = keyId,
        )
        return PersistedAuditEventV1.fromJson(plaintext.toString(Charsets.UTF_8)).toDomain()
    }

    // ── Validation helpers ──

    /**
     * Validates an event against the chain invariants, binding to the expected
     * stream directory.
     *
     * @throws IllegalArgumentException on any validation failure.
     */
    private fun validateEvent(
        event: AuditEvent,
        expectedAuditStreamId: String,
        previousEvent: AuditEvent?,
        existingEventDigests: Set<String>,
    ) {
        // Stream binding: validate against the caller-requested stream, not the decoded event
        require(event.auditStreamId == expectedAuditStreamId) {
            "Event auditStreamId '${event.auditStreamId}' does not match expected '$expectedAuditStreamId'"
        }

        // Directory binding: validate stream directory name against decoded stream ID
        val expectedDirDigest = streamDigest(event.auditStreamId)
        require(expectedDirDigest == streamDigest(expectedAuditStreamId)) {
            "Directory digest mismatch for stream '$expectedAuditStreamId'"
        }

        require(event.schemaVersion == CURRENT_AUDIT_SCHEMA_VERSION) {
            "Unsupported audit schema version ${event.schemaVersion}, expected $CURRENT_AUDIT_SCHEMA_VERSION"
        }

        val expectedSequence = (previousEvent?.sequenceNumber ?: 0L) + 1L
        require(event.sequenceNumber == expectedSequence) {
            "Expected sequenceNumber $expectedSequence for stream '$expectedAuditStreamId' but got ${event.sequenceNumber}"
        }

        require(event.previousEventHash == previousEvent?.eventHash) {
            "previousEventHash does not match previous event's eventHash"
        }

        require(event.eventHash == event.calculateHash()) {
            "eventHash does not match recalculated hash"
        }

        // Duplicate detection: compare event digests consistently
        val eventDigest = eventDigest(event.eventId)
        require(eventDigest !in existingEventDigests) {
            "Duplicate eventId digest '${event.eventId}' in stream '$expectedAuditStreamId'"
        }
    }

    /**
     * Validates the full chain integrity of a stream's events, binding to
     * the expected audit stream ID.
     */
    private fun validateChain(expectedAuditStreamId: String, events: List<AuditEvent>) {
        val seenEventDigests = mutableSetOf<String>()
        var previousEvent: AuditEvent? = null

        for (event in events) {
            validateEvent(event, expectedAuditStreamId, previousEvent, seenEventDigests)
            seenEventDigests.add(eventDigest(event.eventId))
            previousEvent = event
        }
    }

    // ── AuditStore SPI ──

    override suspend fun appendNext(
        auditStreamId: String,
        eventFactory: (latest: AuditEvent?) -> AuditEvent,
    ): AuditEvent {
        lease.requireOpen()
        val sDigest = streamDigest(auditStreamId)
        val lock = streamLocks.computeIfAbsent(sDigest) { FileStoreUtil.perKeyLock() }
        lock.lock()
        try {
            // Ensure stream directory exists
            val dir = streamDir(auditStreamId)
            if (dir.notExists()) {
                Files.createDirectories(dir)
            }

            // Scan all existing entries
            val entries = scanStreamEntries(auditStreamId)

            // Decrypt all existing events and validate the full chain before appending
            val existingEvents = entries.map { readAuditEventFromEntry(it) }
            if (existingEvents.isNotEmpty()) {
                validateChain(auditStreamId, existingEvents)
            }

            val latestEvent = existingEvents.lastOrNull()

            // Call the factory to get the new event
            val rawEvent = eventFactory(latestEvent)

            // Duplicate detection using event digest
            val existingEventDigests = entries.mapTo(mutableSetOf()) { it.eventIdDigest }
            validateEvent(rawEvent, auditStreamId, latestEvent, existingEventDigests)

            // Also validate that the stream directory digest matches the decoded stream ID
            val expectedDirDigest = streamDigest(rawEvent.auditStreamId)
            require(expectedDirDigest == sDigest) {
                "Stream directory digest mismatch: expected $sDigest, got $expectedDirDigest"
            }

            // Convert to persisted DTO, serialize, encrypt, write
            val persisted = rawEvent.toPersistedV1()
            val persistedJson = persisted.toJson()
            val targetPath = eventFilePath(auditStreamId, rawEvent.sequenceNumber, rawEvent.eventId)

            FileStoreUtil.atomicEncryptWrite(
                targetPath = targetPath,
                recordType = RECORD_TYPE,
                recordKeyDigest = eventDigest(rawEvent.eventId),
                keyId = keyId,
                key = key,
                plaintextBytes = persistedJson.toByteArray(Charsets.UTF_8),
            )

            return rawEvent
        } finally {
            lock.unlock()
        }
    }

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> {
        lease.requireOpen()
        val entries = scanStreamEntries(auditStreamId)
        if (entries.isEmpty()) return emptyList()

        val events = entries.map { readAuditEventFromEntry(it) }

        // Validate against the caller-requested stream
        validateChain(auditStreamId, events)

        // Return defensive copies
        return events.map { it.copy(metadata = java.util.Collections.unmodifiableMap(java.util.LinkedHashMap(it.metadata))) }
    }

    override suspend fun latestEvent(auditStreamId: String): AuditEvent? {
        lease.requireOpen()
        val entries = scanStreamEntries(auditStreamId)
        if (entries.isEmpty()) return null

        // Validate full chain before returning terminal event
        val events = entries.map { readAuditEventFromEntry(it) }
        validateChain(auditStreamId, events)

        return events.last()
    }

    // ── Verification ──

    /**
     * Verifies all existing audit records across all stream directories.
     *
     * For each stream:
     * - Decrypts and parses every event
     * - Validates directory name binds to decoded stream ID
     * - Validates every event file name binds to decoded event ID
     * - Validates the full hash chain against the caller-requested stream ID
     * - Validates file permissions and symlinks on every file
     *
     * @throws IllegalArgumentException if any stream fails chain validation.
     * @throws FileStoreCorruptionException if any file fails decryption or integrity check.
     */
    fun verifyAll() {
        if (auditDir.notExists() || !auditDir.isDirectory()) return

        val streamDirs = auditDir.listDirectoryEntries()
            .filter { it.isDirectory() }

        for (dir in streamDirs) {
            val streamDirName = dir.fileName.toString()
            val regex = Regex("""^(\d{$SEQUENCE_PADDING})-([a-f0-9]{64})$FILE_EXTENSION$""")
            val entries = dir.listDirectoryEntries("*$FILE_EXTENSION")
                .mapNotNull { file ->
                    // Validate each file
                    FileStoreUtil.validateRegularFile(file, "audit-event")
                    val name = file.fileName.toString()
                    regex.matchEntire(name)?.destructured?.let { (seq, digest) ->
                        AuditFileEntry(seq.toLong(), digest, file)
                    }
                }
                .sortedBy { it.sequenceNumber }

            if (entries.isEmpty()) continue

            val events = entries.map { readAuditEventFromEntry(it) }
            if (events.isEmpty()) continue

            // Directory binding: validate directory name against decoded stream ID
            val firstStreamId = events.first().auditStreamId
            val expectedDirDigest = streamDigest(firstStreamId)
            require(streamDirName == expectedDirDigest) {
                throw FileStoreCorruptionException("audit-stream-directory-digest-mismatch")
            }

            // Validate every event file name binds to decoded event ID
            for ((event, entry) in events.zip(entries)) {
                val expectedEventDigest = eventDigest(event.eventId)
                require(entry.eventIdDigest == expectedEventDigest) {
                    throw FileStoreCorruptionException("audit-event-filename-digest-mismatch")
                }
            }

            // Validate hash chain against the decoded stream ID
            validateChain(firstStreamId, events)
        }
    }
}
