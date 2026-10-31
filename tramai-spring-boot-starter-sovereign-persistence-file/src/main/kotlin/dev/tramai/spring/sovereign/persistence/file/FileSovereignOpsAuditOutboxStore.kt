@file:OptIn(ExperimentalTramaiInternalApi::class)

package dev.tramai.spring.sovereign.persistence.file

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import dev.tramai.core.observation.secondary.ExperimentalTramaiInternalApi
import dev.tramai.persistence.file.AesGcmFileEncryption
import dev.tramai.persistence.file.EncryptedFileEnvelopeV1
import dev.tramai.persistence.file.FileStoreCorruptionException
import dev.tramai.persistence.file.FileStorePermissionException
import dev.tramai.persistence.file.FileStoreSha256
import dev.tramai.persistence.file.FileStoreUnsupportedFormatException
import dev.tramai.spring.sovereign.ops.outbox.GovernedSovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.QuarantineReport
import dev.tramai.spring.sovereign.ops.outbox.QuarantinedEvidenceSummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxGovernance
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxQuarantineStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
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
import java.util.stream.Collectors
import javax.crypto.SecretKey

internal val OPS_OUTBOX_JSON: ObjectMapper =
    JsonMapper
        .builder()
        .addModule(KotlinModule.Builder().build())
        .serializationInclusion(JsonInclude.Include.NON_ABSENT)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true)
        .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, false)
        .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
        .build()

private const val MAX_JSON_SIZE = 10_485_760

internal inline fun <reified T : Any> strictOpsOutboxReadValue(json: String): T {
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
    @JsonProperty("approvalStatus") val approvalStatus: String?,
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
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    @param:JsonProperty("actorIssuer") val actorIssuer: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    @param:JsonProperty("actorIdentityDigest") val actorIdentityDigest: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    @param:JsonProperty("previousApprovalStatus") val previousApprovalStatus: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    @param:JsonProperty("previousApprovalVersion") val previousApprovalVersion: Long? = null,
    @param:JsonProperty("evidenceKind") val evidenceKind: String? = null,
    @param:JsonProperty("refusalReason") val refusalReason: String? = null,
) {
    fun toJson(): String = OPS_OUTBOX_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): PersistedSovereignOpsAuditOutboxRecordV1 = strictOpsOutboxReadValue(json)
    }
}

