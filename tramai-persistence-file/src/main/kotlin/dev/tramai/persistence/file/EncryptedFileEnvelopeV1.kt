package dev.tramai.persistence.file

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
    val envelopeVersion: Int,
    val recordType: String,
    val recordKeyDigest: String,
    val keyId: String,
    val nonceBase64: String,
    val ciphertextBase64: String,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"envelopeVersion\": $envelopeVersion,")
        appendLine("  \"recordType\": \"${escapeJson(recordType)}\",")
        appendLine("  \"recordKeyDigest\": \"${escapeJson(recordKeyDigest)}\",")
        appendLine("  \"keyId\": \"${escapeJson(keyId)}\",")
        appendLine("  \"nonceBase64\": \"${escapeJson(nonceBase64)}\",")
        appendLine("  \"ciphertextBase64\": \"${escapeJson(ciphertextBase64)}\"")
        append("}")
    }

    companion object {
        fun fromJson(json: String): EncryptedFileEnvelopeV1 {
            val trimmed = json.trim()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
                "Invalid envelope JSON: must be a JSON object"
            }

            fun extractString(key: String): String {
                val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
                val match = regex.find(trimmed)
                    ?: throw IllegalArgumentException("Missing or invalid field: $key")
                return match.groupValues[1]
            }

            fun extractInt(key: String): Int {
                val regex = Regex("\"$key\"\\s*:\\s*(\\d+)")
                val match = regex.find(trimmed)
                    ?: throw IllegalArgumentException("Missing or invalid field: $key")
                return match.groupValues[1].toInt()
            }

            return EncryptedFileEnvelopeV1(
                envelopeVersion = extractInt("envelopeVersion"),
                recordType = extractString("recordType"),
                recordKeyDigest = extractString("recordKeyDigest"),
                keyId = extractString("keyId"),
                nonceBase64 = extractString("nonceBase64"),
                ciphertextBase64 = extractString("ciphertextBase64"),
            )
        }
    }
}
