package dev.tramai.persistence.file

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText

/** POSIX permission set for directories (0700). */
internal val DIR_PERMS_0700: Set<PosixFilePermission> =
    PosixFilePermissions.fromString("rwx------").toSet()

/** POSIX permission set for files (0600). */
internal val FILE_PERMS_0600: Set<PosixFilePermission> =
    PosixFilePermissions.fromString("rw-------").toSet()

/**
 * Shared utilities for file-backed stores.
 */
internal object FileStoreUtil {

    private val RANDOM = SecureRandom()

    /** Generate a temporary sibling filename. */
    fun tempSibling(target: Path): Path =
        target.resolveSibling(".${target.fileName}.tmp.${RANDOM.nextInt(Int.MAX_VALUE)}")

    /**
     * Atomically move source to target, then fsync the parent directory.
     */
    fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Files.setPosixFilePermissions(target, FILE_PERMS_0600)
        forceParentDirectory(target.parent)
    }

    /**
     * Encrypt and write a record atomically.
     *
     * - Temporary file created with CREATE_NEW, NOFOLLOW_LINKS, immediate 0600 permissions
     * - Writes complete buffer in a loop
     * - Fsync before atomic move
     * - Uses REPLACE_EXISTING (for approvals/continuations)
     *
     * For audit events (create-only), use [atomicEncryptCreate].
     */
    fun atomicEncryptWrite(
        targetPath: Path,
        recordType: String,
        recordKeyDigest: String,
        keyId: String,
        key: javax.crypto.SecretKey,
        plaintextBytes: ByteArray,
    ) {
        val (nonceB64, ciphertextB64) = AesGcmFileEncryption.encrypt(
            key, recordType, recordKeyDigest, keyId, plaintextBytes,
        )
        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = recordType,
            recordKeyDigest = recordKeyDigest,
            keyId = keyId,
            nonceBase64 = nonceB64,
            ciphertextBase64 = ciphertextB64,
        )
        val envelopeBytes = envelope.toJson().toByteArray(Charsets.UTF_8)
        val temp = tempSibling(targetPath)
        try {
            writeTempFileWith0600(temp, envelopeBytes)
            atomicMove(temp, targetPath)
        } finally {
            try {
                Files.deleteIfExists(temp)
            } catch (_: Exception) {
                // Best-effort cleanup of a temporary sibling after the real write path has completed or failed.
            }
        }
    }

    /**
     * Create a record atomically and immutably (create-only, no replacement).
     *
     * Writes the encrypted envelope directly to the target using CREATE_NEW + DSYNC,
     * not ATOMIC_MOVE. This guarantees create-only semantics because ATOMIC_MOVE
     * with no REPLACE_EXISTING is implementation-specific on some platforms.
     *
     * Suitable for immutable audit events where silent replacement is not acceptable.
     */
    fun atomicEncryptCreate(
        targetPath: Path,
        recordType: String,
        recordKeyDigest: String,
        keyId: String,
        key: javax.crypto.SecretKey,
        plaintextBytes: ByteArray,
    ) {
        val (nonceB64, ciphertextB64) = AesGcmFileEncryption.encrypt(
            key, recordType, recordKeyDigest, keyId, plaintextBytes,
        )
        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = recordType,
            recordKeyDigest = recordKeyDigest,
            keyId = keyId,
            nonceBase64 = nonceB64,
            ciphertextBase64 = ciphertextB64,
        )
        val envelopeBytes = envelope.toJson().toByteArray(Charsets.UTF_8)
        // Write directly to the target using CREATE_NEW (fails if target exists)
        FileChannel.open(
            targetPath,
            setOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC,
            ),
            PosixFilePermissions.asFileAttribute(FILE_PERMS_0600),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(envelopeBytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        forceParentDirectory(targetPath.parent)
    }

    /**
     * Write [bytes] to [path] with:
     * - CREATE_NEW + NOFOLLOW_LINKS
     * - Atomic 0600 permissions via PosixFilePermissions file attribute
     * - Loop until all bytes written
     * - fsync via channel.force(true)
     */
    private fun writeTempFileWith0600(path: Path, bytes: ByteArray) {
        FileChannel.open(
            path,
            setOf(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC,
            ),
            PosixFilePermissions.asFileAttribute(FILE_PERMS_0600),
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    /**
     * Read and decrypt a stored record with bounded file size and symlink rejection.
     *
     * @throws FileStoreCorruptionException on decryption failure.
     */
    fun readAndDecrypt(
        path: Path,
        recordType: String,
        expectedRecordKeyDigest: String,
        key: javax.crypto.SecretKey,
        expectedKeyId: String,
    ): ByteArray {
        require(!path.isSymbolicLink()) { "symlink-not-allowed" }
        val json = boundedReadText(path)
        val envelope = EncryptedFileEnvelopeV1.fromJson(json)
        return AesGcmFileEncryption.decrypt(
            key, envelope, recordType, expectedRecordKeyDigest, expectedKeyId,
        )
    }

    /**
     * Read a file's content with a hard size cap before loading into memory.
     *
     * @throws IllegalArgumentException if the file exceeds 10 MB.
     */
    fun boundedReadText(path: Path): String {
        val size = Files.size(path)
        require(size <= 10_485_760) { "file-too-large" }
        return path.readText()
    }

    /** SHA-256 hex of an input string. */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Per-key lock for concurrent access. */
    fun perKeyLock(): ReentrantLock = ReentrantLock()

    /**
     * Scans [directory] for committed records using a strict inventory strategy.
     *
     * Returns a list of file paths matching [filenamePattern].
     * Every entry must match — unexpected files, renamed records, orphan temps,
     * and subdirectories all fail closed.
     *
     * @throws FileStoreCorruptionException if any unexpected entry is found.
     * @throws FileStorePermissionException if a matched file fails permission validation.
     */
    fun strictCommittedEntries(directory: Path, filenamePattern: Regex, recordDescription: String): List<Path> {
        return buildList {
            for (entry in directory.toFile().listFiles()!!) {
                if (entry.isDirectory) {
                    throw FileStoreCorruptionException("$recordDescription-unexpected-directory-entry")
                }
                val path = entry.toPath()
                val name = path.fileName.toString()
                if (!filenamePattern.matches(name)) {
                    throw FileStoreCorruptionException("$recordDescription-unexpected-entry")
                }
                add(path)
            }
        }
    }

    /** Fsync a directory. */
    fun forceParentDirectory(dir: Path) {
        if (dir.exists()) {
            try {
                FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Exception) {
                // Best-effort
            }
        }
    }

    /**
     * Validate that the given path is a regular file (no symlink) with 0600 permissions.
     * @throws FileStorePermissionException if validation fails.
     */
    fun validateRegularFile(path: Path, description: String) {
        if (path.isSymbolicLink()) throw FileStorePermissionException("$description-symlink-rejected")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw FileStorePermissionException("$description-not-regular-file")
        }
        val perms = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).toSet()
        if (perms != FILE_PERMS_0600) throw FileStorePermissionException("$description-permission-denied")
    }

    /**
     * Validate a managed directory.
     *
     * Must:
     * - Not be a symlink
     * - Be a directory (checked with NOFOLLOW_LINKS)
     * - Have exact 0700 permissions
     *
     * @throws FileStorePermissionException if validation fails.
     */
    fun validateManagedDirectory(path: Path, description: String) {
        if (path.isSymbolicLink()) throw FileStorePermissionException("$description-symlink-rejected")
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw FileStorePermissionException("$description-not-directory")
        }
        if (Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).toSet() != DIR_PERMS_0700) {
            throw FileStorePermissionException("$description-permission-denied")
        }
    }

    /**
     * Create a single directory with strict 0700 permissions atomically.
     * Throws if it already exists.
     */
    fun createStrictDirectory(path: Path, description: String) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIR_PERMS_0700))
    }
}
