package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStatus
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ClaimedApprovalContinuation
import dev.tramai.core.approval.SafeActorIdPolicy
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.approval.Sha256Digest
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import dev.tramai.core.exception.ApprovalContinuationNotCompletableException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
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
import kotlin.io.path.listDirectoryEntries

/**
 * File-backed [ApprovalContinuationStore] implementation using encrypted atomic file persistence
 * with exactly-once claim semantics.
 *
 * Each continuation record is stored as a single encrypted file under
 * `{root}/continuations/<sha256("approval-continuation:" + approvalId)>.tram.enc`.
 *
 * The persisted record pairs continuation metadata with raw sensitive arguments.
 * On [claimForExecution], the arguments are atomically released exactly once:
 * the record is re-encrypted with `arguments = null` and the raw arguments are
 * returned via [ClaimedApprovalContinuation]. Subsequent claim attempts see no
 * arguments and are rejected.
 *
 * ## Thread safety
 * Reads, mutations, and writes are serialised per approval ID via [ReentrantLock]
 * to ensure atomic read-modify-write semantics.
 *
 * ## State machine
 * ```
 * PENDING (arguments present)
 *   → claimForExecution → CLAIMED (arguments = null, exactly once)
 *   → expire (only if elapsed) → EXPIRED
 *   → cancel → CANCELLED
 *
 * CLAIMED (arguments already released)
 *   → complete → COMPLETED
 *   → findStaleClaimed + forceCancelClaimed → CANCELLED_UNCERTAIN (with recovery metadata)
 *
 * CLAIMED must NEVER lazy-expire.
 * CLAIMED must NEVER use ordinary cancel.
 *
 * COMPLETED / EXPIRED / CANCELLED / CANCELLED_UNCERTAIN → terminal
 * ```
 */
