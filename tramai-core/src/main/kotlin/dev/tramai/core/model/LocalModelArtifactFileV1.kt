package dev.tramai.core.model

data class LocalModelArtifactFileV1(
    val relativePath: String,
    val sizeBytes: Long,
    val digest: ModelArtifactDigest,
) {
    init {
        validateField("relativePath", relativePath)
        require(relativePath.isNotEmpty()) { "Path must not be empty" }
        require(!relativePath.startsWith("/")) { "Path must be relative (no absolute paths)" }
        require(!relativePath.startsWith("..")) { "Path must not traverse upward" }
        require(!relativePath.contains("/../") && !relativePath.contains("\\..\\")) {
            "Path must not contain upward traversal"
        }
        require(relativePath.none(Char::isISOControl)) { "Path must not contain control characters" }
        require(sizeBytes >= 0) { "sizeBytes must not be negative" }
    }
}
