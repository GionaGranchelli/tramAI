package dev.tramai.sovereign.evidence

/**
 * Validates that identifiers written into the Sovereign Evidence Pack
 * do not contain fragments that could leak sensitive information.
 *
 * The evidence pack must be safe to share with auditors. This sanitizer
 * rejects identifiers that contain path prefixes, secrets-adjacent terms,
 * or control characters — before they are written into the JSON output.
 */
internal object EvidenceSafeString {

    private val windowsDrivePath = Regex("^[A-Za-z]:[/\\\\]")

    private val forbiddenFragments = listOf(
        "/tmp/",
        "/home/",
        "/Users/",
        "token",
        "secret",
        "password",
        "prompt",
        "rawRequest",
        "rawResponse",
        "stacktrace",
    )

    /**
     * Validates that [value] is safe for inclusion in the evidence pack.
     *
     * @throws IllegalArgumentException with a fixed safe reason code
     *   if the value contains an unsafe fragment or control character.
     * @return [value] unchanged on success.
     */
    fun sanitize(value: String): String {
        require(value.none(Char::isISOControl)) {
            "evidence-unsafe-control-character"
        }

        require(!windowsDrivePath.containsMatchIn(value)) {
            "evidence-unsafe-identifier"
        }

        val lower = value.lowercase()
        require(forbiddenFragments.none { lower.contains(it.lowercase()) }) {
            "evidence-unsafe-identifier"
        }

        return value
    }
}
