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
        const val RULE_BOUNDARY_PROBING = "validator/boundary-probing"

        val defaultPatterns: List<Pair<String, Regex>> = listOf(
            RULE_EXTRACTION_SYSTEM_PROMPT to Regex(
                pattern = """(?is)\b(?:${splitWordPattern("output")}|${splitWordPattern("print")}|${splitWordPattern("repeat")}|${splitWordPattern("reveal")}|${splitWordPattern("show")}|${splitWordPattern("dump")}|${splitWordPattern("quote")})\b(?:\W+\w+){0,4}\W+\b(?:${splitWordPattern("your")}|${splitWordPattern("the")})\b\W+\b(?:${splitPhrasePattern("system", "prompt")}|${splitPhrasePattern("system", "instructions")}|${splitWordPattern("instructions")})\b""",
            ),
            RULE_REPEAT_ABOVE to Regex(
                pattern = """(?is)\b${splitWordPattern("repeat")}\b(?:\W+\w+){0,2}\W+\b(?:${splitWordPattern("everything")}|${splitWordPattern("all")}|${splitWordPattern("entire")})\b\W+\b(?:${splitWordPattern("above")}|${splitWordPattern("previous")}|${splitWordPattern("prior")})\b""",
            ),
            RULE_BOUNDARY_PROBING to Regex(
                pattern = """(?is)\b(?:${splitWordPattern("show")}|${splitWordPattern("reveal")}|${splitWordPattern("print")}|${splitWordPattern("what")}|${splitWordPattern("between")}|${splitWordPattern("inside")}|${splitWordPattern("before")}|${splitWordPattern("after")})\b.{0,80}\b(?:${splitPhrasePattern("system", "instructions")}|${splitPhrasePattern("user", "prompt")}|${splitPhrasePattern("start", "of", "input")}|${splitPhrasePattern("end", "of", "input")})\b""",
            ),
        )
    }
}
