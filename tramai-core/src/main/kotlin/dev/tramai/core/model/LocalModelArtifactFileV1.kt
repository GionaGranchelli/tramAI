package dev.tramai.core.model

data class LocalModelArtifactFileV1(
    val relativePath: String,
    val sizeBytes: Long,
    val digest: ModelArtifactDigest,
) {
    init {
        validateField("relativePath", relativePath)
        require(relativePath.isNotEmpty()) { "Path must not be empty" }

        // Reject absolute paths on all platforms
        require(!relativePath.startsWith("/")) { "Path must be relative (no Unix absolute paths)" }
        require(!relativePath.startsWith("\\\\")) { "Path must be relative (no UNC paths)" }

        // Block Windows drive letters (C:\...)
        require(relativePath.length < 2 || relativePath[1] != ':') {
            "Path must be relative (no Windows drive prefixes)"
        }

        // Normalize to forward-slash representation
        val normalized = relativePath.replace('\\', '/')

        // Reject traversal
        require(!normalized.startsWith("..")) { "Path must not traverse upward" }
        require(!normalized.contains("/../") && !normalized.endsWith("/..")) {
            "Path must not contain upward traversal"
        }

        // Reject self-references
        require(!normalized.contains("/./") && !normalized.startsWith("./") && normalized != ".") {
            "Path must not contain self-reference segments"
        }

        // Reject empty segments (double slashes)
        require(!normalized.contains("//")) {
            "Path must not contain empty segments (double slashes)"
        }

        require(normalized.none(Char::isISOControl)) { "Path must not contain control characters" }

        // Verify normalized representation is identical to declared Path
        // This catches any path that resolves differently than written
        require(normalized == relativePath) {
            "Path must use forward-slash separator consistently"
        }

        require(sizeBytes >= 0) { "sizeBytes must not be negative" }
    }
}
