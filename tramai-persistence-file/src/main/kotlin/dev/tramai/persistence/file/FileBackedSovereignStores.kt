package dev.tramai.persistence.file

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.security.audit.AuditStore
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText

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
 * Each store instance is thread-safe internally.
 *
 * ## Cleanup
 *
 * Call [close] to release the lock and underlying file handle. After close the
 * store instances MUST NOT be used.
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
) : AutoCloseable {

    companion object {
        /**
         * Opens (or initialises) the store bundle at [configuration.rootDirectory].
         *
         * Validation sequence:
         * 1. Create root directory with 0700 permissions if absent.
         * 2. Verify root is a directory, not a symlink, with 0700 permissions.
         * 3. Acquire exclusive lock on `.tramai.lock` (throws [FileStoreLockUnavailableException]
         *    if already held).
         * 4. Create subdirectories (`approvals/`, `continuations/`, `audit/`) with 0700 permissions.
         * 5. Validate or create `manifest.json` (format version must be 1).
         * 6. Resolve the encryption key.
         * 7. Construct and wire the three file-backed store stubs.
         * 8. If [FileBackedStoreConfiguration.verifyOnOpen] is true, verify all existing records.
         *
         * On any failure the lock is released before the exception propagates.
         */
        fun open(configuration: FileBackedStoreConfiguration): FileBackedSovereignStores {
            val root = configuration.rootDirectory.toAbsolutePath().normalize()

            // ── 1. Create root directory with strict permissions if missing ──
            if (root.notExists()) {
                Files.createDirectories(root, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------"),
                ))
            }

            // ── 2. Validate root directory ──
            require(Files.isDirectory(root)) { "Root path is not a directory: $root" }
            require(!Files.isSymbolicLink(root)) { "Root path must not be a symlink: $root" }

            // ── 3. Check POSIX permissions ──
            val perms = Files.getPosixFilePermissions(root)
            require(perms == PosixFilePermissions.fromString("rwx------")) {
                "Root directory permissions must be 0700: $root"
            }

            // ── 4. Acquire exclusive lock on .tramai.lock ──
            val lockFilePath = root.resolve(".tramai.lock")
            val lockFile = RandomAccessFile(lockFilePath.toFile(), "rw")
            val fileLock = lockFile.channel.tryLock()
            require(fileLock != null) {
                throw FileStoreLockUnavailableException("tramai-lock-unavailable")
            }

            try {
                // ── 5. Create subdirectories with strict permissions ──
                val subdirs = listOf("approvals", "continuations", "audit")
                for (dir in subdirs) {
                    val path = root.resolve(dir)
                    if (path.notExists()) {
                        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------"),
                        ))
                    }
                    val dirPerms = Files.getPosixFilePermissions(path)
                    check(dirPerms == PosixFilePermissions.fromString("rwx------")) {
                        throw FileStorePermissionException("directory-permission-denied")
                    }
                    check(!Files.isSymbolicLink(path)) {
                        throw FileStorePermissionException("symlink-rejected")
                    }
                }

                // ── 6. Validate or create manifest.json ──
                val manifestPath = root.resolve("manifest.json")
                if (manifestPath.exists()) {
                    val manifestJson = manifestPath.readText()
                    val manifest = StoreManifestV1.fromJson(manifestJson)
                    require(manifest.formatVersion == 1) {
                        throw FileStoreUnsupportedFormatException("unsupported-format-version")
                    }
                } else {
                    val manifest = StoreManifestV1(createdAt = Instant.now().toString())
                    manifestPath.writeText(manifest.toJson())
                    Files.setPosixFilePermissions(
                        manifestPath,
                        PosixFilePermissions.fromString("rw-------"),
                    )
                }

                // ── 7. Resolve encryption key and create stores ──
                val key = configuration.encryption.keyProvider.resolve(configuration.encryption.activeKeyId)
                val fileBackedApprovalStore = FileApprovalStore(root, key, configuration)
                val fileBackedContinuationStore = FileApprovalContinuationStore(root, key, configuration)
                val fileBackedAuditStore = FileAuditStore(root, key, configuration)

                // ── 8. If verifyOnOpen, validate all records ──
                if (configuration.verifyOnOpen) {
                    fileBackedApprovalStore.verifyAll()
                    fileBackedContinuationStore.verifyAll()
                    fileBackedAuditStore.verifyAll()
                }

                return FileBackedSovereignStores(
                    approvalStore = fileBackedApprovalStore,
                    approvalContinuationStore = fileBackedContinuationStore,
                    auditStore = fileBackedAuditStore,
                    lockFile = lockFile,
                    lock = fileLock,
                    rootDir = root,
                )
            } catch (e: Exception) {
                // Clean up lock on failure
                fileLock.close()
                lockFile.close()
                throw e
            }
        }
    }

    /**
     * Releases the exclusive file lock and closes the underlying `RandomAccessFile`.
     *
     * Safe to call multiple times — subsequent invocations are no-ops.
     * After close, the store instances must not be used.
     */
    override fun close() {
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
