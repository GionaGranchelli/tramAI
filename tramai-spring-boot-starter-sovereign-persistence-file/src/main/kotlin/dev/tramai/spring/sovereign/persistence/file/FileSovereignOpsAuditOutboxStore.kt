package dev.tramai.spring.sovereign.persistence.file

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.tramai.persistence.file.AesGcmFileEncryption
import dev.tramai.persistence.file.EncryptedFileEnvelopeV1
import dev.tramai.persistence.file.FileStoreCorruptionException
import dev.tramai.persistence.file.FileStorePermissionException
import dev.tramai.persistence.file.FileStoreSha256
import dev.tramai.persistence.file.FileStoreUnsupportedFormatException
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.SecretKey

private val OPS_OUTBOX_JSON: ObjectMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .serializationInclusion(JsonInclude.Include.NON_ABSENT)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
    .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
    .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, false)
    .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
    .build()

private const val MAX_JSON_SIZE = 10_485_760

private inline fun <reified T : Any> strictOpsOutboxReadValue(json: String): T {
    require(json.length <= MAX_JSON_SIZE) { "json-payload-too-large" }
    return try {
        OPS_OUTBOX_JSON.readValue(json.trim())
    } catch (_: Exception) {
        throw IllegalArgumentException("json-deserialisation-failed")
    }
}

/**
 * Persistable DTO for [SovereignOpsAuditOutboxRecord].
 * Timestamps are stored as ISO-8601 strings.
 */
internal data class PersistedSovereignOpsAuditOutboxRecordV1(
    @get:JsonProperty("schemaVersion") val schemaVersion: Int = 1,
    @JsonProperty("outboxId") val outboxId: String,
    @JsonProperty("aggregateType") val aggregateType: String,
    @JsonProperty("aggregateIdDigest") val aggregateIdDigest: String,
    @JsonProperty("operation") val operation: String,
    @JsonProperty("eventKey") val eventKey: String,
    @JsonProperty("actor") val actor: String,
    @JsonProperty("workflowRunId") val workflowRunId: String?,
    @JsonProperty("correlationId") val correlationId: String?,
    @JsonProperty("approvalStatus") val approvalStatus: String,
    @JsonProperty("approvalVersion") val approvalVersion: Long?,
    @JsonProperty("reasonDigest") val reasonDigest: String,
    @JsonProperty("reasonLength") val reasonLength: Int,
    @JsonProperty("status") val status: String,
    @JsonProperty("attemptCount") val attemptCount: Int,
    @JsonProperty("lastErrorCode") val lastErrorCode: String?,
    @JsonProperty("claimedBy") val claimedBy: String?,
    @JsonProperty("claimedAt") val claimedAt: String?,
    @JsonProperty("claimExpiresAt") val claimExpiresAt: String?,
    @JsonProperty("createdAt") val createdAt: String,
    @JsonProperty("emittedAt") val emittedAt: String?,
    @JsonProperty("outboxRecordVersion") val outboxRecordVersion: Long,
) {
    fun toJson(): String = OPS_OUTBOX_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedSovereignOpsAuditOutboxRecordV1 =
            strictOpsOutboxReadValue(json)
    }
}

internal fun SovereignOpsAuditOutboxRecord.toPersistedV1(
    outboxRecordVersion: Long,
): PersistedSovereignOpsAuditOutboxRecordV1 = PersistedSovereignOpsAuditOutboxRecordV1(
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
    outboxRecordVersion = outboxRecordVersion,
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
        createdAt = Instant.parse(createdAt),
        status = SovereignOpsAuditOutboxStatus.valueOf(status),
        attemptCount = attemptCount,
        lastErrorCode = lastErrorCode,
        claimedBy = claimedBy,
        claimedAt = claimedAt?.let { Instant.parse(it) },
        claimExpiresAt = claimExpiresAt?.let { Instant.parse(it) },
        emittedAt = emittedAt?.let { Instant.parse(it) },
    )
}

