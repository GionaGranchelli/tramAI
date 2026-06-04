package dev.tramai.core.security

/**
 * Shared DLP rule ID normalization logic used by both the interceptor and audit emitter.
 */
object DlpRuleIdNormalizer {

    private const val maxRuleIdLength = 128
    private val safeRuleId = Regex("[a-z0-9][a-z0-9._:-]{0,127}")

    fun normalize(id: String): String {
        require(id.isNotBlank()) { "DLP rule ID must not be blank" }
        require(id == id.trim()) { "DLP rule ID must not contain surrounding whitespace" }
        require(id.length <= maxRuleIdLength) { "DLP rule ID exceeds maximum length of 128" }
        require(safeRuleId.matches(id)) { "DLP rule ID is invalid" }
        return id
    }
}
