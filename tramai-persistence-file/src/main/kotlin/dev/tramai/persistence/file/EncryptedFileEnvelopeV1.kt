package dev.tramai.persistence.file

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * On-disk envelope for a single encrypted persisted record (format version 1).
 *
 * @property envelopeVersion Must be 1 for this envelope type.
 * @property recordType Discriminator indicating which domain type is stored
 *                      (e.g. "approval-request", "approval-continuation", "audit-event").
 * @property recordKeyDigest SHA-256 hex digest of the stable record identifier
 *                           (recordType + ":" + stableRecordId).
 * @property keyId Identifies which encryption key was used.
 * @property nonceBase64 Base64-encoded 96-bit GCM nonce.
 * @property ciphertextBase64 Base64-encoded AES-256-GCM ciphertext (includes 128-bit auth tag).
 */
data class EncryptedFileEnvelopeV1(
    @JsonProperty("envelopeVersion") val envelopeVersion: Int,
    @JsonProperty("recordType") val recordType: String,
    @JsonProperty("recordKeyDigest") val recordKeyDigest: String,
    @JsonProperty("keyId") val keyId: String,
    @JsonProperty("nonceBase64") val nonceBase64: String,
    @JsonProperty("ciphertextBase64") val ciphertextBase64: String,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        fun fromJson(json: String): EncryptedFileEnvelopeV1 = strictReadValue(json)
    }
}
