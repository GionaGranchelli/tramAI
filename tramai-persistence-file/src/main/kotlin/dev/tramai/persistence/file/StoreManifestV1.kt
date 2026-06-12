package dev.tramai.persistence.file

import java.time.Instant

/**
 * On-disk manifest for a file-backed store (format version 1).
 *
 * Written once at store initialisation to identify the module and
 * format version that created the data.
 *
 * @property formatVersion Must be 1 for this manifest format.
 * @property module Must be "tramai-persistence-file".
 * @property createdAt ISO-8601 timestamp of manifest creation.
 */
data class StoreManifestV1(
    val formatVersion: Int,
    val module: String,
    val createdAt: String,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        @Deprecated("Unused", level = DeprecationLevel.ERROR)
        private const val serialVersionUID = 1L

        fun fromJson(json: String): StoreManifestV1 = strictReadValue(json)
    }
}

/**
 * Validates a [StoreManifestV1] against expected values.
 *
 * @throws FileStoreUnsupportedFormatException on validation failure.
 */
internal fun StoreManifestV1.validateManifest() {
    require(formatVersion == 1) {
        throw FileStoreUnsupportedFormatException("unsupported-manifest-format-version")
    }
    require(module == "tramai-persistence-file") {
        throw FileStoreUnsupportedFormatException("unsupported-manifest-module")
    }
    // Validate createdAt is a valid ISO-8601 instant
    try {
        Instant.parse(createdAt)
    } catch (e: Exception) {
        throw FileStoreUnsupportedFormatException("invalid-manifest-created-at")
    }
}
