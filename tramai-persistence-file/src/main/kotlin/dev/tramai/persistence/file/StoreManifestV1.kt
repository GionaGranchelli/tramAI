package dev.tramai.persistence.file

/**
 * On-disk manifest for a file-backed store (format version 1).
 *
 * Written once at store initialisation to identify the module and
 * format version that created the data.
 */
data class StoreManifestV1(
    val formatVersion: Int = 1,
    val module: String = "tramai-persistence-file",
    val createdAt: String,
) {
    fun toJson(): String = FILE_STORE_JSON.writeValueAsString(this)

    companion object {
        @Deprecated("Unused", level = DeprecationLevel.ERROR)
        private const val serialVersionUID = 1L

        fun fromJson(json: String): StoreManifestV1 = strictReadValue(json)
    }
}