internal fun SovereignOpsAuditOutboxRecord.toPersistedV1(version: Long): PersistedSovereignOpsAuditOutboxRecordV1 =
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
        outboxRecordVersion = version,
        actorIssuer = actorIssuer,
        actorIdentityDigest = actorIdentityDigest,
        previousApprovalStatus = previousApprovalStatus,
        previousApprovalVersion = previousApprovalVersion,
        evidenceKind = evidenceKind?.name,
        refusalReason = refusalReason,
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
        actorIssuer = actorIssuer,
        actorIdentityDigest = actorIdentityDigest,
        previousApprovalStatus = previousApprovalStatus,
        previousApprovalVersion = previousApprovalVersion,
        evidenceKind = evidenceKind?.let { SovereignOpsAuditEvidenceKind.valueOf(it) },
        refusalReason = refusalReason,
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
        private const val QUARANTINE_DIR = "quarantine"
        private const val LOCATOR_LENGTH = 64
        private const val MARKER_SUFFIX = ".quarantine.json"
        private const val FILE_EXTENSION = ".tram.enc"
        private const val SCAN_CURSOR_FILE = "quarantine-scan.cursor"

        // Raised when the filesystem cannot publish a marker atomically without clobbering.
        private const val ERROR_PUBLICATION_UNSUPPORTED =
            "tramai-sovereign-ops-outbox-quarantine-publication-unsupported"
        private const val KEY_ID = "default"
        private val COMMITTED_FILENAME = Regex("[a-f0-9]{64}\\.tram\\.enc")
    }

    private val rootDir: Path = root.toAbsolutePath().normalize()
    private val outboxDir: Path = rootDir.resolve(OUTBOX_DIR)
    private val quarantineDir: Path = rootDir.resolve(QUARANTINE_DIR)
    private val encryptionKey: SecretKey = key
    private val recordLocks = ConcurrentHashMap<String, ReentrantLock>()
    private val appendLock = ReentrantLock()
    private val eventKeyIndex = ConcurrentHashMap<String, String>()

    @Volatile
    private var unindexableDigests: List<String> = emptyList()

    private val scanCursorFile: Path = rootDir.resolve(SCAN_CURSOR_FILE)

    init {
        ensureManagedDirectory(outboxDir, STORAGE_NAME)
        ensureManagedDirectory(quarantineDir, QUARANTINE_DIR)
        rebuildIndex()
    }

    private fun storePath(outboxId: String): Path = outboxDir.resolve("${recordKeyDigest(outboxId)}$FILE_EXTENSION")

    private fun recordKeyDigest(outboxId: String): String = FileStoreSha256.digest(RECORD_TYPE, outboxId)

    private fun getLockForDigest(key: String): ReentrantLock = recordLocks.computeIfAbsent(key) { ReentrantLock() }

    private fun getLockForOutboxId(outboxId: String): ReentrantLock = getLockForDigest(recordKeyDigest(outboxId))

    fun rebuildIndex() =
        lease.withOpenOperation {
            eventKeyIndex.clear()
            unindexableDigests = emptyList()
            if (!Files.exists(outboxDir, LinkOption.NOFOLLOW_LINKS)) return@withOpenOperation
            validateManagedDirectory(outboxDir, STORAGE_NAME)
            // 0.7.6f: one bad record must not block startup. A record carrying a quarantine marker is
            // honoured without being decoded at all, and a record that fails to decode is indexed as
            // decode-failed by its file digest only. Quarantine completes the marker on the next
            // recovery or dispatch run; initialization never depends on a damaged record.
            unindexableDigests =
                buildList {
                    for (entry in committedEntries()) {
                        val digest = digestFromPath(entry)
                        if (!hasQuarantineMarker(digest)) {
                            try {
                                val decoded = validatePersistedRecord(readCurrentByDigest(entry, digest), digest)
                                eventKeyIndex[decoded.record.eventKey] = decoded.record.outboxId
                            } catch (_: FileStoreCorruptionException) {
                                // no event key, no outbox id, no startup failure
                                add(digest)
                            } catch (_: FileStoreUnsupportedFormatException) {
                                // same: an unknown shape is undecodable evidence, not an init error
                                add(digest)
                            }
                        }
                    }
                }
            // Committed records this pass could not index: their event keys are unknown, so a new append
            // cannot prove uniqueness against them until they are quarantined (and therefore unemittable).
        }

    /**
     * Fails closed when a committed record could not be indexed and is still unmarked.
     *
     * Event keys live inside the ciphertext, so a damaged record cannot contribute its own key. A
     * quarantined record is exempt: it is permanently unemittable, so a later append reusing its key can
     * never produce a second emission. An unmarked damaged record may still dispatch, so appends refuse
     * rather than risk a duplicate.
     */
    private fun requireEventKeysProvable() {
        val unresolved = unindexableDigests.filterNot { hasQuarantineMarker(it) }
        check(unresolved.isEmpty()) { "tramai-sovereign-ops-outbox-event-key-uniqueness-unavailable" }
    }

    override fun isDurable(): Boolean = true

    override suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord =
        appendRecord(record, requiredStatus = SovereignOpsAuditOutboxStatus.PREPARED)

    /**
     * 0.7.6f: commits a refusal record directly as PENDING -- one atomic encrypted write, no separate
     * transition to wait for. Only refusal evidence may take this path.
     */
    override suspend fun appendDispatchable(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord {
        require(record.evidenceKind == SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL) {
            "tramai-sovereign-ops-outbox-invalid-evidence-kind"
        }
        return appendRecord(record, requiredStatus = SovereignOpsAuditOutboxStatus.PENDING)
    }

    private suspend fun appendRecord(
        record: SovereignOpsAuditOutboxRecord,
        requiredStatus: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord =
        lease.withOpenOperation {
            validateManagedDirectory(outboxDir, STORAGE_NAME)
            require(record.outboxId.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-id" }
            require(record.eventKey.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-event-key" }
            require(record.status == requiredStatus) { "tramai-sovereign-ops-outbox-invalid-status" }

            appendLock.lock()
            try {
                require(!Files.exists(storePath(record.outboxId), LinkOption.NOFOLLOW_LINKS)) {
                    "tramai-sovereign-ops-outbox-duplicate-id"
                }
                requireEventKeysProvable()
                require(!eventKeyIndex.containsKey(record.eventKey)) {
                    "tramai-sovereign-ops-outbox-duplicate-event-key"
                }

                val decoded = DecodedOutboxRecord(record, runIdentity = null, outboxRecordVersion = 0L)
                createAtomically(encodeOutboxRecord(decoded, 0L), record.outboxId)
                eventKeyIndex[record.eventKey] = record.outboxId
                record
            } finally {
                appendLock.unlock()
            }
        }

    override suspend fun markReadyForDispatch(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord =
        mutate(outboxId) { decoded ->
            require(expectedStatus == SovereignOpsAuditOutboxStatus.PREPARED) {
                ERROR_OUTBOX_STATUS_MISMATCH
            }
            require(decoded.record.status == expectedStatus) {
                ERROR_OUTBOX_STATUS_MISMATCH
            }
            decoded.record.copy(status = SovereignOpsAuditOutboxStatus.PENDING)
        }

    override suspend fun claimPending(
        claimedBy: String,
        limit: Int,
        now: Instant,
    ): List<SovereignOpsAuditOutboxRecord> =
        lease.withOpenOperation {
            if (limit <= 0) return@withOpenOperation emptyList()
            validateManagedDirectory(outboxDir, STORAGE_NAME)

            val claimed = mutableListOf<SovereignOpsAuditOutboxRecord>()
            for (entry in committedEntries()) {
                if (claimed.size >= limit) break
                val digest = digestFromPath(entry)
                if (hasQuarantineMarker(digest)) continue
                val lock = getLockForDigest(digest)
                lock.lock()
                try {
                    // 0.7.6f: a record that cannot be decoded is skipped, never thrown past the batch.
                    // It gains its quarantine marker on the recovery pass and stops being a candidate.
                    val decoded =
                        try {
                            validatePersistedRecord(readCurrentByDigest(entry, digest), digest)
                        } catch (_: FileStoreCorruptionException) {
                            continue
                        } catch (_: FileStoreUnsupportedFormatException) {
                            continue
                        }
                    val record = decoded.record
                    if (!record.isDispatchable(now)) continue

                    val updated =
                        record.copy(
                            status = SovereignOpsAuditOutboxStatus.EMITTING,
                            attemptCount = record.attemptCount + 1,
                            claimedBy = claimedBy,
                            claimedAt = now,
                            claimExpiresAt = now.plus(claimLeaseDuration),
                            lastErrorCode = null,
                        )
                    writeCurrent(
                        record.outboxId,
                        decoded.copy(record = updated),
                        nextVersion(decoded.outboxRecordVersion),
                    )
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
    ): SovereignOpsAuditOutboxRecord =
        mutate(outboxId) { decoded ->
            require(expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING) {
                ERROR_OUTBOX_STATUS_MISMATCH
            }
            require(decoded.record.status == expectedStatus) {
                ERROR_OUTBOX_STATUS_MISMATCH
            }
            val record = decoded.record
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
    ): SovereignOpsAuditOutboxRecord =
        mutate(outboxId) { decoded ->
            if (retryable) {
                require(expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING) {
                    ERROR_OUTBOX_STATUS_MISMATCH
                }
            } else {
                require(
                    expectedStatus == SovereignOpsAuditOutboxStatus.EMITTING ||
                        expectedStatus == SovereignOpsAuditOutboxStatus.PREPARED,
                ) {
                    ERROR_OUTBOX_STATUS_MISMATCH
                }
            }
            require(decoded.record.status == expectedStatus) {
                ERROR_OUTBOX_STATUS_MISMATCH
            }
            val record = decoded.record
            check(record.attemptCount == expectedAttemptCount) {
                ERROR_OUTBOX_CONCURRENT_UPDATE
            }
            val targetStatus =
                if (retryable) {
                    SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE
                } else {
                    SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
                }
            record.copy(
                status = targetStatus,
                lastErrorCode = errorCode,
            )
        }

    /**
     * 0.7.1d: appends a governed record, persisting the ordinary outbox fields and the complete
     * canonical identity as one encrypted atomic write. No second file, no identity sidecar.
     */
    internal suspend fun appendGoverned(entry: GovernedSovereignOpsAuditOutboxRecord) {
        lease.withOpenOperation {
            validateManagedDirectory(outboxDir, STORAGE_NAME)
            val record = entry.record
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
                requireEventKeysProvable()
                require(!eventKeyIndex.containsKey(record.eventKey)) {
                    "tramai-sovereign-ops-outbox-duplicate-event-key"
                }
                val decoded = DecodedOutboxRecord(record, entry.runIdentity, 0L)
                createAtomically(encodeOutboxRecord(decoded, 0L), record.outboxId)
                eventKeyIndex[record.eventKey] = record.outboxId
            } finally {
                appendLock.unlock()
            }
        }
    }

    /**
     * 0.7.1d: resolves provenance from record existence first, so an absent record is never mistaken
     * for a legacy one.
     */
    internal suspend fun governanceById(outboxId: String): SovereignOpsAuditOutboxGovernance =
        lease.withOpenOperation {
            val lock = getLockForOutboxId(outboxId)
            lock.lock()
            try {
                val decoded = readCurrent(outboxId)
                if (decoded == null) {
                    SovereignOpsAuditOutboxGovernance.NoRecord
                } else if (decoded.runIdentity == null) {
                    SovereignOpsAuditOutboxGovernance.Legacy(decoded.record)
                } else {
                    SovereignOpsAuditOutboxGovernance.Governed(
                        GovernedSovereignOpsAuditOutboxRecord(decoded.record, decoded.runIdentity),
                    )
                }
            } finally {
                lock.unlock()
            }
        }

    override suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord? =
        lease.withOpenOperation {
            val lock = getLockForOutboxId(outboxId)
            lock.lock()
            try {
                readCurrent(outboxId)?.record
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
                readCurrent(outboxId)?.record
            } finally {
                lock.unlock()
            }
        }

    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> =
        listRecords(limit) { it.status == SovereignOpsAuditOutboxStatus.PENDING }

    override suspend fun listByStatus(
        status: SovereignOpsAuditOutboxStatus,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> = listRecords(limit) { it.status == status }

    override suspend fun listExpiredEmitting(
        now: Instant,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> =
        listRecords(limit) {
            it.status == SovereignOpsAuditOutboxStatus.EMITTING &&
                it.claimExpiresAt?.isBefore(now) == true
        }

    fun verifyAll() =
        lease.withOpenOperation {
            if (!Files.exists(outboxDir, LinkOption.NOFOLLOW_LINKS)) return@withOpenOperation
            validateManagedDirectory(outboxDir, STORAGE_NAME)
            for (entry in committedEntries()) {
                val digest = digestFromPath(entry)
                validatePersistedRecord(readCurrentByDigest(entry, digest), digest)
            }
        }

    override fun close() {
        lease.close()
    }

    private fun mutate(
        outboxId: String,
        update: (DecodedOutboxRecord) -> SovereignOpsAuditOutboxRecord,
    ): SovereignOpsAuditOutboxRecord =
        lease.withOpenOperation {
            validateManagedDirectory(outboxDir, STORAGE_NAME)
            val lock = getLockForOutboxId(outboxId)
            lock.lock()
            try {
                val decoded =
                    readCurrent(outboxId)
                        ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")
                // The updater may only replace the ordinary record; the decoded identity is carried
                // around the mutation, so a transition can never rewrite a governed V2 payload as V1.
                val updated = update(decoded)
                writeCurrent(outboxId, decoded.copy(record = updated), nextVersion(decoded.outboxRecordVersion))
                updated
            } finally {
                lock.unlock()
            }
        }

    private fun readCurrent(outboxId: String): DecodedOutboxRecord? {
        val path = storePath(outboxId)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val digest = recordKeyDigest(outboxId)
        return validatePersistedRecord(readCurrentByDigest(path, digest), digest)
    }

    private fun readCurrentByDigest(
        path: Path,
        digest: String,
    ): DecodedOutboxRecord {
        validateRegularFile(path, STORAGE_NAME)
        val plaintext =
            try {
                readAndDecrypt(path, digest)
            } catch (e: FileStoreCorruptionException) {
                throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
            } catch (e: Exception) {
                throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
            }
        // Decoding sits outside the envelope-failure catch above, so the codec's own error contract
        // (corruption vs unsupported format) reaches the caller unchanged.
        return decodeOutboxRecord(String(plaintext, Charsets.UTF_8))
    }

    private fun validatePersistedRecord(
        decoded: DecodedOutboxRecord,
        digest: String,
    ): DecodedOutboxRecord {
        if (FileStoreSha256.digest(RECORD_TYPE, decoded.record.outboxId) != digest) {
            throw FileStoreCorruptionException("ops-audit-outbox-id-filename-digest-mismatch")
        }
        return decoded
    }

    private fun writeCurrent(
        outboxId: String,
        decoded: DecodedOutboxRecord,
        outboxRecordVersion: Long,
    ) {
        atomicEncryptWrite(
            targetPath = storePath(outboxId),
            recordKeyDigest = recordKeyDigest(outboxId),
            plaintextBytes = encodeOutboxRecord(decoded, outboxRecordVersion).toByteArray(Charsets.UTF_8),
            replaceExisting = true,
        )
    }

    private fun createAtomically(
        json: String,
        outboxId: String,
    ) {
        atomicEncryptWrite(
            targetPath = storePath(outboxId),
            recordKeyDigest = recordKeyDigest(outboxId),
            plaintextBytes = json.toByteArray(Charsets.UTF_8),
            replaceExisting = false,
        )
    }

    private fun readAndDecrypt(
        path: Path,
        expectedRecordKeyDigest: String,
    ): ByteArray {
        val envelope =
            try {
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
        val (nonceBase64, ciphertextBase64) =
            AesGcmFileEncryption.encrypt(
                key = encryptionKey,
                recordType = RECORD_TYPE,
                recordKeyDigest = recordKeyDigest,
                keyId = KEY_ID,
                plaintextBytes = plaintextBytes,
            )
        val envelope =
            EncryptedFileEnvelopeV1(
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
        predicate: (SovereignOpsAuditOutboxRecord) -> Boolean,
    ): List<SovereignOpsAuditOutboxRecord> =
        lease.withOpenOperation {
            if (limit <= 0) return@withOpenOperation emptyList()
            validateManagedDirectory(outboxDir, STORAGE_NAME)
            val results = mutableListOf<SovereignOpsAuditOutboxRecord>()
            for (entry in committedEntries()) {
                if (results.size >= limit) break
                val digest = digestFromPath(entry)
                if (hasQuarantineMarker(digest)) continue
                val lock = getLockForDigest(digest)
                lock.lock()
                try {
                    val decoded =
                        try {
                            validatePersistedRecord(readCurrentByDigest(entry, digest), digest)
                        } catch (_: FileStoreCorruptionException) {
                            continue
                        } catch (_: FileStoreUnsupportedFormatException) {
                            continue
                        }
                    if (predicate(decoded.record)) results += decoded.record
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

    /** A payload that decodes but contradicts its own file-name digest is corrupt evidence. */
    private fun persistedVerdict(
        decoded: DecodedOutboxRecord,
        digest: String,
    ): QuarantineVerdict =
        try {
            validatePersistedRecord(decoded, digest)
            QuarantineVerdict.Readable
        } catch (e: FileStoreCorruptionException) {
            failureVerdict(SovereignOpsAuditOutboxQuarantineStore.LABEL_CORRUPT, e)
        } catch (e: FileStoreUnsupportedFormatException) {
            failureVerdict(SovereignOpsAuditOutboxQuarantineStore.LABEL_CORRUPT, e)
        }

    private fun failureVerdict(
        label: String,
        e: Throwable,
    ): QuarantineVerdict.Failure =
        QuarantineVerdict.Failure(
            QuarantineFailure(label, boundedDetail(e as? Exception ?: IllegalStateException(e))),
        )

    /** Whether a record already carries a durable quarantine marker. Never decodes anything. */
    private fun readScanCursor(): String? {
        if (!Files.exists(scanCursorFile, LinkOption.NOFOLLOW_LINKS)) return null
        return Files.readString(scanCursorFile, StandardCharsets.UTF_8).trim().ifBlank { null }
    }

    /**
     * Durable scan position so consecutive recovery passes make progress instead of retrying a prefix.
     *
     * The staging file stays inside the store root: [tempSibling] stages a level above its target, which is
     * right for records under the outbox directory but would put this cursor's temp file in the root's
     * parent -- a directory a hardened deployment need not make writable.
     */
    private fun writeScanCursor(digest: String) {
        val temp = rootDir.resolve(".$SCAN_CURSOR_FILE.tmp.${RANDOM.nextInt(Int.MAX_VALUE)}")
        try {
            writeFileWith0600(temp, digest.toByteArray(StandardCharsets.UTF_8), createNew = true)
            Files.move(temp, scanCursorFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            forceParentDirectory(rootDir)
        } finally {
            runCatching { Files.deleteIfExists(temp) }
        }
    }

    private fun hasQuarantineMarker(locator: String): Boolean =
        Files.exists(quarantineDir.resolve("$locator$MARKER_SUFFIX"), LinkOption.NOFOLLOW_LINKS)

    /**
     * One record's recovery verdict.
     *
     * [Failure] is evidence this build cannot read; [Readable] is the control that proves the deployment
     * key works; [Unsupported] is a payload written by a newer build, preserved untouched so a rollback
     * does not permanently condemn evidence the next upgrade can read again.
     */
    private sealed interface QuarantineVerdict {
        class Failure(
            val failure: QuarantineFailure,
        ) : QuarantineVerdict

        object Readable : QuarantineVerdict

        object Unsupported : QuarantineVerdict
    }

    /** One record's quarantine verdict: a label and a bounded detail. */
    private data class QuarantineFailure(
        val label: String,
        val detail: String,
    )

    /**
     * 0.7.6f: the recovery surface, implemented as an inner class so this store's own shape (and its
     * quality baseline) is unchanged: quarantine is a separate contract, not another store operation.
     *
     * Eligibility caveat, stated rather than papered over: this backend keeps a record's status *inside*
     * the sealed payload, so a record that cannot be decoded cannot prove it is PENDING. The JDBC store
     * excludes PREPARED from its scan by column; here that exclusion is impossible. What makes it safe is
     * that quarantine is metadata-only and reversible: the `.tram.enc` file is never modified, its bytes
     * stay retrievable through [readQuarantinedCiphertext], the marker can be deleted by an operator, and a
     * PREPARED record that *does* decode is a control record and is never a candidate. The cost of a wrong
     * guess is therefore a marker, never lost evidence.
     */
    private inner class FileQuarantine : SovereignOpsAuditOutboxQuarantineStore {
        /**
         * Classifies the scan batch before writing anything, so a batch in which nothing decodes aborts
         * instead of quarantining healthy evidence with a wrong key.
         */
        override suspend fun quarantineUndecodableEvidence(
            limit: Int,
            now: Instant,
        ): QuarantineReport =
            lease.withOpenOperation {
                if (limit <= 0) return@withOpenOperation QuarantineReport(0, 0, emptyList(), 0)
                validateManagedDirectory(outboxDir, STORAGE_NAME)
                val alreadyMarked = quarantinedDigests()
                val unmarked =
                    committedEntries()
                        .map { digestFromPath(it) to it }
                        .filter { (digest, _) -> digest !in alreadyMarked }
                        .sortedBy { (digest, _) -> digest }
                // Bounded work per pass: exactly `limit` records are decrypted, matching the SPI's meaning of
                // limit, so a large healthy archive is never re-decrypted in full on every dispatch. Progress
                // across passes comes from a durable cursor, and the rotation wraps so nothing is starved.
                val start =
                    readScanCursor()
                        ?.let { cursor -> unmarked.indexOfFirst { (digest, _) -> digest > cursor } }
                        ?.takeIf { it >= 0 }
                        ?: 0
                val batch = (unmarked.drop(start) + unmarked.take(start)).take(limit)
                // buildList, not fold: one append per entry instead of copying the running list each time.
                val classified =
                    buildList {
                        for ((digest, entry) in batch) add(Triple(digest, entry, classify(digest, entry)))
                    }
                if (batch.isNotEmpty()) writeScanCursor(batch.last().first)
                val failureList =
                    classified.mapNotNull { (_, _, verdict) -> (verdict as? QuarantineVerdict.Failure)?.failure }
                val decodable = classified.count { (_, _, verdict) -> verdict is QuarantineVerdict.Readable }
                requireUsableKey(classified.size, decodable, failureList)
                val quarantined =
                    classified.mapNotNull { (digest, path, verdict) ->
                        (verdict as? QuarantineVerdict.Failure)?.let {
                            writeQuarantineMarker(digest, path, it.failure, now)
                        }
                    }
                QuarantineReport(
                    scanned = classified.size,
                    decodable = decodable,
                    quarantined = quarantined,
                    skipped = alreadyMarked.size,
                )
            }

        override suspend fun listQuarantined(limit: Int): List<QuarantinedEvidenceSummary> =
            lease.withOpenOperation {
                if (limit <= 0) return@withOpenOperation emptyList()
                validateManagedDirectory(quarantineDir, QUARANTINE_DIR)
                val markers: List<PersistedQuarantineMarker> =
                    Files.list(quarantineDir).use { stream ->
                        stream
                            .filter { it.fileName.toString().endsWith(MARKER_SUFFIX) }
                            .map { parseMarker(it) }
                            .collect(Collectors.toList())
                    }
                markers
                    .sortedWith(
                        compareByDescending<PersistedQuarantineMarker> { it.quarantinedAt }.thenBy { it.locator },
                    ).take(limit)
                    .map { it.toSummary() }
            }

        override suspend fun readQuarantinedCiphertext(locator: String): ByteArray? =
            lease.withOpenOperation {
                if (!isValidLocator(locator) || !hasQuarantineMarker(locator)) return@withOpenOperation null
                val path = outboxDir.resolve("$locator$FILE_EXTENSION")
                if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withOpenOperation null
                validateRegularFile(path, STORAGE_NAME)
                Files.readAllBytes(path)
            }

        /**
         * A record's verdict, or null when it decodes and its file name agrees with its own outbox id.
         *
         * The labels stay distinct: a record that cannot be decrypted or decoded is undecodable evidence,
         * while one that decodes but whose stored id contradicts its file-name digest is corrupt evidence.
         */
        private fun classify(
            digest: String,
            path: Path,
        ): QuarantineVerdict =
            try {
                persistedVerdict(readCurrentByDigest(path, digest), digest)
            } catch (e: FileStoreCorruptionException) {
                failureVerdict(SovereignOpsAuditOutboxQuarantineStore.LABEL_UNDECODABLE, e)
            } catch (_: FileStoreUnsupportedFormatException) {
                // Typed on purpose: runCatching would also swallow CancellationException.
                QuarantineVerdict.Unsupported
            }

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
         * Writes the marker atomically (temp file, fsync, rename) and leaves the record byte-identical.
         *
         * Identifiers are recorded only when the record actually decoded: a SHA-256 record key is not
         * reversible, so an undecodable record keeps null identifiers rather than a synthesised value.
         */
        private fun writeQuarantineMarker(
            digest: String,
            path: Path,
            failure: QuarantineFailure,
            now: Instant,
        ): QuarantinedEvidenceSummary? {
            // Serialized per record and never replacing an existing marker: two recovery passes that both
            // classified the same damage must produce one quarantine, not two reports and a rewritten one.
            val lock = getLockForDigest(digest)
            lock.lock()
            try {
                return createQuarantineMarker(digest, path, failure, now)
            } finally {
                lock.unlock()
            }
        }

        private fun createQuarantineMarker(
            digest: String,
            path: Path,
            failure: QuarantineFailure,
            now: Instant,
        ): QuarantinedEvidenceSummary? =
            if (hasQuarantineMarker(digest)) {
                null
            } else {
                val decoded = runCatching { readCurrentByDigest(path, digest) }.getOrNull()
                val marker =
                    PersistedQuarantineMarker(
                        locator = digest,
                        outboxId = decoded?.record?.outboxId,
                        eventKey = decoded?.record?.eventKey,
                        payloadDigest = payloadDigestOf(path),
                        label = failure.label,
                        quarantinedAt = now.toString(),
                        failureDetail = failure.detail,
                    )
                val target = quarantineDir.resolve("$digest$MARKER_SUFFIX")
                val temp = tempSibling(target)
                try {
                    writeFileWith0600(temp, OPS_OUTBOX_JSON.writeValueAsBytes(marker), createNew = true)
                    // Hard link, not rename: link() is atomic and fails when the target exists, so a marker
                    // is never observable half-written and no pass can clobber another's publication.
                    // A filesystem without links fails closed: ATOMIC_MOVE leaves replacement of an existing
                    // target implementation-defined, so degrading to it could overwrite another worker's
                    // marker and report a second successful quarantine.
                    try {
                        Files.createLink(target, temp)
                    } catch (_: UnsupportedOperationException) {
                        throw IllegalStateException(ERROR_PUBLICATION_UNSUPPORTED)
                    }
                    forceParentDirectory(quarantineDir)
                    marker.toSummary()
                } catch (_: FileAlreadyExistsException) {
                    // Lost the publication race: the winner's marker stands, this pass reports nothing.
                    null
                } finally {
                    runCatching { Files.deleteIfExists(temp) }
                }
            }

        private fun parseMarker(path: Path): PersistedQuarantineMarker =
            try {
                OPS_OUTBOX_JSON.readValue<PersistedQuarantineMarker>(Files.readString(path))
            } catch (e: IOException) {
                // JsonProcessingException is an IOException: one catch covers both.
                throw FileStoreCorruptionException("ops-audit-outbox-quarantine-marker-corrupted", e)
            }

        private fun quarantinedDigests(): Set<String> {
            if (!Files.exists(quarantineDir, LinkOption.NOFOLLOW_LINKS)) return emptySet()
            return Files.list(quarantineDir).use { stream ->
                stream
                    .map { it.fileName.toString() }
                    .filter { it.endsWith(MARKER_SUFFIX) }
                    .map { it.removeSuffix(MARKER_SUFFIX) }
                    .collect(Collectors.toSet())
            }
        }
    }

    override fun quarantineStore(): SovereignOpsAuditOutboxQuarantineStore = FileQuarantine()

    private fun isValidLocator(locator: String): Boolean {
        val hex = locator.length == LOCATOR_LENGTH && locator.all { it in '0'..'9' || it in 'a'..'f' }
        return hex
    }

    private fun payloadDigestOf(path: Path): String {
        val digest = FileStoreSha256.hexOf(Files.readAllBytes(path))
        return "sha256:$digest"
    }

    private fun boundedDetail(e: Exception): String =
        (e.message ?: e::class.simpleName ?: "undecodable-evidence")
            .take(SovereignOpsAuditOutboxQuarantineStore.MAX_FAILURE_DETAIL)

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

    private fun nextVersion(currentVersion: Long): Long =
        try {
            Math.addExact(currentVersion, 1L)
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

private fun ensureManagedDirectory(
    path: Path,
    description: String,
) {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        validateManagedDirectory(path, description)
        return
    }
    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIR_PERMS_0700))
    forceParentDirectory(path.parent)
}

private fun validateManagedDirectory(
    path: Path,
    description: String,
) {
    if (Files.isSymbolicLink(path)) permissionFailure("$description-symlink-rejected")
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        permissionFailure("$description-not-directory")
    }
    if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) != DIR_PERMS_0700) {
        permissionFailure("$description-permission-denied")
    }
}

private fun validateRegularFile(
    path: Path,
    description: String,
) {
    if (Files.isSymbolicLink(path)) permissionFailure("$description-symlink-rejected")
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        permissionFailure("$description-not-regular-file")
    }
    if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) != FILE_PERMS_0600) {
        permissionFailure("$description-permission-denied")
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

private fun writeFileWith0600(
    path: Path,
    bytes: ByteArray,
    createNew: Boolean,
) {
    val options =
        if (createNew) {
            setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.DSYNC)
        } else {
            setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.DSYNC)
        }
    FileChannel
        .open(
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
internal const val ERROR_CORRUPTED_RECORD = "ops-audit-outbox-record-corrupted"
