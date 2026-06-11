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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
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

    private fun recordKeyDigest(approvalId: String): String =
        FileStoreSha256.digest(RECORD_TYPE, approvalId)

    private fun getLock(approvalId: String): ReentrantLock =
        locks.computeIfAbsent(approvalId) { ReentrantLock() }

    // ── Read / write helpers ──────────────────────────────────────

    private fun readCurrent(approvalId: String): PersistedApprovalRequestV1? {
        lease.requireOpen()
        val path = storePath(approvalId)
        if (!path.exists()) return null
        val rkd = recordKeyDigest(approvalId)
        val plaintext: ByteArray = try {
            FileStoreUtil.readAndDecrypt(path, RECORD_TYPE, rkd, encryptionKey, keyId)
        } catch (e: FileStoreCorruptionException) {
            throw FileStoreCorruptionException("approval-record-corrupted", e)
        } catch (e: Exception) {
            throw FileStoreCorruptionException("approval-record-corrupted", e)
        }
        val json = String(plaintext, Charsets.UTF_8)
        val dto = try {
            PersistedApprovalRequestV1.fromJson(json)
        } catch (e: Exception) {
            throw FileStoreCorruptionException("approval-record-corrupted", e)
        }
        // Bind decoded ID back to filename digest
        val expectedDigest = FileStoreSha256.digest(RECORD_TYPE, dto.approvalId)
        require(expectedDigest == rkd) { "approval-id-filename-digest-mismatch" }
        return dto
    }

    private fun writeCurrent(approvalId: String, dto: PersistedApprovalRequestV1) {
        lease.requireOpen()
        val json = dto.toJson()
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
     * - Decoded approval ID matches filename digest
     *
     * @throws FileStoreCorruptionException if any record fails integrity verification.
     */
    fun verifyAll() {
        if (!approvalsDir.exists() || !approvalsDir.isDirectory()) return
        for (entry in approvalsDir.listDirectoryEntries("*$FILE_EXTENSION")) {
            val fileName = entry.fileName.toString()
            val digestHex = fileName.removeSuffix(FILE_EXTENSION)
            require(digestHex.length == 64 && digestHex.all { it in '0'..'9' || it in 'a'..'f' }) {
                throw FileStoreCorruptionException("approval-invalid-filename")
            }
            FileStoreUtil.validateRegularFile(entry, "approval")
            val plaintext: ByteArray = try {
                FileStoreUtil.readAndDecrypt(entry, RECORD_TYPE, digestHex, encryptionKey, keyId)
            } catch (e: FileStoreCorruptionException) {
                throw FileStoreCorruptionException("approval-record-corrupted", e)
            } catch (e: Exception) {
                throw FileStoreCorruptionException("approval-record-corrupted", e)
            }
            // Parse DTO and validate schema version + domain conversion
            val dto = try {
                PersistedApprovalRequestV1.fromJson(String(plaintext, Charsets.UTF_8))
            } catch (e: Exception) {
                throw FileStoreCorruptionException("approval-record-corrupted", e)
            }
            require(dto.schemaVersion == 1) {
                throw FileStoreUnsupportedFormatException("unsupported-approval-schema-version: ${dto.schemaVersion}")
            }
            // Validate filename digest matches DTO ID
            val expectedDigest = FileStoreSha256.digest(RECORD_TYPE, dto.approvalId)
            require(expectedDigest == digestHex) {
                throw FileStoreCorruptionException("approval-id-filename-digest-mismatch")
            }
            // Domain conversion must succeed
            try {
                dto.toDomain()
            } catch (e: Exception) {
                throw FileStoreCorruptionException("approval-domain-conversion-failed", e)
            }
        }
    }

    // ── ApprovalStore SPI ─────────────────────────────────────────

    override suspend fun create(request: ApprovalRequest): ApprovalRequest {
        lease.requireOpen()
        // Version
        require(request.version == 0L) { "Initial approval version must be 0, got ${request.version}" }

        // Status
        require(request.status == ApprovalStatus.PENDING) { "Initial approval status must be PENDING, got ${request.status}" }

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
        require(request.expiresAt > now) { "expiresAt must be in the future, got $now for expiry ${request.expiresAt}" }
        require(request.expiresAt > request.requestedAt) { "expiresAt must be after requestedAt" }

        // requestedAt must not be in the future
        require(request.requestedAt <= now) { "requestedAt must not be in the future, got ${request.requestedAt} for now $now" }
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

            val dto = request.toPersistedV1()
            writeCurrent(request.approvalId, dto)
            return request
        } finally {
            lock.unlock()
        }
    }

    override suspend fun get(approvalId: String): ApprovalRequest? {
        lease.requireOpen()
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
        val lock = getLock(approvalId)
        lock.lock()
        try {
            val dto = readCurrent(approvalId) ?: return null
            return dto.toDomain()
        } finally {
            lock.unlock()
        }
    }

    override suspend fun transition(
        approvalId: String,
        expectedVersion: Long,
        transition: ApprovalTransition,
    ): ApprovalRequest {
        lease.requireOpen()
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)

        // Validate comment length
        when (transition) {
            is ApprovalTransition.Approve -> transition.comment?.let {
                require(it.length <= MAX_COMMENT_LENGTH) { "Comment exceeds maximum length of $MAX_COMMENT_LENGTH" }
            }
            is ApprovalTransition.Deny -> transition.comment?.let {
                require(it.length <= MAX_COMMENT_LENGTH) { "Comment exceeds maximum length of $MAX_COMMENT_LENGTH" }
            }
            is ApprovalTransition.Timeout -> {}
        }

        // Validate decidedBy for non-timeout transitions
        when (transition) {
            is ApprovalTransition.Approve -> {
                validateIdField(transition.decidedBy, "decidedBy", MAX_ID_LENGTH)
                SafeActorIdPolicy.validateActorId(transition.decidedBy, "decidedBy")
            }
            is ApprovalTransition.Deny -> {
                validateIdField(transition.decidedBy, "decidedBy", MAX_ID_LENGTH)
                SafeActorIdPolicy.validateActorId(transition.decidedBy, "decidedBy")
            }
            is ApprovalTransition.Timeout -> {}
        }

        val lock = getLock(approvalId)
        lock.lock()
        try {
            val dto = readCurrent(approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
            val req = dto.toDomain()

            if (req.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)

            val now = clock.instant()
            val nextStatus = resolveNextStatus(req, transition, now)

            val updated = req.copy(
                status = nextStatus,
                version = incrementVersion(approvalId, req.version),
                decidedAt = when (transition) {
                    is ApprovalTransition.Approve -> now
                    is ApprovalTransition.Deny -> now
                    is ApprovalTransition.Timeout -> now
                },
                decidedBy = when (transition) {
                    is ApprovalTransition.Approve -> transition.decidedBy
                    is ApprovalTransition.Deny -> transition.decidedBy
                    is ApprovalTransition.Timeout -> null
                },
                decisionComment = when (transition) {
                    is ApprovalTransition.Approve -> transition.comment
                    is ApprovalTransition.Deny -> transition.comment
                    is ApprovalTransition.Timeout -> null
                },
            )

            writeCurrent(approvalId, updated.toPersistedV1())
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
    ): ApprovalConsumptionReceipt {
        lease.requireOpen()
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
        validateIdField(consumedBy, "consumedBy", MAX_ID_LENGTH)
        SafeActorIdPolicy.validateActorId(consumedBy, "consumedBy")

        val lock = getLock(approvalId)
        lock.lock()
        try {
            val dto = readCurrent(approvalId) ?: throw ApprovalStoreNotFoundException(approvalId)
            val req = dto.toDomain()

            if (req.status != ApprovalStatus.APPROVED) throw ApprovalStoreNotConsumableException(approvalId)

            if (!tokenDigestsMatch(presentedTokenDigest, req.binding.approvalTokenDigest)) {
                throw ApprovalStoreTokenRejectedException(approvalId)
            }

            if (req.consumedAt == null && req.consumedBy == null) {
                // Fresh consumption
                if (req.version != expectedVersion) throw ApprovalStoreConflictException(approvalId)

                val now = clock.instant()
                if (now >= req.expiresAt) throw ApprovalStoreNotConsumableException(approvalId)

                val updated = req.copy(
                    consumedBy = consumedBy,
                    consumedAt = now,
                    version = incrementVersion(approvalId, req.version),
                )

                writeCurrent(approvalId, updated.toPersistedV1())
                return ApprovalConsumptionReceipt(request = updated, replayed = false)
            } else {
                // Replay path
                if (req.consumedAt == null || req.consumedBy == null) {
                    throw ApprovalStoreNotConsumableException(approvalId)
                }
                if (req.consumedBy != consumedBy) throw ApprovalStoreNotConsumableException(approvalId)

                val replayVersion = try {
                    Math.addExact(expectedVersion, 1L)
                } catch (_: ArithmeticException) {
                    throw ApprovalStoreConflictException(approvalId)
                }
                if (req.version != replayVersion) throw ApprovalStoreConflictException(approvalId)

                return ApprovalConsumptionReceipt(request = req, replayed = true)
            }
        } finally {
            lock.unlock()
        }
    }

    // ── Internal helpers ──────────────────────────────────────────

    private fun incrementVersion(approvalId: String, version: Long): Long =
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
                        current.approvalId, current.status, transition.targetStatus(),
                        "approval has expired at ${current.expiresAt}",
                    )
                }
                when (transition) {
                    is ApprovalTransition.Approve -> ApprovalStatus.APPROVED
                    is ApprovalTransition.Deny -> ApprovalStatus.DENIED
                    is ApprovalTransition.Timeout -> {
                        throw IllegalApprovalTransitionException(
                            current.approvalId, current.status, transition.targetStatus(),
                            "Cannot time out approval before expiry at ${current.expiresAt}",
                        )
                    }
                }
            }
            ApprovalStatus.APPROVED -> throw IllegalApprovalTransitionException(
                current.approvalId, current.status,
                transition.targetStatus(),
                "approval already granted",
            )
            ApprovalStatus.DENIED -> throw IllegalApprovalTransitionException(
                current.approvalId, current.status,
                transition.targetStatus(),
                "approval already denied",
            )
            ApprovalStatus.TIMED_OUT -> throw IllegalApprovalTransitionException(
                current.approvalId, current.status,
                transition.targetStatus(),
                "approval already timed out",
            )
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
