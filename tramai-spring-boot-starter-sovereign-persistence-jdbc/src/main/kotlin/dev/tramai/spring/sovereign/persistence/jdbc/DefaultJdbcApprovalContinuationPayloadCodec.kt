package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.persistence.jdbc.JdbcContinuationArgumentsCodec
import dev.tramai.persistence.jdbc.JdbcEncryptedContinuationArguments
import javax.crypto.SecretKey

/**
 * Default AES-256-GCM implementation of [JdbcContinuationArgumentsCodec].
 *
 * Delegates encryption and decryption to [DefaultJdbcPayloadCrypto].
 *
 * @param key The AES-256 secret key.
 * @param keyId Identifier for the key (stored in the `encryption_key_id` column).
 */
class DefaultJdbcApprovalContinuationPayloadCodec(
    private val key: SecretKey,
    private val keyId: String = "default",
) : JdbcContinuationArgumentsCodec {

    override fun encode(plaintext: ByteArray): JdbcEncryptedContinuationArguments {
        val encrypted = DefaultJdbcPayloadCrypto.encrypt(plaintext, key, keyId)
        DefaultJdbcPayloadCrypto.verifyDigest(encrypted)
        return encrypted.toContinuationArgs()
    }

    override fun decode(envelope: JdbcEncryptedContinuationArguments): ByteArray {
        DefaultJdbcPayloadCrypto.verifyDigest(envelope.toEncryptedPayload())
        return DefaultJdbcPayloadCrypto.decrypt(envelope.toEncryptedPayload(), key)
    }
}
