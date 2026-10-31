package dev.tramai.persistence.file

import java.security.MessageDigest

/**
 * SHA-256 utility for deriving stable, deterministic file names
 * from record type and stable record identifiers.
 *
 * The digest is computed as `SHA-256(recordType + ":" + stableRecordId)`
 * and encoded as a 64-character lowercase hex string.
 */
object FileStoreSha256 {
    /**
     * Computes a SHA-256 digest string for the given record identifier.
     *
     * @param recordType Domain type discriminator (e.g. "approval-request").
     * @param stableRecordId Stable, unique record identifier within the type.
     * @return 64-character lowercase hex SHA-256 hash.
     */
    fun digest(
        recordType: String,
        stableRecordId: String,
    ): String {
        val input = "$recordType:$stableRecordId".toByteArray(Charsets.UTF_8)
        return hexOf(input)
    }

    /**
     * SHA-256 of raw bytes as 64-character lowercase hex.
     *
     * Used to fingerprint stored ciphertext without decoding it (0.7.6f quarantine metadata).
     */
    fun hexOf(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
