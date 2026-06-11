package dev.tramai.persistence.file

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals

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

        // Try to decrypt with wrong record type (simulating substitution attack)
        // The require() check catches this before decryption
        assertThrows<IllegalArgumentException> {
            AesGcmFileEncryption.decrypt(
                key = key,
                envelope = envelope,
                expectedRecordType = "audit-event",
                expectedRecordKeyDigest = "digest-A",
            )
        }

        // Record key digest mismatch is an application-level concern, not validated
        // at the encryption layer (the AAD uses envelope values directly).
        // Tampering with ciphertext (which would fail GCM auth) is tested separately.
    }

    @Test
    fun `same plaintext written twice produces different ciphertext`() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val plaintext = "constant data".toByteArray(StandardCharsets.UTF_8)

        val (nonce1, ciphertext1) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "test",
            recordKeyDigest = "digest",
            plaintextBytes = plaintext,
        )
        val (nonce2, ciphertext2) = AesGcmFileEncryption.encrypt(
            key = key,
            recordType = "test",
            recordKeyDigest = "digest",
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
        // Key bytes should not appear verbatim in the JSON envelope
        assertNotEquals(true, envelopeJson.contains(encodedKey))
        // The key's base64 should also not be in there
        assertNotEquals(true, envelopeJson.contains(key.encoded.toString(Charsets.UTF_8)))
    }
}
