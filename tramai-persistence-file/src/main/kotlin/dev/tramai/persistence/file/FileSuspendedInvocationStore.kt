package dev.tramai.persistence.file

import dev.tramai.engine.SensitiveReplayEnvelope
import dev.tramai.engine.SuspendedInvocationMetadata
import dev.tramai.engine.SuspendedInvocationStore
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.SecretKey
import kotlin.io.path.exists

class FileSuspendedInvocationStore internal constructor(
    root: Path,
    key: SecretKey,
    configuration: FileBackedStoreConfiguration,
    private val lease: FileStoreLease,
) : SuspendedInvocationStore {

    internal data class ValidatedSuspendedInvocationRecord(
        val metadata: SuspendedInvocationMetadata,
        val envelope: SensitiveReplayEnvelope,
    )

    companion object {
        private const val RECORD_TYPE = "suspended-invocation"
        private const val SUSPENDED_DIR = "suspended"
        private const val FILE_EXTENSION = ".tram.enc"
        private val COMMITTED_FILENAME = Regex("[a-f0-9]{64}\\.tram\\.enc")
        private const val MAX_ID_LENGTH = 256
    }

    private val suspendedDir: Path = root.resolve(SUSPENDED_DIR)
    private val keyId: String = configuration.encryption.activeKeyId
    private val encryptionKey: SecretKey = key
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private fun storePath(approvalId: String): Path {
        val digest = FileStoreSha256.digest(RECORD_TYPE, approvalId)
        return suspendedDir.resolve("$digest$FILE_EXTENSION")
    }

    private fun recordKeyDigest(approvalId: String): String =
        FileStoreSha256.digest(RECORD_TYPE, approvalId)

    private fun getLock(approvalId: String): ReentrantLock =
        locks.computeIfAbsent(approvalId) { ReentrantLock() }

    private fun readCurrent(approvalId: String): PersistedSuspendedInvocationRecordV1? {
        val path = storePath(approvalId)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        FileStoreUtil.validateRegularFile(path, "suspended-invocation")
        val rkd = recordKeyDigest(approvalId)
        val plaintext = try {
            FileStoreUtil.readAndDecrypt(path, RECORD_TYPE, rkd, encryptionKey, keyId)
        } catch (e: FileStoreCorruptionException) {
            throw FileStoreCorruptionException("suspended-invocation-record-corrupted", e)
        } catch (e: Exception) {
            throw FileStoreCorruptionException("suspended-invocation-record-corrupted", e)
        }
        val record = try {
            PersistedSuspendedInvocationRecordV1.fromJson(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            throw FileStoreCorruptionException("suspended-invocation-record-corrupted", e)
        }
        validateRecordSchemas(record)
        val expectedDigest = FileStoreSha256.digest(RECORD_TYPE, record.metadata.approvalId)
        if (expectedDigest != rkd) {
            throw FileStoreCorruptionException("suspended-invocation-id-filename-digest-mismatch")
        }
        return record
    }

    internal fun decodeAndValidateRecord(
        record: PersistedSuspendedInvocationRecordV1,
    ): ValidatedSuspendedInvocationRecord {
        try {
            validateRecordSchemas(record)
        } catch (e: Exception) {
            throw FileStoreCorruptionException("suspended-invocation-record-corrupted", e)
        }

        val metadata = try {
            record.metadata.toDomain()
        } catch (e: Exception) {
            throw FileStoreCorruptionException("suspended-invocation-domain-conversion-failed", e)
        }

        val messages = try {
            record.replayEnvelope.messages.map { it.toDomain() }
        } catch (e: Exception) {
            throw FileStoreCorruptionException("suspended-invocation-domain-conversion-failed", e)
        }

        val envelope = try {
            ReplayEnvelopePersistenceCodec.restoreFromPersistence(metadata, messages)
        } catch (e: Exception) {
            throw FileStoreCorruptionException("suspended-invocation-replay-envelope-invalid", e)
        }

        return ValidatedSuspendedInvocationRecord(metadata, envelope)
    }

    fun verifyAll() = lease.withOpenOperation {
        if (!suspendedDir.exists()) return@withOpenOperation
        FileStoreUtil.validateManagedDirectory(suspendedDir, "suspended")
        for (entry in FileStoreUtil.strictCommittedEntries(
            suspendedDir,
            COMMITTED_FILENAME,
            "suspended-invocation",
        )) {
            val fileName = entry.fileName.toString()
            val digestHex = fileName.removeSuffix(FILE_EXTENSION)
            if (digestHex.length != 64 || digestHex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                throw FileStoreCorruptionException("suspended-invocation-invalid-filename")
            }
            FileStoreUtil.validateRegularFile(entry, "suspended-invocation")
            val plaintext = try {
                FileStoreUtil.readAndDecrypt(entry, RECORD_TYPE, digestHex, encryptionKey, keyId)
            } catch (e: FileStoreCorruptionException) {
                throw FileStoreCorruptionException("suspended-invocation-record-corrupted", e)
            } catch (e: Exception) {
                throw FileStoreCorruptionException("suspended-invocation-record-corrupted", e)
            }
            val record = try {
                PersistedSuspendedInvocationRecordV1.fromJson(String(plaintext, Charsets.UTF_8))
            } catch (e: Exception) {
                throw FileStoreCorruptionException("suspended-invocation-record-corrupted", e)
            }
            val expectedDigest = FileStoreSha256.digest(RECORD_TYPE, record.metadata.approvalId)
            if (expectedDigest != digestHex) {
                throw FileStoreCorruptionException("suspended-invocation-id-filename-digest-mismatch")
            }
            validateRecordSchemas(record)
            val validated = decodeAndValidateRecord(record)
            try {
                ReplayEnvelopePersistenceCodec.snapshotForPersistence(validated.metadata, validated.envelope)
            } catch (e: Exception) {
                throw FileStoreCorruptionException("suspended-invocation-replay-envelope-invalid", e)
            }
        }
    }

    override suspend fun create(
        metadata: SuspendedInvocationMetadata,
        replayEnvelope: SensitiveReplayEnvelope,
    ) = lease.withOpenOperation {
        FileStoreUtil.validateManagedDirectory(suspendedDir, "suspended")
        validateIdField(metadata.approvalId, "approvalId", MAX_ID_LENGTH)
        validateIdField(metadata.toolCallId, "toolCallId", MAX_ID_LENGTH)
        validateIdField(metadata.toolName, "toolName", MAX_ID_LENGTH)
        validateIdField(metadata.correlationId, "correlationId", MAX_ID_LENGTH)
        metadata.conversationId?.let { validateIdField(it, "conversationId", MAX_ID_LENGTH) }

        val lock = getLock(metadata.approvalId)
        lock.lock()
        try {
            val messages = ReplayEnvelopePersistenceCodec.snapshotForPersistence(metadata, replayEnvelope)
            val record = PersistedSuspendedInvocationRecordV1(
                schemaVersion = 1,
                metadata = metadata.toPersistedV1(),
                replayEnvelope = PersistedReplayEnvelopeV1(
                    schemaVersion = 1,
                    messages = messages.map { it.toPersistedV1() },
                ),
            )
            val path = storePath(metadata.approvalId)
            val rkd = recordKeyDigest(metadata.approvalId)
            val jsonBytes = record.toJson().toByteArray(Charsets.UTF_8)
            try {
                FileStoreUtil.atomicEncryptCreate(
                    path,
                    RECORD_TYPE,
                    rkd,
                    keyId,
                    encryptionKey,
                    jsonBytes,
                )
            } catch (_: FileAlreadyExistsException) {
                throw IllegalArgumentException("suspended-invocation-already-exists")
            }
        } finally {
            lock.unlock()
        }
    }

    override suspend fun get(approvalId: String): SuspendedInvocationMetadata? = lease.withOpenOperation {
        FileStoreUtil.validateManagedDirectory(suspendedDir, "suspended")
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
        val lock = getLock(approvalId)
        lock.lock()
        try {
            val record = readCurrent(approvalId) ?: return null
            return decodeAndValidateRecord(record).metadata
        } finally {
            lock.unlock()
        }
    }

    override suspend fun revealReplayEnvelope(approvalId: String): SensitiveReplayEnvelope? = lease.withOpenOperation {
        FileStoreUtil.validateManagedDirectory(suspendedDir, "suspended")
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
        val lock = getLock(approvalId)
        lock.lock()
        try {
            val record = readCurrent(approvalId) ?: return null
            return decodeAndValidateRecord(record).envelope
        } finally {
            lock.unlock()
        }
    }

    override suspend fun remove(approvalId: String): SuspendedInvocationMetadata? = lease.withOpenOperation {
        FileStoreUtil.validateManagedDirectory(suspendedDir, "suspended")
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
        val lock = getLock(approvalId)
        lock.lock()
        try {
            val record = readCurrent(approvalId) ?: return null
            val validated = decodeAndValidateRecord(record)
            Files.delete(storePath(approvalId))
            FileStoreUtil.forceParentDirectory(suspendedDir)
            return validated.metadata
        } finally {
            lock.unlock()
        }
    }

    private fun validateRecordSchemas(record: PersistedSuspendedInvocationRecordV1) {
        if (record.schemaVersion != 1) {
            throw FileStoreUnsupportedFormatException("unsupported-suspended-invocation-schema-version")
        }
        if (record.metadata.schemaVersion != 1) {
            throw FileStoreUnsupportedFormatException("unsupported-suspended-invocation-metadata-schema-version")
        }
        if (record.metadata.identity.schemaVersion != 1) {
            throw FileStoreUnsupportedFormatException("unsupported-engine-execution-identity-schema-version")
        }
        if (record.metadata.securityContext.schemaVersion != 1) {
            throw FileStoreUnsupportedFormatException("unsupported-execution-security-context-schema-version")
        }
        if (record.metadata.operationReference.schemaVersion != 1) {
            throw FileStoreUnsupportedFormatException("unsupported-resume-operation-reference-schema-version")
        }
        if (record.metadata.toolReference.schemaVersion != 1) {
            throw FileStoreUnsupportedFormatException("unsupported-resume-tool-reference-schema-version")
        }
        record.metadata.tokenBudgetSnapshot?.let {
            if (it.schemaVersion != 1) {
                throw FileStoreUnsupportedFormatException("unsupported-token-budget-snapshot-schema-version")
            }
        }
        record.metadata.toolSecurity?.let {
            if (it.schemaVersion != 1) {
                throw FileStoreUnsupportedFormatException("unsupported-tool-security-metadata-schema-version")
            }
        }
        if (record.replayEnvelope.schemaVersion != 1) {
            throw FileStoreUnsupportedFormatException("unsupported-replay-envelope-schema-version")
        }
        record.replayEnvelope.messages.forEach { message ->
            if (message.schemaVersion != 1) {
                throw FileStoreUnsupportedFormatException("unsupported-replay-message-schema-version")
            }
            message.toolCalls?.forEach { toolCall ->
                if (toolCall.schemaVersion != 1) {
                    throw FileStoreUnsupportedFormatException("unsupported-replay-tool-call-schema-version")
                }
            }
            message.contentParts?.forEach { contentPart ->
                if (contentPart.schemaVersion != 1) {
                    throw FileStoreUnsupportedFormatException("unsupported-replay-content-part-schema-version")
                }
            }
        }
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
