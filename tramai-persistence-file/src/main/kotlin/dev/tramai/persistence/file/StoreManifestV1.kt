package dev.tramai.persistence.file

/**
 * On-disk manifest for a file-backed store (format version 1).
 *
 * Written once at store initialisation to identify the module and
 * format version that created the data.
 *
 * Manual JSON serialisation — no external JSON dependency required.
 */
data class StoreManifestV1(
    val formatVersion: Int = 1,
    val module: String = "tramai-persistence-file",
    val createdAt: String,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"formatVersion\": $formatVersion,")
        appendLine("  \"module\": \"${escapeJson(module)}\",")
        appendLine("  \"createdAt\": \"${escapeJson(createdAt)}\"")
        append("}")
    }

    companion object {
        fun fromJson(json: String): StoreManifestV1 {
            val trimmed = json.trim()
            require(trimmed.startsWith("{") && trimmed.endsWith("}")) {
                "Invalid manifest JSON: must be a JSON object"
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

            return StoreManifestV1(
                formatVersion = extractInt("formatVersion"),
                module = extractString("module"),
                createdAt = extractString("createdAt"),
            )
        }

        private const val DEPRECATED_SERIAL_VERSION_UID = 1L
    }
}