/**
 * File-backed [SovereignOpsAuditOutboxStore] using encrypted atomic file persistence.
 *
 * Each outbox record is stored as one encrypted file under
 * `{root}/ops-audit-outbox/<sha256("ops-audit-outbox:<outboxId>)>.tram.enc`.
 * The event-key index is rebuilt from committed files on construction.
 */
class FileSovereignOpsAuditOutboxStore internal constructor(
    root: Path,
    key: SecretKey,
    private val lease: SovereignOpsOutboxFileStoreLease = SovereignOpsOutboxFileStoreLease(),
    private val claimLeaseDuration: Duration = SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY,
) : SovereignOpsAuditOutboxStore,
    AutoCloseable {

    companion object {
        private const val RECORD_TYPE = STORAGE_NAME
        private const val OUTBOX_DIR = STORAGE_NAME
        private const val FILE_EXTENSION = ".tram.enc"
        private const val KEY_ID = "default"
        private val COMMITTED_FILENAME = Regex("[a-f0-9]{64}\\.tram\\.enc")
    }

    private val rootDir: Path = root.toAbsolutePath().normalize()
    private val outboxDir: Path = rootDir.resolve(OUTBOX_DIR)
    private val encryptionKey: SecretKey = key
    private val recordLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val appendLock = ReentrantLock()
    private val eventKeyIndex = ConcurrentHashMap<String, String>()

    init {
        ensureManagedDirectory(outboxDir, STORAGE_NAME)
        rebuildIndex()
    }

    private fun storePath(outboxId: String): Path =
        outboxDir.resolve("${recordKeyDigest(outboxId)}$FILE_EXTENSION")

    private fun recordKeyDigest(outboxId: String): String =
        FileStoreSha256.digest(RECORD_TYPE, outboxId)

    private fun getLockForDigest(digest: String): ReentrantLock =
        recordLocks.computeIfAbsent(digest) { ReentrantLock() }

    private fun getLockForOutboxId(outboxId: String): ReentrantLock =
        getLockForDigest(recordKeyDigest(outboxId))

    fun rebuildIndex() = lease.withOpenOperation {
        eventKeyIndex.clear()
        if (!Files.exists(outboxDir, LinkOption.NOFOLLOW_LINKS)) return@withOpenOperation
        validateManagedDirectory(outboxDir, STORAGE_NAME)
        for (entry in committedEntries()) {
            val digest = digestFromPath(entry)
            validatePersistedDto(readCurrentByDigest(entry, digest), digest)
                .also { eventKeyIndex[it.eventKey] = it.outboxId }
        }
    }

    override fun isDurable(): Boolean = true

    override suspend fun append(
        record: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsAuditOutboxRecord = lease.withOpenOperation {
        validateManagedDirectory(outboxDir, STORAGE_NAME)
        require(record.outboxId.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-id" }
        require(record.eventKey.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-event-key" }
        require(record.status == SovereignOpsAuditOutboxStatus.PREPARED) {
            "tramai-sovereign-ops-outbox-invalid-status"
        }

        appendLock.lock()
        try {
            require(!Files.exists(storePath(record.outboxId), LinkOption.NOFOLLOW_LINKS)) {
                "tramai-sovereign-ops-outbox-duplicate-id"
            }
            require(!eventKeyIndex.containsKey(record.eventKey)) {
                "tramai-sovereign-ops-outbox-duplicate-event-key"
            }

            createAtomically(record.toPersistedV1(outboxRecordVersion = 0L))
            eventKeyIndex[record.eventKey] = record.outboxId
            record
        } finally {
            appendLock.unlock()
        }
    }

    override suspend fun markReadyForDispatch(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord = mutate(outboxId) { dto ->
        require(expectedStatus == SovereignOpsAuditOutboxStatus.PREPARED) {
            ERROR_OUTBOX_STATUS_MISMATCH
        }
        require(dto.status == expectedStatus.name) {
            ERROR_OUTBOX_STATUS_MISMATCH
        }
        dto.toDomain().copy(status = SovereignOpsAuditOutboxStatus.PENDING)
    }

    override suspend fun claimPending(
        claimedBy: String,
        limit: Int,
        now: Instant,
    ): List<SovereignOpsAuditOutboxRecord> = lease.withOpenOperation {
        if (limit <= 0) return@withOpenOperation emptyList()
        validateManagedDirectory(outboxDir, STORAGE_NAME)

        val claimed = mutableListOf<SovereignOpsAuditOutboxRecord>()
        for (entry in committedEntries()) {
            if (claimed.size >= limit) break
            val digest = digestFromPath(entry)
            val lock = getLockForDigest(digest)
            lock.lock()
            try {
                val dto = validatePersistedDto(readCurrentByDigest(entry, digest), digest)
                val record = dto.toDomain()
                if (!record.isDispatchable(now)) continue

                val updated = record.copy(
                    status = SovereignOpsAuditOutboxStatus.EMITTING,
                    attemptCount = record.attemptCount + 1,
                    claimedBy = claimedBy,
                    claimedAt = now,
                    claimExpiresAt = now.plus(claimLeaseDuration),
                    lastErrorCode = null,
                )
                writeCurrent(record.outboxId, updated.toPersistedV1(nextVersion(dto)))
                claimed += updated
            } finally {
                lock.unlock()
            }
        }
        claimed
    }

    override suspend fun markEmitted(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        expectedAttemptCount: Int,
        emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord = mutate(outboxId) { dto ->
        require(expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING) {
            ERROR_OUTBOX_STATUS_MISMATCH
        }
        require(dto.status == expectedStatus.name) {
            ERROR_OUTBOX_STATUS_MISMATCH
        }
        val record = dto.toDomain()
        check(record.attemptCount == expectedAttemptCount) {
            ERROR_OUTBOX_CONCURRENT_UPDATE
        }
        record.copy(
            status = SovereignOpsAuditOutboxStatus.EMITTED,
            emittedAt = emittedAt,
        )
    }

    override suspend fun markFailed(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        expectedAttemptCount: Int,
        errorCode: String,
        retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord = mutate(outboxId) { dto ->
        if (retryable) {
            require(expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING) {
                ERROR_OUTBOX_STATUS_MISMATCH
            }
        } else {
            require(
                expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING ||
                    expectedStatus == SovereignOpsAuditOutboxStatus.PREPARED
            ) {
                ERROR_OUTBOX_STATUS_MISMATCH
            }
        }
        require(dto.status == expectedStatus.name) {
            ERROR_OUTBOX_STATUS_MISMATCH
        }
        val record = dto.toDomain()
        check(record.attemptCount == expectedAttemptCount) {
            ERROR_OUTBOX_CONCURRENT_UPDATE
        }
        val targetStatus = if (retryable) {
            SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE
        } else {
            SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
        }
        record.copy(
            status = targetStatus,
            lastErrorCode = errorCode,
        )
    }

    override suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord? =
        lease.withOpenOperation {
            val lock = getLockForOutboxId(outboxId)
            lock.lock()
            try {
                readCurrent(outboxId)?.toDomain()
            } finally {
                lock.unlock()
            }
        }

    override suspend fun findByEventKey(eventKey: String): SovereignOpsAuditOutboxRecord? =
        lease.withOpenOperation {
            val outboxId = eventKeyIndex[eventKey] ?: return@withOpenOperation null
            val lock = getLockForOutboxId(outboxId)
            lock.lock()
            try {
                readCurrent(outboxId)?.toDomain()
            } finally {
                lock.unlock()
            }
        }

    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> =
        listRecords(limit) { it.status == SovereignOpsAuditOutboxStatus.PENDING.name }

    override suspend fun listByStatus(
        status: SovereignOpsAuditOutboxStatus,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> =
        listRecords(limit) { it.status == status.name }

    override suspend fun listExpiredEmitting(
        now: Instant,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> =
        listRecords(limit) {
            it.status == SovereignOpsAuditOutboxStatus.EMITTING.name &&
                it.claimExpiresAt?.let { claimExpiresAt -> Instant.parse(claimExpiresAt).isBefore(now) } == true
        }

    fun verifyAll() = lease.withOpenOperation {
        if (!Files.exists(outboxDir, LinkOption.NOFOLLOW_LINKS)) return@withOpenOperation
        validateManagedDirectory(outboxDir, STORAGE_NAME)
        for (entry in committedEntries()) {
            val digest = digestFromPath(entry)
            validatePersistedDto(readCurrentByDigest(entry, digest), digest).toDomain()
        }
    }

    override fun close() {
        lease.close()
    }

    private fun mutate(
        outboxId: String,
        update: (PersistedSovereignOpsAuditOutboxRecordV1) -> SovereignOpsAuditOutboxRecord,
    ): SovereignOpsAuditOutboxRecord = lease.withOpenOperation {
        validateManagedDirectory(outboxDir, STORAGE_NAME)
        val lock = getLockForOutboxId(outboxId)
        lock.lock()
        try {
            val dto = readCurrent(outboxId)
                ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")
            val updated = update(dto)
            writeCurrent(outboxId, updated.toPersistedV1(nextVersion(dto)))
            updated
        } finally {
            lock.unlock()
        }
    }

    private fun readCurrent(outboxId: String): PersistedSovereignOpsAuditOutboxRecordV1? {
        val path = storePath(outboxId)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val digest = recordKeyDigest(outboxId)
        return validatePersistedDto(readCurrentByDigest(path, digest), digest)
    }

    private fun readCurrentByDigest(
        path: Path,
        digest: String,
    ): PersistedSovereignOpsAuditOutboxRecordV1 {
        validateRegularFile(path, STORAGE_NAME)
        val plaintext = try {
            readAndDecrypt(path, digest)
        } catch (e: FileStoreCorruptionException) {
            throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
        } catch (e: Exception) {
            throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
        }
        return try {
            PersistedSovereignOpsAuditOutboxRecordV1.fromJson(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
        }
    }

    private fun validatePersistedDto(
        dto: PersistedSovereignOpsAuditOutboxRecordV1,
        digest: String,
    ): PersistedSovereignOpsAuditOutboxRecordV1 {
        if (dto.schemaVersion != 1) {
            throw FileStoreUnsupportedFormatException("unsupported-outbox-schema-version")
        }
        if (FileStoreSha256.digest(RECORD_TYPE, dto.outboxId) != digest) {
            throw FileStoreCorruptionException("ops-audit-outbox-id-filename-digest-mismatch")
        }
        try {
            dto.toDomain()
        } catch (e: Exception) {
            throw FileStoreCorruptionException("ops-audit-outbox-domain-conversion-failed", e)
        }
        return dto
    }

    private fun writeCurrent(
        outboxId: String,
        dto: PersistedSovereignOpsAuditOutboxRecordV1,
    ) {
        atomicEncryptWrite(
            targetPath = storePath(outboxId),
            recordKeyDigest = recordKeyDigest(outboxId),
            plaintextBytes = dto.toJson().toByteArray(Charsets.UTF_8),
            replaceExisting = true,
        )
    }

    private fun createAtomically(dto: PersistedSovereignOpsAuditOutboxRecordV1) {
        atomicEncryptWrite(
            targetPath = storePath(dto.outboxId),
            recordKeyDigest = recordKeyDigest(dto.outboxId),
            plaintextBytes = dto.toJson().toByteArray(Charsets.UTF_8),
            replaceExisting = false,
        )
    }

    private fun readAndDecrypt(path: Path, expectedRecordKeyDigest: String): ByteArray {
        val envelope = try {
            EncryptedFileEnvelopeV1.fromJson(boundedReadText(path))
        } catch (e: Exception) {
            throw FileStoreCorruptionException("ops-audit-outbox-envelope-corrupted", e)
        }
        return AesGcmFileEncryption.decrypt(
            key = encryptionKey,
            envelope = envelope,
            expectedRecordType = RECORD_TYPE,
            expectedRecordKeyDigest = expectedRecordKeyDigest,
            expectedKeyId = KEY_ID,
        )
    }

    private fun atomicEncryptWrite(
        targetPath: Path,
        recordKeyDigest: String,
        plaintextBytes: ByteArray,
        replaceExisting: Boolean,
    ) {
        val (nonceBase64, ciphertextBase64) = AesGcmFileEncryption.encrypt(
            key = encryptionKey,
            recordType = RECORD_TYPE,
            recordKeyDigest = recordKeyDigest,
            keyId = KEY_ID,
            plaintextBytes = plaintextBytes,
        )
        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = RECORD_TYPE,
            recordKeyDigest = recordKeyDigest,
            keyId = KEY_ID,
            nonceBase64 = nonceBase64,
            ciphertextBase64 = ciphertextBase64,
        )
        val bytes = envelope.toJson().toByteArray(Charsets.UTF_8)
        if (replaceExisting) {
            val temp = tempSibling(targetPath)
            try {
                writeFileWith0600(temp, bytes, createNew = true)
                Files.move(
                    temp,
                    targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                Files.setPosixFilePermissions(targetPath, FILE_PERMS_0600)
                forceParentDirectory(targetPath.parent)
            } finally {
                try {
                    Files.deleteIfExists(temp)
                } catch (_: Exception) {
                    // best effort cleanup
                }
            }
        } else {
            writeFileWith0600(targetPath, bytes, createNew = true)
            forceParentDirectory(targetPath.parent)
        }
    }

    private fun listRecords(
        limit: Int,
        predicate: (PersistedSovereignOpsAuditOutboxRecordV1) -> Boolean,
    ): List<SovereignOpsAuditOutboxRecord> = lease.withOpenOperation {
        if (limit <= 0) return@withOpenOperation emptyList()
        validateManagedDirectory(outboxDir, STORAGE_NAME)
        val results = mutableListOf<SovereignOpsAuditOutboxRecord>()
        for (entry in committedEntries()) {
            if (results.size >= limit) break
            val digest = digestFromPath(entry)
            val lock = getLockForDigest(digest)
            lock.lock()
            try {
                val dto = validatePersistedDto(readCurrentByDigest(entry, digest), digest)
                if (predicate(dto)) results += dto.toDomain()
            } finally {
                lock.unlock()
            }
        }
        results
    }

    private fun committedEntries(): List<Path> {
        val entries = mutableListOf<Path>()
        Files.newDirectoryStream(outboxDir).use { stream ->
            for (entry in stream) {
                val name = entry.fileName.toString()
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw FileStoreCorruptionException("ops-audit-outbox-unexpected-directory-entry")
                }
                if (!COMMITTED_FILENAME.matches(name)) {
                    throw FileStoreCorruptionException("ops-audit-outbox-unexpected-entry")
                }
                entries.add(entry)
            }
        }
        return entries.sortedBy { it.fileName.toString() }
    }

    private fun digestFromPath(path: Path): String {
        val digest = path.fileName.toString().removeSuffix(FILE_EXTENSION)
        if (digest.length != 64 || digest.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw FileStoreCorruptionException("ops-audit-outbox-invalid-filename")
        }
        return digest
    }

    private fun SovereignOpsAuditOutboxRecord.isDispatchable(now: Instant): Boolean {
        val expiresAt = claimExpiresAt
        return status == SovereignOpsAuditOutboxStatus.PENDING ||
            status == SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE ||
            (
                status == SovereignOpsAuditOutboxStatus.EMITTING &&
                    expiresAt != null &&
                    expiresAt.isBefore(now)
                )
    }

    private fun nextVersion(dto: PersistedSovereignOpsAuditOutboxRecordV1): Long =
        try {
            Math.addExact(dto.outboxRecordVersion, 1L)
        } catch (_: ArithmeticException) {
            throw IllegalStateException("tramai-sovereign-ops-outbox-version-overflow")
        }
}

internal class SovereignOpsOutboxFileStoreLease {
    private val lock = ReentrantReadWriteLock()
    private var closed = false

    inline fun <T> withOpenOperation(block: () -> T): T {
        lock.readLock().lock()
        try {
            check(!closed) { "file-store-closed" }
            return block()
        } finally {
            lock.readLock().unlock()
        }
    }

    fun close() {
        lock.writeLock().lock()
        try {
            closed = true
        } finally {
            lock.writeLock().unlock()
        }
    }
}

private val DIR_PERMS_0700: Set<PosixFilePermission> =
    PosixFilePermissions.fromString("rwx------")

private val FILE_PERMS_0600: Set<PosixFilePermission> =
    PosixFilePermissions.fromString("rw-------")

private val RANDOM = SecureRandom()

private fun ensureManagedDirectory(path: Path, description: String) {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        validateManagedDirectory(path, description)
        return
    }
    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIR_PERMS_0700))
    forceParentDirectory(path.parent)
}

private fun validateManagedDirectory(path: Path, description: String) {
    if (Files.isSymbolicLink(path)) throw FileStorePermissionException("$description-symlink-rejected")
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        throw FileStorePermissionException("$description-not-directory")
    }
    if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) != DIR_PERMS_0700) {
        throw FileStorePermissionException("$description-permission-denied")
    }
}

private fun validateRegularFile(path: Path, description: String) {
    if (Files.isSymbolicLink(path)) throw FileStorePermissionException("$description-symlink-rejected")
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        throw FileStorePermissionException("$description-not-regular-file")
    }
    if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) != FILE_PERMS_0600) {
        throw FileStorePermissionException("$description-permission-denied")
    }
}

