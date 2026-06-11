package dev.tramai.persistence.file

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.*

/**
 * Shared utilities for file-backed stores.
 */
internal object FileStoreUtil {

    private val RANDOM = SecureRandom()

    /** Generate a temporary sibling filename. */
    fun tempSibling(target: Path): Path =
        target.resolveSibling(".${target.fileName}.tmp.${RANDOM.nextInt()}")

    /** Atomically move temp file to target location. */
    fun atomicMove(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
    }

    /** Encrypt and write a record atomically. */
    fun atomicEncryptWrite(
        targetPath: Path,
        recordType: String,
        recordKeyDigest: String,
        keyId: String,
        key: javax.crypto.SecretKey,
        plaintextBytes: ByteArray,
    ) {
        val (nonceB64, ciphertextB64) = AesGcmFileEncryption.encrypt(key, recordType, recordKeyDigest, plaintextBytes)
        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = recordType,
            recordKeyDigest = recordKeyDigest,
            keyId = keyId,
            nonceBase64 = nonceB64,
            ciphertextBase64 = ciphertextB64,
        )
        val envelopeJson = envelope.toJson().toByteArray(Charsets.UTF_8)
        val temp = tempSibling(targetPath)
        try {
            temp.writeBytes(envelopeJson)
            atomicMove(temp, targetPath)
        } finally {
            // Best-effort cleanup of temp file
            try { Files.deleteIfExists(temp) } catch (_: Exception) {}
        }
    }

    /** Read and decrypt a stored record. */
    fun readAndDecrypt(
        path: Path,
        recordType: String,
        expectedRecordKeyDigest: String,
        key: javax.crypto.SecretKey,
    ): ByteArray {
        val json = path.readText()
        val envelope = EncryptedFileEnvelopeV1.fromJson(json)
        return AesGcmFileEncryption.decrypt(key, envelope, recordType, expectedRecordKeyDigest)
    }

    /** SHA-256 hex of an input string. */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Per-key lock for concurrent access. */
    fun perKeyLock(): ReentrantLock = ReentrantLock()
}
