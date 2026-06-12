package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.security.audit.AuditStore
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import javax.crypto.SecretKey
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.notExists

/**
 * Bundle of all three sovereign file-backed stores, sharing a single root directory
 * and an exclusive file lock.
 *
 * On construction, [open] validates the root directory, acquires an exclusive lock
 * on `.tramai.lock`, creates or validates subdirectories (`approvals/`, `continuations/`,
 * `audit/`), initialises or validates the `manifest.json`, optionally verifies all
 * existing records, and returns a fully wired [AutoCloseable] handle.
 *
 * ## Thread safety
 *
 * The single exclusive lock guarantees mutual exclusion per root directory.
 * Each store instance is thread-safe internally via per-record locks.
 *
 * ## Cleanup
 *
 * Call [close] to release the lock and underlying file handle. After close the
 * store instances MUST NOT be used — every public method checks the shared
 * [FileStoreLease] and throws [IllegalStateException] if closed.
 *
 * @property approvalStore Store for [ApprovalRequest] records.
 * @property approvalContinuationStore Store for [ApprovalContinuation] records.
 * @property auditStore Store for [AuditEvent] records.
 */
class FileBackedSovereignStores private constructor(
    val approvalStore: ApprovalStore,
    val approvalContinuationStore: ApprovalContinuationStore,
    val auditStore: AuditStore,
    private val lockFile: RandomAccessFile,
    private val lock: FileLock,
    private val rootDir: Path,
    private val lease: FileStoreLease,
) : AutoCloseable {

    companion object {
        private const val MAX_KEY_ID_LENGTH = 128
        private val SAFE_KEY_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")

        /**
         * Opens (or initialises) the store bundle at [configuration.rootDirectory].
         *
         * Validation sequence:
         * 1. Create root directory with 0700 permissions if absent.
         * 2. Verify root is a directory, not a symlink, with 0700 permissions.
         * 3. Acquire exclusive lock on `.tramai.lock` (rejects if already held).
         * 4. Create subdirectories (`approvals/`, `continuations/`, `audit/`) with 0700 permissions.
         * 5. Validate or create `manifest.json` (format version must be 1).
         * 6. Resolve and validate the encryption key (must be AES, 256-bit).
         * 7. Construct and wire the three file-backed stores with a shared [FileStoreLease].
         * 8. If [FileBackedStoreConfiguration.verifyOnOpen] is true, verify all existing records.
         *
         * On any failure the lock is released before the exception propagates.
         */
        fun open(configuration: FileBackedStoreConfiguration): FileBackedSovereignStores {
            val root = configuration.rootDirectory.toAbsolutePath().normalize()
            validateActiveKeyId(configuration.encryption.activeKeyId)

            // ── 1. Create root directory with strict permissions if missing ──
            if (root.notExists()) {
                Files.createDirectories(root, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------"),
                ))
            }

            // ── 2. Validate root directory ──
            require(Files.isDirectory(root)) { "root-not-directory" }
            require(!Files.isSymbolicLink(root)) { "root-symlink-rejected" }

            val rootPerms = Files.getPosixFilePermissions(root)
            val expectedRootPerms = PosixFilePermissions.fromString("rwx------")
            require(rootPerms == expectedRootPerms) { "root-permission-denied" }

            // ── 3. Acquire exclusive lock on .tramai.lock ──
            val lockFilePath = root.resolve(".tramai.lock")
            // Reject symlink for lock file
            require(!lockFilePath.isSymbolicLink()) {
                throw FileStorePermissionException("lock-file-symlink-rejected")
            }
            // Ensure .tramai.lock exists with 0600 permissions
            if (!lockFilePath.exists()) {
                Files.createFile(lockFilePath, PosixFilePermissions.asFileAttribute(FILE_PERMS_0600))
            } else {
                FileStoreUtil.validateRegularFile(lockFilePath, "lock-file")
            }
            var lockFile: RandomAccessFile? = null
            var fileLock: FileLock? = null
            try {
                lockFile = RandomAccessFile(lockFilePath.toFile(), "rw")
                fileLock = lockFile.channel.tryLock()
                require(fileLock != null) {
                    throw FileStoreLockUnavailableException("tramai-lock-unavailable")
                }
            } catch (e: Exception) {
                // Close the file handle on any acquisition failure
                try { lockFile?.close() } catch (_: Exception) {}
                throw e
            }
            // After successful acquisition, both are guaranteed non-null
            val lockFileNonNull = lockFile!!
            val fileLockNonNull = fileLock!!

            try {
                // Reject symlink and validate permissions for manifest
                val manifestPath = root.resolve("manifest.json")
                // Check symlink BEFORE existence — dangling symlinks appear non-existent to exists()
                if (manifestPath.isSymbolicLink()) {
                    throw FileStorePermissionException("manifest-symlink-rejected")
                }
                if (Files.exists(manifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    FileStoreUtil.validateRegularFile(manifestPath, "manifest")
                }

                // ── 4. Create subdirectories with strict permissions ──
                val subdirs = listOf("approvals", "continuations", "audit")
                for (dir in subdirs) {
                    val path = root.resolve(dir)
                    if (path.notExists()) {
                        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------"),
                        ))
                    }
                    require(!path.isSymbolicLink()) {
                        throw FileStorePermissionException("$dir-symlink-rejected")
                    }
                    val dirPerms = Files.getPosixFilePermissions(path)
                    check(dirPerms == PosixFilePermissions.fromString("rwx------")) {
                        throw FileStorePermissionException("$dir-permission-denied")
                    }
                }

                // ── 5. Validate or create manifest.json ──
                if (Files.exists(manifestPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    FileStoreUtil.validateRegularFile(manifestPath, "manifest")
                    val manifestJson = FileStoreUtil.boundedReadText(manifestPath)
                    val manifest = StoreManifestV1.fromJson(manifestJson)
                    manifest.validateManifest()
                } else {
                    val manifest = StoreManifestV1(
                        formatVersion = 1,
                        module = "tramai-persistence-file",
                        createdAt = Instant.now().toString(),
                    )
                    // Atomic create with immediate 0600 permissions
                    FileChannel.open(
                        manifestPath,
                        setOf(
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.DSYNC,
                        ),
                        PosixFilePermissions.asFileAttribute(FILE_PERMS_0600),
                    ).use { channel ->
                        val bytes = manifest.toJson().toByteArray(Charsets.UTF_8)
                        val buffer = java.nio.ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) {
                            channel.write(buffer)
                        }
                        channel.force(true)
                    }
                }

                // ── 6. Resolve and validate encryption key ──
                val key = configuration.encryption.keyProvider.resolve(configuration.encryption.activeKeyId)
                validateEncryptionKey(key)

                // Shared lease for post-close guarding
                val lease = FileStoreLease()

                val fileBackedApprovalStore = FileApprovalStore(root, key, configuration, lease)
                val fileBackedContinuationStore = FileApprovalContinuationStore(root, key, configuration, lease)
                val fileBackedAuditStore = FileAuditStore(root, key, configuration, lease)

                // ── 7. If verifyOnOpen, verify all records ──
                if (configuration.verifyOnOpen) {
                    fileBackedApprovalStore.verifyAll()
                    fileBackedContinuationStore.verifyAll()
                    fileBackedAuditStore.verifyAll()
                }

                return FileBackedSovereignStores(
                    approvalStore = fileBackedApprovalStore,
                    approvalContinuationStore = fileBackedContinuationStore,
                    auditStore = fileBackedAuditStore,
                    lockFile = lockFileNonNull,
                    lock = fileLockNonNull,
                    rootDir = root,
                    lease = lease,
                )
            } catch (e: Exception) {
                // Clean up lock on failure
                try { fileLockNonNull.close() } catch (_: Exception) {}
                try { lockFileNonNull.close() } catch (_: Exception) {}
                throw e
            }
        }

        /**
         * Validates that the encryption key meets the AES-256 requirements.
         * @throws FileStoreConfigurationException on invalid key.
         */
        private fun validateEncryptionKey(key: SecretKey) {
            require(key.algorithm == "AES") {
                throw FileStoreConfigurationException("key-algorithm-mismatch")
            }
            require(key.encoded.size == 32) {
                throw FileStoreConfigurationException("key-size-mismatch")
            }
        }

        /**
         * Validates that the activeKeyId is non-blank, bounded, and has a safe character pattern.
         * @throws FileStoreConfigurationException on invalid key ID.
         */
        private fun validateActiveKeyId(activeKeyId: String) {
            require(activeKeyId.isNotBlank()) {
                throw FileStoreConfigurationException("active-key-id-blank")
            }
            require(activeKeyId.length <= MAX_KEY_ID_LENGTH) {
                throw FileStoreConfigurationException("active-key-id-too-long")
            }
            require(SAFE_KEY_ID.matches(activeKeyId)) {
                throw FileStoreConfigurationException("active-key-id-unsafe-pattern")
            }
        }
    }

    /**
     * Releases the exclusive file lock and closes the underlying channel.
     * Marks the lease as closed — any subsequent store operation
     * will throw [IllegalStateException].
     *
     * Safe to call multiple times — subsequent invocations are no-ops.
     */
    override fun close() {
        lease.close()
        try {
            lock.release()
        } catch (_: Exception) {
            // already released or channel closed
        }
        try {
            lockFile.close()
        } catch (_: Exception) {
            // already closed
        }
    }
}