private fun boundedReadText(path: Path): String {
    val size = Files.size(path)
    require(size <= MAX_JSON_SIZE) { "file-too-large" }
    return Files.readString(path)
}

private fun tempSibling(target: Path): Path {
    // Stage the temp file OUTSIDE the scanned outbox directory (in the root
    // directory above it), so a concurrent committedEntries() directory scan
    // never observes an in-flight write as an unexpected entry. The atomic
    // move back into the outbox directory stays on the same filesystem.
    val stagingDir = target.parent?.parent ?: target.parent
    return stagingDir.resolve(".${target.fileName}.tmp.${RANDOM.nextInt(Int.MAX_VALUE)}")
}

private fun writeFileWith0600(path: Path, bytes: ByteArray, createNew: Boolean) {
    val options = if (createNew) {
        setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.DSYNC)
    } else {
        setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.DSYNC)
    }
    FileChannel.open(
        path,
        options,
        PosixFilePermissions.asFileAttribute(FILE_PERMS_0600),
    ).use { channel ->
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
        channel.force(true)
    }
}

private fun forceParentDirectory(dir: Path?) {
    if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return
    try {
        FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
        // best effort
    }
}

/** @see FileSovereignOpsAuditOutboxStore */
private const val STORAGE_NAME = "ops-audit-outbox"

/** @see FileSovereignOpsAuditOutboxStore */
private const val ERROR_OUTBOX_STATUS_MISMATCH = "tramai-sovereign-ops-outbox-status-mismatch"
private const val ERROR_OUTBOX_CONCURRENT_UPDATE = "tramai-sovereign-ops-outbox-concurrent-update"

/** @see FileSovereignOpsAuditOutboxStore */
private const val ERROR_CORRUPTED_RECORD = "ops-audit-outbox-record-corrupted"
