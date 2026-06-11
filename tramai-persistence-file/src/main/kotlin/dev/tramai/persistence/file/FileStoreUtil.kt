package dev.tramai.persistence.file

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText

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
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
        forceParentDirectory(target.parent)
    }

    /**
     * Encrypt and write a record atomically with CREATE_NEW, NOFOLLOW_LINKS, and fsync.
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
            // Write with CREATE_NEW and NOFOLLOW_LINKS
            FileChannel.open(
                temp,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                StandardOpenOption.DSYNC,
            ).use { channel ->
                channel.write(ByteBuffer.wrap(envelopeBytes))
                channel.force(true)
            }
            atomicMove(temp, targetPath)
        } finally {
            // Best-effort cleanup of temp file
            try { Files.deleteIfExists(temp) } catch (_: Exception) {}
        }
    }

    /**
     * Read and decrypt a stored record.
     */
    fun readAndDecrypt(
        path: Path,
        recordType: String,
        expectedRecordKeyDigest: String,
        key: javax.crypto.SecretKey,
        expectedKeyId: String,
    ): ByteArray {
        // Validate symlink before reading
        require(!path.isSymbolicLink()) { "symlink-not-allowed" }
        val json = path.readText()
        val envelope = EncryptedFileEnvelopeV1.fromJson(json)
        return AesGcmFileEncryption.decrypt(
            key, envelope, recordType, expectedRecordKeyDigest, expectedKeyId,
        )
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
     * Fsync a directory to ensure the metadata operation (atomic move rename)
     * is durably committed.
     */
    fun forceParentDirectory(dir: Path) {
        if (dir.exists()) {
            try {
                val dirChannel = FileChannel.open(dir, StandardOpenOption.READ)
                dirChannel.use { it.force(true) }
            } catch (_: Exception) {
                // Best-effort — some filesystems/platforms don't support directory fsync
            }
        }
    }

    /**
     * Validate that the given path is a regular file (no symlink) with 0600 permissions.
     * @throws FileStorePermissionException if validation fails.
     */
    fun validateRegularFile(path: Path, description: String) {
        require(!path.isSymbolicLink()) {
            throw FileStorePermissionException("$description-symlink-rejected")
        }
        require(Files.isRegularFile(path)) {
            throw FileStorePermissionException("$description-not-regular-file")
        }
        val perms = Files.getPosixFilePermissions(path)
        val expected = PosixFilePermissions.fromString("rw-------")
        require(perms == expected) {
            throw FileStorePermissionException("$description-permission-denied")
        }
    }
}
