package dev.tramai.spring.sovereign.persistence.jdbc

/**
 * Codec for encrypting and decrypting audit outbox record payloads
 * before storing them in the `encrypted_payload` column of `audit_outbox`.
 *
 * Implementations are responsible for key management, encryption algorithm
 * selection, and nonce generation.
 */
interface JdbcOpsAuditOutboxPayloadCodec {

    /**
     * Encrypt [plaintext] and produce a [JdbcEncryptedAuditOutboxPayload]
     * suitable for storage in the `audit_outbox` table.
     */
    fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload

    /**
     * Decrypt the [envelope] and return the original plaintext.
     *
     * @throws IllegalStateException if decryption fails (wrong key,
     *   tampered ciphertext, algorithm mismatch, etc.).
     */
    fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray
}

/**
 * Encrypted audit outbox payload with all metadata fields required by the
 * `audit_outbox` table schema.
 */
data class JdbcEncryptedAuditOutboxPayload(
    val ciphertext: ByteArray,
    val keyId: String,
    val algorithm: String,
    val nonce: ByteArray,
    val payloadDigest: String,
)
