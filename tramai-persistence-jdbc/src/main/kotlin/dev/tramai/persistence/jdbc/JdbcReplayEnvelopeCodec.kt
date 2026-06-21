package dev.tramai.persistence.jdbc

/**
 * Minimal codec for encrypting and decrypting replay envelope payloads
 * before storing them in the `encrypted_replay_envelope` column.
 *
 * Implementations are responsible for key management, encryption algorithm
 * selection, and nonce generation.
 */
interface JdbcReplayEnvelopeCodec {
    /**
     * Encrypt [plaintext] and produce a [JdbcEncryptedReplayEnvelope]
     * suitable for storage in the `suspended_invocations` table.
     */
    fun encode(plaintext: ByteArray): JdbcEncryptedReplayEnvelope

    /**
     * Decrypt the [envelope] and return the original plaintext.
     *
     * @throws IllegalStateException if decryption fails (wrong key,
     *   tampered ciphertext, algorithm mismatch, etc.).
     */
    fun decode(envelope: JdbcEncryptedReplayEnvelope): ByteArray
}

/**
 * Encrypted replay envelope with all metadata fields required by the
 * `suspended_invocations` table schema.
 */
data class JdbcEncryptedReplayEnvelope(
    val ciphertext: ByteArray,
    val keyId: String,
    val algorithm: String,
    val nonce: ByteArray,
    val payloadDigest: String,
)
