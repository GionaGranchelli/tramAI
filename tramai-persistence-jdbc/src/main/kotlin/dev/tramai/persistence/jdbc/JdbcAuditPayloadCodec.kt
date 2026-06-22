package dev.tramai.persistence.jdbc

/**
 * Minimal codec for encrypting and decrypting audit event payloads
 * before storing them in the `encrypted_payload` column of `audit_events`.
 *
 * Implementations are responsible for key management, encryption algorithm
 * selection, and nonce generation.
 */
interface JdbcAuditPayloadCodec {
    /**
     * Encrypt [plaintext] and produce a [JdbcEncryptedAuditPayload]
     * suitable for storage in the `audit_events` table.
     */
    fun encode(plaintext: ByteArray): JdbcEncryptedAuditPayload

    /**
     * Decrypt the [envelope] and return the original plaintext.
     *
     * @throws IllegalStateException if decryption fails (wrong key,
     *   tampered ciphertext, algorithm mismatch, etc.).
     */
    fun decode(envelope: JdbcEncryptedAuditPayload): ByteArray
}

/**
 * Encrypted audit payload with all metadata fields required by the
 * `audit_events` table schema.
 */
data class JdbcEncryptedAuditPayload(
    val ciphertext: ByteArray,
    val keyId: String,
    val algorithm: String,
    val nonce: ByteArray,
    val payloadDigest: String,
)
