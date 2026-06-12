package dev.tramai.persistence.file

import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore
import dev.tramai.security.audit.CURRENT_AUDIT_SCHEMA_VERSION
import dev.tramai.security.audit.calculateHash
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
 * - Audit events are **immutable**: once written, the file is never replaced (create-only)
 *
 * ## Security guarantees
 * - Stream directory binding: directory name must match SHA-256("audit-stream:" + auditStreamId)
 * - Event file binding: event digest in filename must match SHA-256("audit-event:" + eventId)
 * - AAD includes recordType, recordKeyDigest, **and keyId**
 * - Full chain validation before every append
 * - Duplicate event ID detection using consistent digest comparison
 * - Malformed committed filenames fail closed — evidence can never silently disappear
 * - Audit stream directories are validated as strict 0700 non-symlink directories
 *
 * ## Thread safety
 * Uses per-stream [ReentrantLock] to serialize append operations within each stream.
 * Read operations do not acquire locks (immutable files are safe to read concurrently).
 * All public methods are guarded by [FileStoreLease.withOpenOperation].
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

    /**
     * Creates or validates the stream directory.
     * Must be a non-symlink directory with 0700 permissions.
     */
    private fun ensureStreamDir(auditStreamId: String): Path {
        val dir = streamDir(auditStreamId)
        if (dir.notExists()) {
            // Ensure the audit root directory exists first
            if (auditDir.notExists()) {
                Files.createDirectories(auditDir, PosixFilePermissions.asFileAttribute(FILE_PERMS_0600))
                Files.setPosixFilePermissions(auditDir, DIR_PERMS_0700)
            }
            FileStoreUtil.createStrictDirectory(dir, "audit-stream")
        }
        FileStoreUtil.validateManagedDirectory(dir, "audit-stream")
        return dir
    }

    private fun eventFileName(sequenceNumber: Long, eventId: String): String {
        val paddedSeq = sequenceNumber.toString().padStart(SEQUENCE_PADDING, '0')
        return "$paddedSeq-${eventDigest(eventId)}$FILE_EXTENSION"
    }

    private fun eventFilePath(auditStreamId: String, sequenceNumber: Long, eventId: String): Path =
        streamDir(auditStreamId).resolve(eventFileName(sequenceNumber, eventId))

    // ── Filename parsing (fail-closed, no mapNotNull) ──

    private data class AuditFileEntry(
        val sequenceNumber: Long,
        val eventIdDigest: String,
        val path: Path,
    )

    /**
     * Scans a stream directory and returns parsed entries sorted by sequence.
     *
     * **Every committed entry must match the expected format.** Iterates ALL
     * directory entries (not just `*.tram.enc`), so renamed evidence without
     * the expected extension is detected and fails closed.
     */
    private fun scanStreamEntries(auditStreamId: String): List<AuditFileEntry> {
        val dir = streamDir(auditStreamId)
        if (dir.notExists() || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            // Path exists but is not a directory — fail closed
            if (dir.exists() && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw FileStoreCorruptionException("audit-stream-path-not-directory")
            }
            return emptyList()
        }

        FileStoreUtil.validateManagedDirectory(dir, "audit-stream")

        val regex = Regex("""^(\d{$SEQUENCE_PADDING})-([a-f0-9]{64})$FILE_EXTENSION$""")
        val entries = mutableListOf<AuditFileEntry>()
        val seenSequences = mutableSetOf<Long>()

        for (entry in dir.toFile().listFiles()!!) {
            if (entry.isDirectory) {
                throw FileStoreCorruptionException("audit-stream-unexpected-directory-entry")
            }
            val path = entry.toPath()
            val name = path.fileName.toString()
            // Any non-temp file that doesn't match the expected format is a corruption
            if (!name.startsWith(".")) {
                val match = regex.matchEntire(name)
                    ?: throw FileStoreCorruptionException("audit-event-invalid-filename")
                val (seqStr, digest) = match.destructured
                val seq = seqStr.toLong()
                if (!seenSequences.add(seq)) {
                    throw FileStoreCorruptionException("audit-duplicate-sequence")
                }
                FileStoreUtil.validateRegularFile(path, "audit-event")
                entries.add(AuditFileEntry(sequenceNumber = seq, eventIdDigest = digest, path = path))
            }
            // Temp files (. prefix) are silently ignored (orphan cleanup)
        }

        return entries.sortedBy { it.sequenceNumber }
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
        val dto = PersistedAuditEventV1.fromJson(plaintext.toString(Charsets.UTF_8))
        // Verify DTO schema version is at least 1 (domain-level validateEvent checks the exact value)
        require(dto.schemaVersion >= 1) { "unsupported-audit-schema-version" }
        return dto.toDomain()
    }

    // ── Validation helpers (safe reason codes only) ──

    private fun validateEvent(
        event: AuditEvent,
        expectedAuditStreamId: String,
        previousEvent: AuditEvent?,
        existingEventDigests: Set<String>,
    ) {
        require(event.auditStreamId == expectedAuditStreamId) { "audit-stream-id-mismatch" }
        require(event.schemaVersion == CURRENT_AUDIT_SCHEMA_VERSION) { "audit-schema-version-unsupported" }

        val expectedSequence = (previousEvent?.sequenceNumber ?: 0L) + 1L
        require(event.sequenceNumber == expectedSequence) { "audit-sequence-gap" }
        require(event.previousEventHash == previousEvent?.eventHash) { "audit-hash-chain-broken" }
        require(event.eventHash == event.calculateHash()) { "audit-event-hash-mismatch" }

        val eDigest = eventDigest(event.eventId)
        require(eDigest !in existingEventDigests) { "audit-duplicate-event-id" }
    }

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
    ): AuditEvent = lease.withOpenOperation {
        val sDigest = streamDigest(auditStreamId)
        val lock = streamLocks.computeIfAbsent(sDigest) { FileStoreUtil.perKeyLock() }
        lock.lock()
        try {
            // Validate and/or create stream directory
            ensureStreamDir(auditStreamId)

            // Scan all existing entries (fail-closed)
            val entries = scanStreamEntries(auditStreamId)

            // Decrypt all existing events and validate full chain before appending
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

            // Create-only: use atomicEncryptCreate, not atomicEncryptWrite
            val persisted = rawEvent.toPersistedV1()
            val persistedJson = persisted.toJson()
            val targetPath = eventFilePath(auditStreamId, rawEvent.sequenceNumber, rawEvent.eventId)

            FileStoreUtil.atomicEncryptCreate(
                targetPath = targetPath,
                recordType = RECORD_TYPE,
                recordKeyDigest = eventDigest(rawEvent.eventId),
                keyId = keyId,
                key = key,
                plaintextBytes = persistedJson.toByteArray(Charsets.UTF_8),
            )

            return@withOpenOperation rawEvent
        } finally {
            lock.unlock()
        }
    }

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> = lease.withOpenOperation {
        val entries = scanStreamEntries(auditStreamId)
        if (entries.isEmpty()) return@withOpenOperation emptyList()

        val events = entries.map { readAuditEventFromEntry(it) }
        validateChain(auditStreamId, events)

        return@withOpenOperation events.map {
            it.copy(metadata = java.util.Collections.unmodifiableMap(java.util.LinkedHashMap(it.metadata)))
        }
    }

    override suspend fun latestEvent(auditStreamId: String): AuditEvent? = lease.withOpenOperation {
        val entries = scanStreamEntries(auditStreamId)
        if (entries.isEmpty()) return@withOpenOperation null

        val events = entries.map { readAuditEventFromEntry(it) }
        validateChain(auditStreamId, events)

        return@withOpenOperation events.last()
    }

    // ── Verification ──

    /**
     * Verifies all existing audit records across all stream directories.
     * Fail-closed on any malformed file or directory.
     *
     * Scans ALL entries under the audit root — unexpected files, renamed
     * events, symlinks, and non-directory paths at the stream level are
     * all rejected.
     */
    fun verifyAll() = lease.withOpenOperation {
        if (auditDir.notExists() || !auditDir.isDirectory()) return@withOpenOperation

        // Validate all entries directly under audit/
        for (entry in auditDir.toFile().listFiles()!!) {
            val path = entry.toPath()
            if (!entry.isDirectory) {
                throw FileStoreCorruptionException("audit-unexpected-root-entry")
            }
            val streamDirName = path.fileName.toString()

            // Reject malformed stream directory names
            require(streamDirName.length == 64 && streamDirName.all { it in '0'..'9' || it in 'a'..'f' }) {
                throw FileStoreCorruptionException("audit-invalid-stream-directory")
            }

            FileStoreUtil.validateManagedDirectory(path, "audit-stream")

            val regex = Regex("""^(\d{$SEQUENCE_PADDING})-([a-f0-9]{64})$FILE_EXTENSION$""")
            val entries = mutableListOf<AuditFileEntry>()
            val seenSequences = mutableSetOf<Long>()

            for (f in path.toFile().listFiles()!!) {
                if (f.isDirectory) {
                    throw FileStoreCorruptionException("audit-stream-unexpected-directory-entry")
                }
                val filePath = f.toPath()
                val name = filePath.fileName.toString()
                if (!name.startsWith(".")) {
                    val match = regex.matchEntire(name)
                        ?: throw FileStoreCorruptionException("audit-event-invalid-filename")
                    val (seqStr, digest) = match.destructured
                    val seq = seqStr.toLong()
                    if (!seenSequences.add(seq)) {
                        throw FileStoreCorruptionException("audit-duplicate-sequence")
                    }
                    FileStoreUtil.validateRegularFile(filePath, "audit-event")
                    entries.add(AuditFileEntry(sequenceNumber = seq, eventIdDigest = digest, path = filePath))
                }
            }

            if (entries.isEmpty()) continue

            val events = entries.map { readAuditEventFromEntry(it) }

            // Directory binding
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

            validateChain(firstStreamId, events)
        }
    }
}