class FileApprovalContinuationStore internal constructor(
    root: Path,
    key: SecretKey,
    configuration: FileBackedStoreConfiguration,
    private val lease: FileStoreLease,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalContinuationStore {

    companion object {
        private const val RECORD_TYPE = "approval-continuation"
        private const val CONTINUATIONS_DIR = "continuations"
        private const val FILE_EXTENSION = ".tram.enc"
        private const val MAX_ID_LENGTH = 256
        private const val MAX_STALE_LIMIT = 100
        private val SAFE_REASON_CODE = Regex("[a-z0-9][a-z0-9._:-]{0,63}")
    }

    private val continuationsDir: Path = root.resolve(CONTINUATIONS_DIR)
    private val keyId: String = configuration.encryption.activeKeyId
    private val encryptionKey: SecretKey = key
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val maxContinuationTtl: Duration = Duration.ofMinutes(15)

    // ── Path helpers ──────────────────────────────────────────────────

    private fun storePath(approvalId: String): Path {
        val digest = FileStoreSha256.digest(RECORD_TYPE, approvalId)
        return continuationsDir.resolve("$digest$FILE_EXTENSION")
    }

    private fun recordKeyDigest(approvalId: String): String =
        FileStoreSha256.digest(RECORD_TYPE, approvalId)

    private fun getLock(approvalId: String): ReentrantLock =
        locks.computeIfAbsent(approvalId) { ReentrantLock() }

    // ── Read / write helpers ─────────────────────────────────────────

    private fun readCurrent(approvalId: String): PersistedApprovalContinuationRecordV1? {
        lease.requireOpen()
        val path = storePath(approvalId)
        if (!path.exists()) return null
        val rkd = recordKeyDigest(approvalId)
        val plaintext: ByteArray = try {
            FileStoreUtil.readAndDecrypt(path, RECORD_TYPE, rkd, encryptionKey, keyId)
        } catch (e: FileStoreCorruptionException) {
            throw FileStoreCorruptionException("continuation-record-corrupted", e)
        } catch (e: Exception) {
            throw FileStoreCorruptionException("continuation-record-corrupted", e)
        }
        val json = String(plaintext, Charsets.UTF_8)
        val record = try {
            PersistedApprovalContinuationRecordV1.fromJson(json)
        } catch (e: Exception) {
            throw FileStoreCorruptionException("continuation-record-corrupted", e)
        }
        // Bind decoded ID back to filename digest
        val expectedDigest = FileStoreSha256.digest(RECORD_TYPE, record.continuation.approvalId)
        require(expectedDigest == rkd) { "continuation-id-filename-digest-mismatch" }
        return record
    }

    private fun writeCurrent(approvalId: String, dto: PersistedApprovalContinuationRecordV1) {
        lease.requireOpen()
        val json = dto.toJson()
        val path = storePath(approvalId)
        val rkd = recordKeyDigest(approvalId)
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        FileStoreUtil.atomicEncryptWrite(path, RECORD_TYPE, rkd, keyId, encryptionKey, jsonBytes)
    }

    // ── verifyAll ─────────────────────────────────────────────────────

    /**
     * Verifies all existing records in the continuations subdirectory.
     *
     * Validates:
     * - File is a regular file (not a symlink) with 0600 permissions
     * - Filename is valid hex digest
     * - Envelope decrypts with correct key and digest
     * - Parsed DTO schema version is supported
     * - DTO ID matches filename digest
     * - Domain conversion succeeds
     *
     * @throws FileStoreCorruptionException if any record fails integrity verification.
     */
    fun verifyAll() {
        if (!continuationsDir.exists() || !continuationsDir.isDirectory()) return
        for (entry in continuationsDir.listDirectoryEntries("*$FILE_EXTENSION")) {
            val fileName = entry.fileName.toString()
            val digestHex = fileName.removeSuffix(FILE_EXTENSION)
            require(digestHex.length == 64 && digestHex.all { it in '0'..'9' || it in 'a'..'f' }) {
                throw FileStoreCorruptionException("continuation-invalid-filename")
            }
            FileStoreUtil.validateRegularFile(entry, "continuation")
            val plaintext: ByteArray = try {
                FileStoreUtil.readAndDecrypt(entry, RECORD_TYPE, digestHex, encryptionKey, keyId)
            } catch (e: FileStoreCorruptionException) {
                throw FileStoreCorruptionException("continuation-record-corrupted", e)
            } catch (e: Exception) {
                throw FileStoreCorruptionException("continuation-record-corrupted", e)
            }
            val record = try {
                PersistedApprovalContinuationRecordV1.fromJson(String(plaintext, Charsets.UTF_8))
            } catch (e: Exception) {
                throw FileStoreCorruptionException("continuation-record-corrupted", e)
            }
            // Validate filename digest matches DTO ID
            val expectedDigest = FileStoreSha256.digest(RECORD_TYPE, record.continuation.approvalId)
            require(expectedDigest == digestHex) {
                throw FileStoreCorruptionException("continuation-id-filename-digest-mismatch")
            }
            // Domain conversion must succeed
            try {
                record.toDomain()
            } catch (e: Exception) {
                throw FileStoreCorruptionException("continuation-domain-conversion-failed", e)
            }
        }
    }

    // ── Version increment ────────────────────────────────────────────

    private fun incrementVersion(approvalId: String, version: Long): Long =
        try {
            Math.addExact(version, 1L)
        } catch (_: ArithmeticException) {
            throw ApprovalContinuationConflictException(approvalId)
        }

    // ── ID validation ────────────────────────────────────────────────

    private fun validateIdField(value: String, fieldName: String, maxLength: Int): String {
        val trimmed = value.trim()
        require(trimmed.isNotBlank()) { "$fieldName must not be blank" }
        require(trimmed.none { it.isISOControl() }) { "$fieldName must not contain control characters" }
        require(trimmed.length <= maxLength) { "$fieldName exceeds maximum length of $maxLength" }
        require(trimmed == value) { "$fieldName must not contain surrounding whitespace" }
        return trimmed
    }

    // ── PENDING-only lazy expiry ─────────────────────────────────────

    /**
     * Checks whether a **PENDING** continuation has passed its [approvalExpiresAt].
     * Only applies to PENDING — CLAIMED must never lazily expire (P1-3).
     *
     * Returns the (possibly updated) record and a boolean indicating whether
     * expiry actually occurred.
     */
    private fun expireIfElapsed(
        approvalId: String,
        record: PersistedApprovalContinuationRecordV1,
        now: Instant,
    ): Pair<PersistedApprovalContinuationRecordV1, Boolean> {
        val c = record.continuation
        val status = try {
            ApprovalContinuationStatus.valueOf(c.status)
        } catch (_: Exception) {
            return record to false
        }
        // Only PENDING can lazy-expire — CLAIMED must not expire automatically
        if (status != ApprovalContinuationStatus.PENDING) {
            return record to false
        }
        val expiresAt = try {
            Instant.parse(c.approvalExpiresAt)
        } catch (_: Exception) {
            return record to false
        }
        if (now < expiresAt) return record to false

        val newVersion = incrementVersion(approvalId, c.version)
        val updatedContinuation = c.copy(
            status = ApprovalContinuationStatus.EXPIRED.name,
            version = newVersion,
        )
        val updatedRecord = PersistedApprovalContinuationRecordV1(
            continuation = updatedContinuation,
            arguments = null,
        )
        return updatedRecord to true
    }

    // ── Arguments digest verification ────────────────────────────────

    private fun computeArgumentsDigest(arguments: SensitiveToolArguments): Sha256Digest {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest(arguments.reveal().toByteArray(Charsets.UTF_8))
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return Sha256Digest.of("sha256:$hex")
    }

    // ══════════════════════════════════════════════════════════════════
    // ApprovalContinuationStore SPI
    // ══════════════════════════════════════════════════════════════════

    override suspend fun create(
        continuation: ApprovalContinuation,
        arguments: SensitiveToolArguments,
    ): ApprovalContinuation {
        lease.requireOpen()
        // ── Field validation ──
        validateIdField(continuation.approvalId, "approvalId", MAX_ID_LENGTH)
        validateIdField(continuation.workflowRunId, "workflowRunId", MAX_ID_LENGTH)
        validateIdField(continuation.correlationId, "correlationId", MAX_ID_LENGTH)
        validateIdField(continuation.toolCallId, "toolCallId", MAX_ID_LENGTH)
        validateIdField(continuation.toolName, "toolName", MAX_ID_LENGTH)
        validateIdField(continuation.policyVersion, "policyVersion", MAX_ID_LENGTH)

        // ── Version and status invariants ──
        require(continuation.version == 0L) {
            "Initial continuation version must be 0, got ${continuation.version}"
        }
        require(continuation.status == ApprovalContinuationStatus.PENDING) {
            "Initial continuation status must be PENDING, got ${continuation.status}"
        }
        require(continuation.claimedBy == null) { "Initial continuation must not have claimedBy set" }
        require(continuation.claimedAt == null) { "Initial continuation must not have claimedAt set" }
        require(continuation.completedAt == null) { "Initial continuation must not have completedAt set" }
        require(continuation.recoveryResolvedBy == null) {
            "Initial continuation must not have recoveryResolvedBy set"
        }
        require(continuation.recoveryResolvedAt == null) {
            "Initial continuation must not have recoveryResolvedAt set"
        }
        require(continuation.recoveryReasonCode == null) {
            "Initial continuation must not have recoveryReasonCode set"
        }

        // ── Temporal invariants ──
        val now = clock.instant()
        require(!continuation.createdAt.isAfter(now)) { "createdAt must not be in the future" }
        require(continuation.approvalExpiresAt.isAfter(now)) { "approvalExpiresAt must be in the future" }
        require(continuation.approvalExpiresAt.isAfter(continuation.createdAt)) {
            "approvalExpiresAt must be after createdAt"
        }

        // ── TTL bound ──
        val ttl = Duration.between(continuation.createdAt, continuation.approvalExpiresAt)
        require(ttl <= maxContinuationTtl) {
            "approvalExpiresAt exceeds maximum continuation TTL of $maxContinuationTtl"
        }

        // ── Arguments digest verification ──
        val actualDigest = computeArgumentsDigest(arguments)
        require(actualDigest == continuation.argumentsDigest) {
            "argumentsDigest does not match arguments"
        }

        val path = storePath(continuation.approvalId)
        val lock = getLock(continuation.approvalId)
        lock.lock()
        try {
            if (path.exists()) throw ApprovalContinuationConflictException(continuation.approvalId)

            val persistedContinuation = continuation.toPersistedV1()
            val record = PersistedApprovalContinuationRecordV1(
                continuation = persistedContinuation,
                arguments = arguments.reveal(),
            )
            writeCurrent(continuation.approvalId, record)
            return continuation
        } finally {
            lock.unlock()
        }
    }

    override suspend fun get(approvalId: String): ApprovalContinuation? {
        lease.requireOpen()
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)

        val lock = getLock(approvalId)
        lock.lock()
        try {
            val record = readCurrent(approvalId) ?: return null

            // Lazy expiry: PENDING only — CLAIMED never lazily expires
            val now = clock.instant()
            val (updated, expired) = expireIfElapsed(approvalId, record, now)
            if (expired) writeCurrent(approvalId, updated)

            // Return metadata only — arguments are NOT exposed via get()
            return updated.continuation.toDomain()
        } finally {
            lock.unlock()
        }
    }

    override suspend fun claimForExecution(
        approvalId: String,
        expectedVersion: Long,
        claimedBy: String,
    ): ClaimedApprovalContinuation {
        lease.requireOpen()
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
        validateIdField(claimedBy, "claimedBy", MAX_ID_LENGTH)
        SafeActorIdPolicy.validateActorId(claimedBy, "claimedBy")

        val lock = getLock(approvalId)
        lock.lock()
        try {
            val record = readCurrent(approvalId)
                ?: throw ApprovalContinuationNotFoundException(approvalId)

            // Lazy expiry for PENDING only
            val now = clock.instant()
            val (normalized, expired) = expireIfElapsed(approvalId, record, now)
            val nc = normalized.continuation
            val status = ApprovalContinuationStatus.valueOf(nc.status)

            if (expired && status == ApprovalContinuationStatus.EXPIRED) {
                writeCurrent(approvalId, normalized)
                throw ApprovalContinuationNotClaimableException(approvalId)
            }

            // Version check
            if (nc.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)

            // Must be PENDING to claim
            if (status != ApprovalContinuationStatus.PENDING) {
                throw ApprovalContinuationNotClaimableException(approvalId)
            }

            // Exactly-once: arguments must be present (never released before)
            val capturedArguments = normalized.arguments
                ?: throw ApprovalContinuationConflictException(approvalId)

            // Transition to CLAIMED and atomically clear arguments
            val newVersion = incrementVersion(approvalId, nc.version)
            val claimedContinuation = nc.copy(
                status = ApprovalContinuationStatus.CLAIMED.name,
                claimedBy = claimedBy,
                claimedAt = now.toString(),
                version = newVersion,
            )
            val claimedRecord = PersistedApprovalContinuationRecordV1(
                continuation = claimedContinuation,
                arguments = null,
            )
            writeCurrent(approvalId, claimedRecord)

            return ClaimedApprovalContinuation(
                continuation = claimedContinuation.toDomain(),
                arguments = SensitiveToolArguments.of(capturedArguments),
            )
        } finally {
            lock.unlock()
        }
    }

    override suspend fun complete(
        approvalId: String,
        expectedVersion: Long,
        completedBy: String,
    ): ApprovalContinuation {
        lease.requireOpen()
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
        validateIdField(completedBy, "completedBy", MAX_ID_LENGTH)
        SafeActorIdPolicy.validateActorId(completedBy, "completedBy")

        val lock = getLock(approvalId)
        lock.lock()
        try {
            val record = readCurrent(approvalId)
                ?: throw ApprovalContinuationNotFoundException(approvalId)
            val c = record.continuation

            if (c.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)

            // Must be CLAIMED
            val status = ApprovalContinuationStatus.valueOf(c.status)
            if (status != ApprovalContinuationStatus.CLAIMED) {
                throw ApprovalContinuationNotCompletableException(approvalId)
            }

            if (c.claimedAt == null || c.claimedBy == null || c.completedAt != null) {
                throw ApprovalContinuationNotCompletableException(approvalId)
            }
            if (c.claimedBy != completedBy) {
                throw ApprovalContinuationNotCompletableException(approvalId)
            }
            if (record.arguments != null) {
                throw ApprovalContinuationNotCompletableException(approvalId)
            }

            val newVersion = incrementVersion(approvalId, c.version)
            val updatedContinuation = c.copy(
                status = ApprovalContinuationStatus.COMPLETED.name,
                completedAt = clock.instant().toString(),
                version = newVersion,
            )
            val updatedRecord = PersistedApprovalContinuationRecordV1(
                continuation = updatedContinuation,
                arguments = null,
            )
            writeCurrent(approvalId, updatedRecord)
            return updatedContinuation.toDomain()
        } finally {
            lock.unlock()
        }
    }

    override suspend fun expire(approvalId: String, expectedVersion: Long): ApprovalContinuation {
        lease.requireOpen()
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)

        val lock = getLock(approvalId)
        lock.lock()
        try {
            val record = readCurrent(approvalId)
                ?: throw ApprovalContinuationNotFoundException(approvalId)
            val c = record.continuation

            if (c.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)

            // Must be PENDING only — CLAIMED must transition to forceCancelClaimed
            val status = ApprovalContinuationStatus.valueOf(c.status)
            if (status != ApprovalContinuationStatus.PENDING) {
                throw ApprovalContinuationConflictException(approvalId)
            }

            val now = clock.instant()
            if (now < Instant.parse(c.approvalExpiresAt)) {
                throw ApprovalContinuationConflictException(approvalId)
            }

            val newVersion = incrementVersion(approvalId, c.version)
            val updatedContinuation = c.copy(
                status = ApprovalContinuationStatus.EXPIRED.name,
                version = newVersion,
            )
            val updatedRecord = PersistedApprovalContinuationRecordV1(
                continuation = updatedContinuation,
                arguments = null,
            )
            writeCurrent(approvalId, updatedRecord)
            return updatedContinuation.toDomain()
        } finally {
            lock.unlock()
        }
    }

    override suspend fun cancel(approvalId: String, expectedVersion: Long): ApprovalContinuation {
        lease.requireOpen()
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)

        val lock = getLock(approvalId)
        lock.lock()
        try {
            val record = readCurrent(approvalId)
                ?: throw ApprovalContinuationNotFoundException(approvalId)

            // Lazy expiry for PENDING only
            val now = clock.instant()
            val (normalized, expired) = expireIfElapsed(approvalId, record, now)
            val nc = normalized.continuation
            val status = ApprovalContinuationStatus.valueOf(nc.status)

            if (expired && status == ApprovalContinuationStatus.EXPIRED) {
                writeCurrent(approvalId, normalized)
                throw ApprovalContinuationConflictException(approvalId)
            }

            if (nc.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)

            // Must be PENDING only — CLAIMED must use forceCancelClaimed → CANCELLED_UNCERTAIN
            if (status != ApprovalContinuationStatus.PENDING) {
                throw ApprovalContinuationConflictException(approvalId)
            }

            val newVersion = incrementVersion(approvalId, nc.version)
            val updatedContinuation = nc.copy(
                status = ApprovalContinuationStatus.CANCELLED.name,
                version = newVersion,
            )
            val updatedRecord = PersistedApprovalContinuationRecordV1(
                continuation = updatedContinuation,
                arguments = null,
            )
            writeCurrent(approvalId, updatedRecord)
            return updatedContinuation.toDomain()
        } finally {
            lock.unlock()
        }
    }

    override suspend fun findStaleClaimed(
        claimedBefore: Instant,
        limit: Int,
    ): List<ApprovalContinuation> {
        lease.requireOpen()
        require(limit in 1..MAX_STALE_LIMIT) { "limit must be between 1 and $MAX_STALE_LIMIT" }
        if (!continuationsDir.exists() || !continuationsDir.isDirectory()) return emptyList()

        val result = mutableListOf<ApprovalContinuation>()

        for (entry in continuationsDir.listDirectoryEntries("*$FILE_EXTENSION")) {
            if (result.size >= limit) break
            val plaintext = try {
                val rkd = entry.fileName.toString().removeSuffix(FILE_EXTENSION)
                FileStoreUtil.readAndDecrypt(entry, RECORD_TYPE, rkd, encryptionKey, keyId)
            } catch (e: FileStoreCorruptionException) {
                // Surface corrupted CLAIMED records — they may represent uncertain side effects
                throw FileStoreCorruptionException("stale-claimed-corrupted-entry", e)
            } catch (_: Exception) {
                continue
            }
            val record = try {
                PersistedApprovalContinuationRecordV1.fromJson(String(plaintext, Charsets.UTF_8))
            } catch (_: Exception) {
                continue
            }
            val c = record.continuation
            val status = try {
                ApprovalContinuationStatus.valueOf(c.status)
            } catch (_: Exception) {
                continue
            }
            val claimedAt = try {
                c.claimedAt?.let { Instant.parse(it) }
            } catch (_: Exception) {
                null
            }

            if (
                status == ApprovalContinuationStatus.CLAIMED &&
                claimedAt != null &&
                !claimedAt.isAfter(claimedBefore)
            ) {
                result.add(c.toDomain())
            }
        }

        return result
            .sortedWith(
                compareBy<ApprovalContinuation> { it.claimedAt!! }
                    .thenBy { it.approvalId },
            )
            .take(limit)
    }

    override suspend fun forceCancelClaimed(
        approvalId: String,
        expectedVersion: Long,
        cancelledBy: String,
        reasonCode: String,
    ): ApprovalContinuation {
        lease.requireOpen()
        validateIdField(approvalId, "approvalId", MAX_ID_LENGTH)
        validateIdField(cancelledBy, "cancelledBy", MAX_ID_LENGTH)
        SafeActorIdPolicy.validateActorId(cancelledBy, "cancelledBy")
        require(SAFE_REASON_CODE.matches(reasonCode)) {
            "reasonCode must match [a-z0-9][a-z0-9._:-]{0,63}"
        }

        val lock = getLock(approvalId)
        lock.lock()
        try {
            val record = readCurrent(approvalId)
                ?: throw ApprovalContinuationNotFoundException(approvalId)
            val c = record.continuation

            if (c.version != expectedVersion) throw ApprovalContinuationConflictException(approvalId)

            // Must be CLAIMED specifically (force-cancel is for stuck CLAIMED records)
            val status = ApprovalContinuationStatus.valueOf(c.status)
            if (status != ApprovalContinuationStatus.CLAIMED) {
                throw ApprovalContinuationNotClaimableException(approvalId)
            }

            // Transition to CANCELLED_UNCERTAIN with recovery metadata
            val now = clock.instant()
            val newVersion = incrementVersion(approvalId, c.version)
            val updatedContinuation = c.copy(
                status = ApprovalContinuationStatus.CANCELLED_UNCERTAIN.name,
                version = newVersion,
                recoveryResolvedBy = cancelledBy,
                recoveryResolvedAt = now.toString(),
                recoveryReasonCode = reasonCode,
            )
            val updatedRecord = PersistedApprovalContinuationRecordV1(
                continuation = updatedContinuation,
                arguments = null,
            )
            writeCurrent(approvalId, updatedRecord)
            return updatedContinuation.toDomain()
        } finally {
            lock.unlock()
        }
    }

    override suspend fun sweepExpired(): Int {
        lease.requireOpen()
        if (!continuationsDir.exists() || !continuationsDir.isDirectory()) return 0
        val now = clock.instant()
        var count = 0

        for (entry in continuationsDir.listDirectoryEntries("*$FILE_EXTENSION")) {
            try {
                val rkd = entry.fileName.toString().removeSuffix(FILE_EXTENSION)
                val plaintext = FileStoreUtil.readAndDecrypt(entry, RECORD_TYPE, rkd, encryptionKey, keyId)
                val record = PersistedApprovalContinuationRecordV1.fromJson(
                    String(plaintext, Charsets.UTF_8),
                )
                val c = record.continuation
                val status = try {
                    ApprovalContinuationStatus.valueOf(c.status)
                } catch (_: Exception) {
                    continue
                }
                val expiresAt = try {
                    Instant.parse(c.approvalExpiresAt)
                } catch (_: Exception) {
                    continue
                }

                // Only transition PENDING past expiry to EXPIRED
                // Never delete expired records — preservation of lifecycle evidence.
                if (status == ApprovalContinuationStatus.PENDING && now >= expiresAt) {
                    val lock = getLock(c.approvalId)
                    lock.lock()
                    try {
                        val plaintext2 = FileStoreUtil.readAndDecrypt(
                            entry, RECORD_TYPE, rkd, encryptionKey, keyId,
                        )
                        val record2 = PersistedApprovalContinuationRecordV1.fromJson(
                            String(plaintext2, Charsets.UTF_8),
                        )
                        val c2 = record2.continuation
                        val status2 = ApprovalContinuationStatus.valueOf(c2.status)
                        if (status2 == ApprovalContinuationStatus.PENDING && now >= Instant.parse(c2.approvalExpiresAt)) {
                            val newVersion = incrementVersion(c2.approvalId, c2.version)
                            val updatedContinuation = c2.copy(
                                status = ApprovalContinuationStatus.EXPIRED.name,
                                version = newVersion,
                            )
                            val updatedRecord = PersistedApprovalContinuationRecordV1(
                                continuation = updatedContinuation,
                                arguments = null,
                            )
                            writeCurrent(c2.approvalId, updatedRecord)
                            count++
                        }
                    } finally {
                        lock.unlock()
                    }
                }
            } catch (_: Exception) {
                // Skip corrupted entries (non-terminal — but surface during verifyAll)
            }
        }

        return count
    }
}
