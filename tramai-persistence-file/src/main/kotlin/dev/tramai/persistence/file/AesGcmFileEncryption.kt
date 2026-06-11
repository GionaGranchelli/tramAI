package dev.tramai.persistence.file

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM authenticated encryption for file-backed persistence.
 *
 * Uses a 96-bit random nonce and 128-bit authentication tag per record.
 * Additional Authenticated Data (AAD) binds the ciphertext to its record
 * type and key digest, preventing type-confusion or swap attacks.
 */
object AesGcmFileEncryption {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BITS = 256
    private const val TAG_LENGTH_BITS = 128
    private const val NONCE_LENGTH_BYTES = 12 // 96-bit
    private val RANDOM = SecureRandom()

    /**
     * Builds Additional Authenticated Data (AAD):
     * `tramAI-file-store|envelope-v1|recordType|recordKeyDigest`
     */
    private fun buildAad(recordType: String, recordKeyDigest: String): ByteArray {
        return "tramAI-file-store|envelope-v1|$recordType|$recordKeyDigest"
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * Encrypts [plaintextBytes] with AES-256-GCM.
     *
     * @param key The 256-bit AES secret key.
     * @param recordType Domain discriminator bound as AAD.
     * @param recordKeyDigest Stable record identifier digest bound as AAD.
     * @param plaintextBytes Raw plaintext to encrypt.
     * @return Pair of (nonceBase64, ciphertextBase64).
     */
    fun encrypt(
        key: SecretKey,
        recordType: String,
        recordKeyDigest: String,
        plaintextBytes: ByteArray,
    ): Pair<String, String> {
        val nonce = ByteArray(NONCE_LENGTH_BYTES)
        RANDOM.nextBytes(nonce)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        cipher.updateAAD(buildAad(recordType, recordKeyDigest))

        val ciphertext = cipher.doFinal(plaintextBytes)

        return Base64.getEncoder().encodeToString(nonce) to
                Base64.getEncoder().encodeToString(ciphertext)
    }

    /**
     * Decrypts an [EncryptedFileEnvelopeV1].
     *
     * Validates algorithm, envelope version, and expected record metadata.
     *
     * @throws FileStoreCorruptionException on any decryption failure
     *   (wrong key, mutated ciphertext or nonce, AAD mismatch, GCM tag failure,
     *    unsupported envelope version, record type mismatch).
     */
    fun decrypt(
        key: SecretKey,
        envelope: EncryptedFileEnvelopeV1,
        expectedRecordType: String,
        expectedRecordKeyDigest: String,
    ): ByteArray {
        require(envelope.envelopeVersion == 1) {
            "Unsupported envelope version: ${envelope.envelopeVersion}"
        }
        require(envelope.recordType == expectedRecordType) {
            "Record type mismatch: expected $expectedRecordType, got ${envelope.recordType}"
        }

        val nonce = Base64.getDecoder().decode(envelope.nonceBase64)
        val ciphertext = Base64.getDecoder().decode(envelope.ciphertextBase64)

        val cipher = Cipher.getInstance(ALGORITHM)
        try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, nonce))
            cipher.updateAAD(buildAad(envelope.recordType, envelope.recordKeyDigest))
            return cipher.doFinal(ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw FileStoreCorruptionException("ciphertext-authentication-failed", e)
        } catch (e: Exception) {
            throw FileStoreCorruptionException("decryption-failed", e)
        }
    }
}
