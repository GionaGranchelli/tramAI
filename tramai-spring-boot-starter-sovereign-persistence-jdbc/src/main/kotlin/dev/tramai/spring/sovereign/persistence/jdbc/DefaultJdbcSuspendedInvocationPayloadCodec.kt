package dev.tramai.spring.sovereign.persistence.jdbc

import dev.tramai.persistence.jdbc.JdbcEncryptedReplayEnvelope
import dev.tramai.persistence.jdbc.JdbcReplayEnvelopeCodec
import javax.crypto.SecretKey

/**
 * Default AES-256-GCM implementation of [JdbcReplayEnvelopeCodec].
 *
 * Delegates encryption and decryption to [DefaultJdbcPayloadCrypto].
 *
 * @param key The AES-256 secret key.
 * @param keyId Identifier for the key (stored in the `encryption_key_id` column).
 */
class DefaultJdbcSuspendedInvocationPayloadCodec(
    private val key: SecretKey,
    private val keyId: String = "default",
) : JdbcReplayEnvelopeCodec {

    override fun encode(plaintext: ByteArray): JdbcEncryptedReplayEnvelope {
        val encrypted = DefaultJdbcPayloadCrypto.encrypt(plaintext, key, keyId)
        DefaultJdbcPayloadCrypto.verifyDigest(encrypted)
        return encrypted.toReplayEnvelope()
    }

    override fun decode(envelope: JdbcEncryptedReplayEnvelope): ByteArray {
        DefaultJdbcPayloadCrypto.verifyDigest(envelope.toEncryptedPayload())
        return DefaultJdbcPayloadCrypto.decrypt(envelope.toEncryptedPayload(), key)
    }
}
