package dev.tramai.spring.sovereign.persistence.jdbc

import javax.crypto.SecretKey

/**
 * Default AES-256-GCM implementation of [JdbcOpsAuditOutboxPayloadCodec].
 *
 * Delegates encryption and decryption to [DefaultJdbcPayloadCrypto].
 *
 * @param key The AES-256 secret key.
 * @param keyId Identifier for the key (stored in the `encryption_key_id` column).
 */
class DefaultJdbcOpsAuditOutboxPayloadCodec(
    private val key: SecretKey,
    private val keyId: String = "default",
) : JdbcOpsAuditOutboxPayloadCodec {

    override fun encode(plaintext: ByteArray): JdbcEncryptedAuditOutboxPayload {
        val encrypted = DefaultJdbcPayloadCrypto.encrypt(plaintext, key, keyId)
        DefaultJdbcPayloadCrypto.verifyDigest(encrypted)
        return encrypted.toOutboxPayload()
    }

    override fun decode(envelope: JdbcEncryptedAuditOutboxPayload): ByteArray {
        DefaultJdbcPayloadCrypto.verifyDigest(envelope.toEncryptedPayload())
        return DefaultJdbcPayloadCrypto.decrypt(envelope.toEncryptedPayload(), key)
    }
}
