package dev.tramai.orchestration

import dev.tramai.core.security.OutputValidator
import dev.tramai.core.security.ValidationResult

data class DefaultOutputValidator(
    val patterns: List<Pair<String, Regex>> = defaultPatterns,
) : OutputValidator {
    override fun validate(output: String): ValidationResult {
        val match = patterns.firstOrNull { (_, pattern) -> pattern.containsMatchIn(output) }
            ?: return ValidationResult.Valid
        return ValidationResult.Rejected(
            reason = "detected prompt extraction attempt",
            ruleId = match.first,
        )
    }

    companion object {
        const val RULE_EXTRACTION_SYSTEM_PROMPT = "validator/extraction-system-prompt"
        const val RULE_REPEAT_ABOVE = "validator/repeat-above"

        val defaultPatterns: List<Pair<String, Regex>> = listOf(
            RULE_EXTRACTION_SYSTEM_PROMPT to Regex(
                pattern = """(?i)\b(?:output|print|repeat|reveal|show)\b(?:\W+\w+){0,3}\W+\b(?:your|the)\b\W+\b(?:system\W+prompt|instructions?)\b""",
            ),
            RULE_REPEAT_ABOVE to Regex(
                pattern = """(?i)\brepeat\b(?:\W+\w+){0,2}\W+\b(?:everything|all)\b\W+\b(?:above|previous|prior)\b""",
            ),
        )
    }
}
