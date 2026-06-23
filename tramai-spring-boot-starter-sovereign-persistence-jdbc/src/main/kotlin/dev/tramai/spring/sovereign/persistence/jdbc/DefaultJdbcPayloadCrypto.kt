package dev.tramai.spring.sovereign.persistence.jdbc

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Shared AES-256-GCM payload encryption/decryption for all JDBC sovereign store codecs.
 *
 * Uses a 256-bit AES key with GCM/NoPadding, 12-byte nonce (recommended for GCM),
 * and 128-bit authentication tag. Each encryption generates a fresh nonce via
 * [SecureRandom].
 *
 * ## Security properties
 * - AES-256-GCM provides authenticated encryption (confidentiality + integrity)
 * - 12-byte random nonce per encryption (96-bit, NIST SP 800-38D recommended)
 * - 128-bit authentication tag prevents tampering
 * - Nonce overflow with a single key is astronomically unlikely (2^96 space)
 * - Keys are never logged or exposed in exception messages
 * - Algorithm constant is `AES/GCM/NoPadding` — wire value is `AES-256-GCM`
 *
 * ## Key requirements
 * - Key must be 32 bytes (256 bits)
 * - Key should be generated from a cryptographically secure source
 * - Key material must never appear in logs, config YAML, or exception messages
 */
object DefaultJdbcPayloadCrypto {

    private const val ALGORITHM: String = "AES/GCM/NoPadding"
    const val ALGORITHM_WIRE_VALUE: String = "AES-256-GCM"
    private const val GCM_TAG_LENGTH_BITS: Int = 128
    private const val NONCE_LENGTH_BYTES: Int = 12

    private val secureRandom: SecureRandom = SecureRandom()

    /**
     * Encrypt [plaintext] under the given [key] and return the encrypted result
     * with all metadata required by the JDBC codec envelopes.
     */
    fun encrypt(
        plaintext: ByteArray,
        key: SecretKey,
        keyId: String = "default",
    ): EncryptedPayload {
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        val payloadDigest = sha256Hex(ciphertext)

        return EncryptedPayload(
            ciphertext = ciphertext,
            keyId = keyId,
            algorithm = ALGORITHM_WIRE_VALUE,
            nonce = nonce,
            payloadDigest = payloadDigest,
        )
    }

    /**
     * Decrypt [envelope] using the given [key].
     *
     * @throws IllegalStateException if decryption fails (wrong key, tampered
     *   ciphertext, algorithm mismatch, etc.).
     */
    fun decrypt(
        envelope: EncryptedPayload,
        key: SecretKey,
    ): ByteArray {
        require(envelope.algorithm == ALGORITHM_WIRE_VALUE) {
            "tramai-sovereign-jdbc-codec-algorithm-mismatch"
        }

        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, envelope.nonce),
            )
            return cipher.doFinal(envelope.ciphertext)
        } catch (e: Exception) {
            throw IllegalStateException("tramai-sovereign-jdbc-codec-decryption-failed", e)
        }
    }

    /**
     * Verify that the [envelope]'s payload digest matches a recomputation
     * of the SHA-256 over the ciphertext. Throws on mismatch.
     */
    fun verifyDigest(envelope: EncryptedPayload) {
        val expectedDigest = sha256Hex(envelope.ciphertext)
        require(envelope.payloadDigest == expectedDigest) {
            "tramai-sovereign-jdbc-codec-digest-mismatch"
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Generic encrypted payload matching the shape required by all JDBC codec
 * envelope types. Use [toAuditPayload], [toReplayEnvelope], [toContinuationArgs],
 * and [toOutboxPayload] to convert to the specific envelope types.
 */
data class EncryptedPayload(
    val ciphertext: ByteArray,
    val keyId: String,
    val algorithm: String,
    val nonce: ByteArray,
    val payloadDigest: String,
)

// ── Conversion extensions ─────────────────────────────────────────────

fun EncryptedPayload.toAuditPayload(): dev.tramai.persistence.jdbc.JdbcEncryptedAuditPayload =
    dev.tramai.persistence.jdbc.JdbcEncryptedAuditPayload(
        ciphertext = ciphertext,
        keyId = keyId,
        algorithm = algorithm,
        nonce = nonce,
        payloadDigest = payloadDigest,
    )

fun EncryptedPayload.toReplayEnvelope(): dev.tramai.persistence.jdbc.JdbcEncryptedReplayEnvelope =
    dev.tramai.persistence.jdbc.JdbcEncryptedReplayEnvelope(
        ciphertext = ciphertext,
        keyId = keyId,
        algorithm = algorithm,
        nonce = nonce,
        payloadDigest = payloadDigest,
    )

fun EncryptedPayload.toContinuationArgs(): dev.tramai.persistence.jdbc.JdbcEncryptedContinuationArguments =
    dev.tramai.persistence.jdbc.JdbcEncryptedContinuationArguments(
        ciphertext = ciphertext,
        keyId = keyId,
        algorithm = algorithm,
        nonce = nonce,
        payloadDigest = payloadDigest,
    )

fun EncryptedPayload.toOutboxPayload(): JdbcEncryptedAuditOutboxPayload =
    JdbcEncryptedAuditOutboxPayload(
        ciphertext = ciphertext,
        keyId = keyId,
        algorithm = algorithm,
        nonce = nonce,
        payloadDigest = payloadDigest,
    )

// ── Inverse conversions ───────────────────────────────────────────────

fun dev.tramai.persistence.jdbc.JdbcEncryptedAuditPayload.toEncryptedPayload(): EncryptedPayload =
    EncryptedPayload(
        ciphertext = ciphertext,
        keyId = keyId,
        algorithm = algorithm,
        nonce = nonce,
        payloadDigest = payloadDigest,
    )

fun dev.tramai.persistence.jdbc.JdbcEncryptedReplayEnvelope.toEncryptedPayload(): EncryptedPayload =
    EncryptedPayload(
        ciphertext = ciphertext,
        keyId = keyId,
        algorithm = algorithm,
        nonce = nonce,
        payloadDigest = payloadDigest,
    )

fun dev.tramai.persistence.jdbc.JdbcEncryptedContinuationArguments.toEncryptedPayload(): EncryptedPayload =
    EncryptedPayload(
        ciphertext = ciphertext,
        keyId = keyId,
        algorithm = algorithm,
        nonce = nonce,
        payloadDigest = payloadDigest,
    )

fun JdbcEncryptedAuditOutboxPayload.toEncryptedPayload(): EncryptedPayload =
    EncryptedPayload(
        ciphertext = ciphertext,
        keyId = keyId,
        algorithm = algorithm,
        nonce = nonce,
        payloadDigest = payloadDigest,
    )
