package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.persistence.jdbc.JdbcAuditPayloadCodec
import dev.tramai.persistence.jdbc.JdbcEncryptedAuditPayload
import javax.crypto.SecretKey

/**
 * Default AES-256-GCM implementation of [JdbcAuditPayloadCodec].
 *
 * Delegates encryption and decryption to [DefaultJdbcPayloadCrypto].
 *
 * @param key The AES-256 secret key.
 * @param keyId Identifier for the key (stored in the `encryption_key_id` column).
 */
class DefaultJdbcAuditPayloadCodec(
    private val key: SecretKey,
    private val keyId: String = "default",
) : JdbcAuditPayloadCodec {

    override fun encode(plaintext: ByteArray): JdbcEncryptedAuditPayload {
        val encrypted = DefaultJdbcPayloadCrypto.encrypt(plaintext, key, keyId)
        DefaultJdbcPayloadCrypto.verifyDigest(encrypted)
        return encrypted.toAuditPayload()
    }

    override fun decode(envelope: JdbcEncryptedAuditPayload): ByteArray {
        DefaultJdbcPayloadCrypto.verifyDigest(envelope.toEncryptedPayload())
        return DefaultJdbcPayloadCrypto.decrypt(envelope.toEncryptedPayload(), key)
    }
}
