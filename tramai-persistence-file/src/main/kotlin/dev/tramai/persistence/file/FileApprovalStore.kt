package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalConsumptionReceipt
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.ApprovalTransition
import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotConsumableException
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.ApprovalStoreTokenRejectedException
import dev.tramai.core.exception.IllegalApprovalTransitionException
import dev.tramai.engine.approval.ApprovalAttributionCorruptionException
import dev.tramai.engine.approval.ApprovalRunAttribution
import dev.tramai.engine.approval.ApprovalRunAttribution.Ungoverned
import dev.tramai.engine.approval.decodeApprovalAttribution
import dev.tramai.engine.approval.requireAttributionMatchesBinding
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries

/**
 * File-backed [ApprovalStore] implementation using encrypted atomic file persistence.
 *
 * Each approval request is stored as a single encrypted file under
 * `{root}/approvals/<sha256("approval-request:<approvalId>)>.tram.enc`.
 * Reads, mutations, and writes are serialised per approval ID via
 * [ReentrantLock] to ensure atomic read-modify-write semantics.
 */
class FileApprovalStore internal constructor(
    root: Path,
    key: SecretKey,
    configuration: FileBackedStoreConfiguration,
    private val lease: FileStoreLease,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalStore {
    companion object {
        private const val RECORD_TYPE = "approval-request"
        private const val APPROVALS_DIR = "approvals"
        private const val FILE_EXTENSION = ".tram.enc"
        private val COMMITTED_FILENAME = Regex("[a-f0-9]{64}\\.tram\\.enc")
        private const val MAX_ID_LENGTH = 256
        private const val MAX_COMMENT_LENGTH = 4096
    }

    private val approvalsDir: Path = root.resolve(APPROVALS_DIR)
    private val keyId: String = configuration.encryption.activeKeyId
    private val encryptionKey: SecretKey = key
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val maxCreationTtl: Duration = Duration.ofMinutes(15)

    // ── Path helpers ──────────────────────────────────────────────

    private fun storePath(approvalId: String): Path {
        val digest = FileStoreSha256.digest(RECORD_TYPE, approvalId)
        return approvalsDir.resolve("$digest$FILE_EXTENSION")
    }

    private fun recordKeyDigest(approvalId: String): String = FileStoreSha256.digest(RECORD_TYPE, approvalId)

    private fun getLock(approvalId: String): ReentrantLock = locks.computeIfAbsent(approvalId) { ReentrantLock() }

    // ── Read / write helpers ──────────────────────────────────────

    /**
     * A persisted approval together with its governed attribution snapshot.
     *
     * [attribution] is null exactly when the record is a V1 (legacy, un-attributed) record. Lifecycle
     * rewrites start from this value and only replace [request], so a rewrite site cannot drop
     * attribution by forgetting to pass it.
     */
    private data class PersistedApprovalRecord(
        val request: PersistedApprovalRequestV1,
        val attribution: PersistedApprovalAttributionV1?,
    )

    private fun readRecord(approvalId: String): PersistedApprovalRecord? {
        val path = storePath(approvalId)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        FileStoreUtil.validateRegularFile(path, "approval")
        val rkd = recordKeyDigest(approvalId)
        val plaintext: ByteArray =
            try {
                FileStoreUtil.readAndDecrypt(path, RECORD_TYPE, rkd, encryptionKey, keyId)
            } catch (e: FileStoreCorruptionException) {
                throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
            } catch (e: Exception) {
                throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
            }
        val record = decodeRecord(String(plaintext, Charsets.UTF_8))
        // Bind decoded ID back to filename digest
        val expectedDigest = FileStoreSha256.digest(RECORD_TYPE, record.request.approvalId)
        require(expectedDigest == rkd) { "approval-id-filename-digest-mismatch" }
        return record
    }

    /**
     * Decodes a persisted approval of either schema version.
     *
     * V1 is an un-attributed (legacy) approval, V2 carries governed attribution. The version comes
     * from the document itself, so a malformed V2 fails closed here instead of being reinterpreted as
     * V1 — and the attribution is validated against the canonical run id on every decode, through the
     * same codec the JDBC store uses.
     */
    private fun decodeRecord(json: String): PersistedApprovalRecord {
        val schemaVersion =
            try {
                FILE_STORE_JSON.readTree(json).get("schemaVersion")?.asInt()
            } catch (e: Exception) {
                corrupted(e)
            }
        val record =
            try {
                when (schemaVersion) {
                    1 -> {
                        PersistedApprovalRecord(PersistedApprovalRequestV1.fromJson(json), attribution = null)
                    }

                    2 -> {
                        val v2 = PersistedApprovalRequestV2.fromJson(json)
                        PersistedApprovalRecord(v2.request, v2.attribution)
                    }

                    else -> {
                        null
                    }
                }
            } catch (e: Exception) {
                corrupted(e)
            }
        require(record != null) { "unsupported-approval-schema-version: $schemaVersion" }
        require(record.request.schemaVersion == 1) { "unsupported-approval-schema-version" }
        require(record.request.binding.schemaVersion == 1) { "unsupported-approval-binding-schema-version" }
        record.attribution?.let { attribution ->
            try {
                decodeApprovalAttribution(record.request.binding.workflowRunId, attribution.toReservedKeys())
            } catch (e: ApprovalAttributionCorruptionException) {
                // A V2 record whose attribution is unusable is corruption, not an un-attributed
                // approval: normalise it into this store's durable-state error vocabulary.
                corrupted(e)
            }
        }
        return record
    }

    private fun writeRecord(
        approvalId: String,
        record: PersistedApprovalRecord,
    ) {
        val json =
            if (record.attribution == null) {
                record.request.toJson()
            } else {
                PersistedApprovalRequestV2(
                    schemaVersion = 2,
                    request = record.request,
                    attribution = record.attribution,
                ).toJson()
            }
        val path = storePath(approvalId)
        val rkd = recordKeyDigest(approvalId)
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        FileStoreUtil.atomicEncryptWrite(path, RECORD_TYPE, rkd, keyId, encryptionKey, jsonBytes)
    }

    /**
     * Verifies all existing records in the approvals subdirectory.
     * Called during [FileBackedSovereignStores.open] when verifyOnOpen is true.
     *
     * Validates:
     * - File is a regular file (not a symlink)
     * - File has 0600 permissions
     * - Filename digest matches pattern
     * - Envelope decrypts with correct key and digest
     * - Parsed DTO schema version is supported
     * - Binding schema version is supported
     * - Decoded approval ID matches filename digest
     *
     * Scans ALL entries in the approvals directory — renamed or unexpected
     * files fail closed.
     *
     * @throws FileStoreCorruptionException if any record fails integrity verification.
     */
    fun verifyAll() =
        lease.withOpenOperation {
            if (!approvalsDir.exists()) return@withOpenOperation
            FileStoreUtil.validateManagedDirectory(approvalsDir, "approvals")
            for (entry in FileStoreUtil.strictCommittedEntries(approvalsDir, COMMITTED_FILENAME, "approval")) {
                verifyCommittedApprovalEntry(entry)
            }
        }

    private fun verifyCommittedApprovalEntry(entry: Path) {
        val digestHex = entry.fileName.toString().removeSuffix(FILE_EXTENSION)
        if (digestHex.length != 64 || digestHex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            throw FileStoreCorruptionException("approval-invalid-filename")
        }
        FileStoreUtil.validateRegularFile(entry, "approval")

        val record = readApprovalEntry(entry, digestHex)
        validateApprovalRecordSchema(record)
        validateApprovalFilenameBinding(record, digestHex)
        validateApprovalDomain(record)
    }

    private fun readApprovalEntry(
        entry: Path,
        digestHex: String,
    ): PersistedApprovalRecord {
        val plaintext =
            try {
                FileStoreUtil.readAndDecrypt(entry, RECORD_TYPE, digestHex, encryptionKey, keyId)
            } catch (e: FileStoreCorruptionException) {
                throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
            } catch (e: Exception) {
                throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, e)
            }
        return decodeRecord(String(plaintext, Charsets.UTF_8))
    }

    private fun validateApprovalRecordSchema(record: PersistedApprovalRecord) {
        val attribution = record.attribution
        val unsupported =
            when {
                record.request.schemaVersion != 1 -> {
                    "unsupported-approval-schema-version: ${record.request.schemaVersion}"
                }

                record.request.binding.schemaVersion != 1 -> {
                    "unsupported-approval-binding-schema-version: ${record.request.binding.schemaVersion}"
                }

                attribution != null && attribution.schemaVersion != 1 -> {
                    "unsupported-approval-attribution-schema-version: ${attribution.schemaVersion}"
                }

                else -> {
                    null
                }
            }
        if (unsupported != null) throw FileStoreUnsupportedFormatException(unsupported)
    }

    private fun validateApprovalFilenameBinding(
        record: PersistedApprovalRecord,
        digestHex: String,
    ) {
        val expectedDigest = FileStoreSha256.digest(RECORD_TYPE, record.request.approvalId)
        if (expectedDigest != digestHex) {
            throw FileStoreCorruptionException("approval-id-filename-digest-mismatch")
        }
    }

    private fun validateApprovalDomain(record: PersistedApprovalRecord) {
        try {
            record.request.toDomain()
        } catch (e: Exception) {
            throw FileStoreCorruptionException("approval-domain-conversion-failed", e)
        }
    }

    // ── ApprovalStore SPI ─────────────────────────────────────────

    override suspend fun create(request: ApprovalRequest): ApprovalRequest = createApproval(request, Ungoverned)

    internal suspend fun createGovernedApproval(
        request: ApprovalRequest,
        attribution: ApprovalRunAttribution.Governed,
    ): ApprovalRequest = createApproval(request, attribution)

    private suspend fun createApproval(
        request: ApprovalRequest,
        attribution: ApprovalRunAttribution,
    ): ApprovalRequest =
        lease.withOpenOperation {
            requireAttributionMatchesBinding(request, attribution)
            FileStoreUtil.validateManagedDirectory(approvalsDir, "approvals")
            // Version
            require(request.version == 0L) { "Initial approval version must be 0, got ${request.version}" }

            // Status
            require(request.status == ApprovalStatus.PENDING) { "Expected PENDING, got ${request.status}" }

            // No decision fields set
            require(request.decidedBy == null) { "Initial approval must not have decidedBy set" }
            require(request.decidedAt == null) { "Initial approval must not have decidedAt set" }
            require(request.decisionComment == null) { "Initial approval must not have decisionComment set" }

            // Approval ID
            validateIdField(request.approvalId, "approvalId", MAX_ID_LENGTH)

            // Requested by
            validateIdField(request.requestedBy, "requestedBy", MAX_ID_LENGTH)
            SafeActorIdPolicy.validateActorId(request.requestedBy, "requestedBy")

            // Binding fields
            val binding = request.binding
            validateIdField(binding.workflowRunId, "workflowRunId", MAX_ID_LENGTH)
            validateIdField(binding.toolName, "toolName", MAX_ID_LENGTH)
            validateIdField(binding.policyVersion, "policyVersion", MAX_ID_LENGTH)

            // Expiry: must be in the future
            val now = clock.instant()
            require(request.expiresAt > now) { "expiresAt must be in the future: $now vs ${request.expiresAt}" }
            require(request.expiresAt > request.requestedAt) { "expiresAt must be after requestedAt" }

            // requestedAt must not be in the future
            require(request.requestedAt <= now) { "requestedAt is in the future: ${request.requestedAt} vs $now" }
            require(request.consumedBy == null) { "Initial approval must not have consumedBy set" }
            require(request.consumedAt == null) { "Initial approval must not have consumedAt set" }

            // Bounded TTL
            val ttl = Duration.between(request.requestedAt, request.expiresAt)
            require(ttl <= maxCreationTtl) {
                "expiresAt exceeds maximum creation TTL of $maxCreationTtl"
            }

            val path = storePath(request.approvalId)
            val lock = getLock(request.approvalId)
            lock.lock()
            try {
                if (path.exists()) throw ApprovalStoreConflictException(request.approvalId)

                writeRecord(
                    request.approvalId,
                    PersistedApprovalRecord(
                        request = request.toPersistedV1(),
                        attribution =
                            (attribution as? ApprovalRunAttribution.Governed)
                                ?.let { PersistedApprovalAttributionV1.from(it.identity) },
                    ),
                )
                return request
            } finally {
                lock.unlock()
            }
        }

    override suspend fun get(approvalId: String): ApprovalRequest? =
        lease.withOpenOperation {
            FileStoreUtil.validateManagedDirectory(approvalsDir, "approvals")
            validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
            val lock = getLock(approvalId)
            lock.lock()
            try {
                val record = readRecord(approvalId) ?: return null
                return record.request.toDomain()
            } finally {
                lock.unlock()
            }
        }

    internal suspend fun attributionOf(approvalId: String): ApprovalRunAttribution =
        lease.withOpenOperation {
            FileStoreUtil.validateManagedDirectory(approvalsDir, "approvals")
            validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
            val lock = getLock(approvalId)
            lock.lock()
            try {
                val record = readRecord(approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
                // One codec, shared with JDBC: V2 components are validated against the canonical run id
                // that the binding owns, and a legacy record decodes as un-attributed.
                decodeApprovalAttribution(
                    record.request.binding.workflowRunId,
                    record.attribution?.toReservedKeys().orEmpty(),
                )
            } finally {
                lock.unlock()
            }
        }

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest =
        lease.withOpenOperation {
            FileStoreUtil.validateManagedDirectory(approvalsDir, "approvals")
            validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)

            transition.commentOrNull()?.let {
                require(it.length <= MAX_COMMENT_LENGTH) { "Comment exceeds maximum length of $MAX_COMMENT_LENGTH" }
            }

            transition.decidedByOrNull()?.let { decidedBy ->
                validateIdField(decidedBy, "decidedBy", MAX_ID_LENGTH)
                SafeActorIdPolicy.validateActorId(decidedBy, "decidedBy")
            }

            val lock = getLock(approvalId)
            lock.lock()
            try {
                val record = readRecord(approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
                val req = record.request.toDomain()

                if (req.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)

                val now = clock.instant()
                val nextStatus = resolveNextStatus(req, transition, now)

                val updated =
                    req.copy(
                        status = nextStatus,
                        version = incrementVersion(approvalId, req.version),
                        decidedAt = now,
                        decidedBy = transition.decidedByOrNull(),
                        decisionComment = transition.commentOrNull(),
                    )

                // Attribution travels with the record, so this rewrite cannot drop it.
                writeRecord(approvalId, record.copy(request = updated.toPersistedV1()))
                return updated
            } finally {
                lock.unlock()
            }
        }

    override suspend fun consumeApprovedOrReplay(
        approvalId: String,
        expectedVersion: Long,
        presentedTokenDigest: Sha256Digest,
        consumedBy: String,
    ): ApprovalConsumptionReceipt =
        lease.withOpenOperation {
            FileStoreUtil.validateManagedDirectory(approvalsDir, "approvals")
            validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
            validateIdField(consumedBy, "consumedBy", MAX_ID_LENGTH)
            SafeActorIdPolicy.validateActorId(consumedBy, "consumedBy")

            val lock = getLock(approvalId)
            lock.lock()
            try {
                val record = readRecord(approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
                val req = record.request.toDomain()

                if (req.status != ApprovalStatus.APPROVED) throw ApprovalStoreNotConsumableException(approvalId)

                if (!tokenDigestsMatch(presentedTokenDigest, req.binding.approvalTokenDigest)) {
                    throw ApprovalStoreTokenRejectedException(approvalId)
                }

                if (req.consumedAt == null && req.consumedBy == null) {
                    return consumeFreshApproval(approvalId, expectedVersion, consumedBy, record)
                }

                return consumeReplayApproval(approvalId, expectedVersion, consumedBy, req)
            } finally {
                lock.unlock()
            }
        }

    private fun consumeFreshApproval(
        approvalId: String,
        expectedVersion: Long,
        consumedBy: String,
        record: PersistedApprovalRecord,
    ): ApprovalConsumptionReceipt {
        val req = record.request.toDomain()
        if (req.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)

        val now = clock.instant()
        if (now >= req.expiresAt) throw ApprovalStoreNotConsumableException(approvalId)

        val updated =
            req.copy(
                consumedBy = consumedBy,
                consumedAt = now,
                version = incrementVersion(approvalId, req.version),
            )

        // Same rewrite rule as transition: the governed snapshot is carried forward, not rebuilt.
        writeRecord(approvalId, record.copy(request = updated.toPersistedV1()))
        return ApprovalConsumptionReceipt(request = updated, replayed = false)
    }

    private fun consumeReplayApproval(
        approvalId: String,
        expectedVersion: Long,
        consumedBy: String,
        req: ApprovalRequest,
    ): ApprovalConsumptionReceipt {
        if (req.consumedAt == null || req.consumedBy == null) {
            throw ApprovalStoreNotConsumableException(approvalId)
        }
        if (req.consumedBy != consumedBy) throw ApprovalStoreNotConsumableException(approvalId)

        val replayVersion =
            try {
                Math.addExact(expectedVersion, 1L)
            } catch (_: ArithmeticException) {
                throw ApprovalStoreConflictException(approvalId)
            }
        if (req.version != replayVersion) throw ApprovalStoreConflictException(approvalId)

        return ApprovalConsumptionReceipt(request = req, replayed = true)
    }

    // ── Internal helpers ──────────────────────────────────────────

    private fun resolveNextStatus(
        current: ApprovalRequest,
        transition: ApprovalTransition,
        now: Instant,
    ): ApprovalStatus {
        return when (current.status) {
            ApprovalStatus.PENDING -> {
                if (now >= current.expiresAt) {
                    if (transition is ApprovalTransition.Timeout) {
                        return ApprovalStatus.TIMED_OUT
                    }
                    throw IllegalApprovalTransitionException(
                        current.approvalId,
                        current.status,
                        transition.targetStatus(),
                        "approval has expired at ${current.expiresAt}",
                    )
                }
                when (transition) {
                    is ApprovalTransition.Approve -> {
                        ApprovalStatus.APPROVED
                    }

                    is ApprovalTransition.Deny -> {
                        ApprovalStatus.DENIED
                    }

                    is ApprovalTransition.Timeout -> {
                        throw IllegalApprovalTransitionException(
                            current.approvalId,
                            current.status,
                            transition.targetStatus(),
                            "Cannot time out approval before expiry at ${current.expiresAt}",
                        )
                    }
                }
            }

            ApprovalStatus.APPROVED -> {
                throw IllegalApprovalTransitionException(
                    current.approvalId,
                    current.status,
                    transition.targetStatus(),
                    "approval already granted",
                )
            }

            ApprovalStatus.DENIED -> {
                throw IllegalApprovalTransitionException(
                    current.approvalId,
                    current.status,
                    transition.targetStatus(),
                    "approval already denied",
                )
            }

            ApprovalStatus.TIMED_OUT -> {
                throw IllegalApprovalTransitionException(
                    current.approvalId,
                    current.status,
                    transition.targetStatus(),
                    "approval already timed out",
                )
            }
        }
    }
}

/** @see FileApprovalStore */
private const val ERROR_CORRUPTED_RECORD = "approval-record-corrupted"

/** The one durable-state failure shape for this store; keeps call sites throw-free. */
private fun corrupted(cause: Exception): Nothing = throw FileStoreCorruptionException(ERROR_CORRUPTED_RECORD, cause)

// ── File-level helpers (outside the class so its function budget is unchanged) ──

private fun incrementVersion(
    approvalId: String,
    version: Long,
): Long =
    try {
        Math.addExact(version, 1L)
    } catch (_: ArithmeticException) {
        throw ApprovalStoreConflictException(approvalId)
    }

private fun tokenDigestsMatch(
    presentedTokenDigest: Sha256Digest,
    storedTokenDigest: Sha256Digest,
): Boolean =
    MessageDigest.isEqual(
        presentedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
        storedTokenDigest.value.toByteArray(StandardCharsets.US_ASCII),
    )

private fun ApprovalTransition.decidedByOrNull(): String? =
    when (this) {
        is ApprovalTransition.Approve -> decidedBy
        is ApprovalTransition.Deny -> decidedBy
        is ApprovalTransition.Timeout -> null
    }

private fun ApprovalTransition.commentOrNull(): String? =
    when (this) {
        is ApprovalTransition.Approve -> comment
        is ApprovalTransition.Deny -> comment
        is ApprovalTransition.Timeout -> null
    }

private fun validateIdField(
    value: String,
    fieldName: String,
    maxLength: Int,
): String {
    val trimmed = value.trim()
    require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
    require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
    require(trimmed.length <= maxLength) { "$fieldName exceeds maximum length of $maxLength" }
    require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
    return trimmed
}
