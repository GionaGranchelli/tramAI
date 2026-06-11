package dev.tramai.persistence.file

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AesGcmFileEncryptionTest {

    private fun testKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `round-trip with correct key`() {
        val key = testKey()
        val plaintext = "Hello, persistence layer!".toByteArray(StandardCharsets.UTF_8)

        val (nonce, ciphertext) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "test-record",
            recordKeyDigest = "abcd1234",
            keyId = "test-key",
            plaintextBytes = plaintext,
        )

        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = "test-record",
            recordKeyDigest = "abcd1234",
            keyId = "test-key",
            nonceBase64 = nonce,
            ciphertextBase64 = ciphertext,
        )

        val decrypted = AesGcmFileEncryption.decrypt(
            key = key,
            envelope = envelope,
            expectedRecordType = "test-record",
            expectedRecordKeyDigest = "abcd1234",
            expectedKeyId = "test-key",
        )

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `wrong key rejected`() {
        val key1 = testKey()
        val key2 = testKey()
        val plaintext = "secret data".toByteArray(StandardCharsets.UTF_8)

        val (nonce, ciphertext) = AesGcmFileEncryption.encrypt(
            key = key1,
            recordType = "test-record",
            recordKeyDigest = "digest",
            keyId = "test-key",
            plaintextBytes = plaintext,
        )

        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = "test-record",
            recordKeyDigest = "digest",
            keyId = "test-key",
            nonceBase64 = nonce,
            ciphertextBase64 = ciphertext,
        )

        assertThrows<FileStoreCorruptionException> {
            AesGcmFileEncryption.decrypt(
                key = key2,
                envelope = envelope,
                expectedRecordType = "test-record",
                expectedRecordKeyDigest = "digest",
                expectedKeyId = "test-key",
            )
        }
    }

    @Test
    fun `mutated ciphertext rejected`() {
        val key = testKey()
        val plaintext = "sensitive data".toByteArray(StandardCharsets.UTF_8)

        val (nonce, ciphertext) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "test-record",
            recordKeyDigest = "digest",
            keyId = "test-key",
            plaintextBytes = plaintext,
        )

        // Flip a byte in the ciphertext
        val ciphertextBytes = Base64.getDecoder().decode(ciphertext)
        ciphertextBytes[0] = (ciphertextBytes[0].toInt() xor 0xFF).toByte()
        val mutatedCiphertext = Base64.getEncoder().encodeToString(ciphertextBytes)

        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = "test-record",
            recordKeyDigest = "digest",
            keyId = "test-key",
            nonceBase64 = nonce,
            ciphertextBase64 = mutatedCiphertext,
        )

        assertThrows<FileStoreCorruptionException> {
            AesGcmFileEncryption.decrypt(
                key = key,
                envelope = envelope,
                expectedRecordType = "test-record",
                expectedRecordKeyDigest = "digest",
                expectedKeyId = "test-key",
            )
        }
    }

    @Test
    fun `mutated nonce rejected`() {
        val key = testKey()
        val plaintext = "sensitive data".toByteArray(StandardCharsets.UTF_8)

        val (nonce, ciphertext) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "test-record",
            recordKeyDigest = "digest",
            keyId = "test-key",
            plaintextBytes = plaintext,
        )

        // Flip a byte in the nonce
        val nonceBytes = Base64.getDecoder().decode(nonce)
        nonceBytes[0] = (nonceBytes[0].toInt() xor 0x01).toByte()
        val mutatedNonce = Base64.getEncoder().encodeToString(nonceBytes)

        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = "test-record",
            recordKeyDigest = "digest",
            keyId = "test-key",
            nonceBase64 = mutatedNonce,
            ciphertextBase64 = ciphertext,
        )

        assertThrows<FileStoreCorruptionException> {
            AesGcmFileEncryption.decrypt(
                key = key,
                envelope = envelope,
                expectedRecordType = "test-record",
                expectedRecordKeyDigest = "digest",
                expectedKeyId = "test-key",
            )
        }
    }

    @Test
    fun `record substitution rejected through AAD`() {
        val key = testKey()
        val plaintext = "approval data".toByteArray(StandardCharsets.UTF_8)

        val (nonce, ciphertext) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "approval-request",
            recordKeyDigest = "digest-A",
            keyId = "test-key",
            plaintextBytes = plaintext,
        )

        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = "approval-request",
            recordKeyDigest = "digest-A",
            keyId = "test-key",
            nonceBase64 = nonce,
            ciphertextBase64 = ciphertext,
        )

        // Try to decrypt with wrong record type (the require() check catches this)
        assertThrows<IllegalArgumentException> {
            AesGcmFileEncryption.decrypt(
                key = key,
                envelope = envelope,
                expectedRecordType = "audit-event",
                expectedRecordKeyDigest = "digest-A",
                expectedKeyId = "test-key",
            )
        }

        // Record key digest mismatch is now validated at encryption layer
        assertThrows<IllegalArgumentException> {
            AesGcmFileEncryption.decrypt(
                key = key,
                envelope = envelope,
                expectedRecordType = "approval-request",
                expectedRecordKeyDigest = "digest-B",
                expectedKeyId = "test-key",
            )
        }
    }

    @Test
    fun `same plaintext written twice produces different ciphertext`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plaintext = "constant data".toByteArray(StandardCharsets.UTF_8)

        val (nonce1, ciphertext1) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "test",
            recordKeyDigest = "digest",
            keyId = "test-key",
            plaintextBytes = plaintext,
        )
        val (nonce2, ciphertext2) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "test",
            recordKeyDigest = "digest",
            keyId = "test-key",
            plaintextBytes = plaintext,
        )

        assertNotEquals(nonce1, nonce2, "Nonces must differ for each encryption")
        assertNotEquals(ciphertext1, ciphertext2, "Ciphertexts must differ for each encryption")

        // Both must still decrypt correctly
        for ((nonce, ct) in listOf(nonce1 to ciphertext1, nonce2 to ciphertext2)) {
            val envelope = EncryptedFileEnvelopeV1(
                envelopeVersion = 1,
                recordType = "test",
                recordKeyDigest = "digest",
                keyId = "test-key",
                nonceBase64 = nonce,
                ciphertextBase64 = ct,
            )
            val decrypted = AesGcmFileEncryption.decrypt(
                key = key,
                envelope = envelope,
                expectedRecordType = "test",
                expectedRecordKeyDigest = "digest",
                expectedKeyId = "test-key",
            )
            assertContentEquals(plaintext, decrypted)
        }
    }

    @Test
    fun `key material absent from all persisted bytes`() {
        val key = testKey()
        val plaintext = "super-secret".toByteArray(StandardCharsets.UTF_8)

        val (nonce, ciphertext) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "test",
            recordKeyDigest = "digest",
            keyId = "test-key",
            plaintextBytes = plaintext,
        )

        val envelopeJson = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = "test",
            recordKeyDigest = "digest",
            keyId = "test-key",
            nonceBase64 = nonce,
            ciphertextBase64 = ciphertext,
        ).toJson()

        val encodedKey = Base64.getEncoder().encodeToString(key.encoded)
        assertTrue(!envelopeJson.contains(encodedKey), "Key base64 must not appear in JSON envelope")
        assertTrue(!envelopeJson.contains(key.encoded.toString(Charsets.UTF_8)), "Key raw bytes must not appear in JSON envelope")
    }

    @Test
    fun `wrong keyId in AAD rejected`() {
        val key = testKey()
        val plaintext = "sensitive".toByteArray(StandardCharsets.UTF_8)

        val (nonce, ciphertext) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "test",
            recordKeyDigest = "digest",
            keyId = "key-A",
            plaintextBytes = plaintext,
        )

        val envelope = EncryptedFileEnvelopeV1(
            envelopeVersion = 1,
            recordType = "test",
            recordKeyDigest = "digest",
            keyId = "key-A",
            nonceBase64 = nonce,
            ciphertextBase64 = ciphertext,
        )

        // Wrong expected keyId triggers require() check
        assertThrows<IllegalArgumentException> {
            AesGcmFileEncryption.decrypt(
                key = key,
                envelope = envelope,
                expectedRecordType = "test",
                expectedRecordKeyDigest = "digest",
                expectedKeyId = "key-B",
            )
        }
    }
}
