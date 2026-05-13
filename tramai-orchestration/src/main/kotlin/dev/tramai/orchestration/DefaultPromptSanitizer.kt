package dev.tramai.orchestration

import dev.tramai.core.security.PromptSanitizer

data object DefaultPromptSanitizer : PromptSanitizer {
    const val RULE_CONTROL_CHAR = "sanitizer/control-char"
    const val RULE_DELIMITER_TRICK = "sanitizer/delimiter-trick"
    const val RULE_JAILBREAK_FRAGMENT = "sanitizer/jailbreak-fragment"

    private val delimiterPatterns = listOf(
        Regex("""\[(?:/)?SYSTEM_INSTRUCTIONS]""", RegexOption.IGNORE_CASE),
        Regex("""\[(?:/)?USER_PROMPT]""", RegexOption.IGNORE_CASE),
    )

    private val jailbreakPatterns = listOf(
        Regex("""\bignore\s+previous\s+instructions\b""", RegexOption.IGNORE_CASE),
        Regex("""\bignore\s+all\s+instructions\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou\s+are\s+now\b""", RegexOption.IGNORE_CASE),
        Regex("""\bDAN\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdo\s+not\s+follow\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdisregard\b""", RegexOption.IGNORE_CASE),
    )

    override fun sanitize(input: String): String {
        if (input.isEmpty()) {
            return input
        }

        val withoutControlChars = input.filter(::isAllowedCharacter)
        val delimiterEscaped = delimiterPatterns.fold(withoutControlChars) { current, pattern ->
            current.replace(pattern) { match -> "`" + match.value + "`" }
        }
        val jailbreakNeutralized = jailbreakPatterns.fold(delimiterEscaped) { current, pattern ->
            current.replace(pattern) { match ->
                "[neutralized:${match.value.lowercase()}]"
            }
        }

        if (jailbreakNeutralized.isEmpty()) {
            return jailbreakNeutralized
        }

        return prefixQuotedLines(jailbreakNeutralized)
    }

    fun getTriggeredRules(input: String): List<String> {
        val rules = linkedSetOf<String>()
        if (input.any { !isAllowedCharacter(it) }) {
            rules += RULE_CONTROL_CHAR
        }
        if (delimiterPatterns.any { it.containsMatchIn(input) }) {
            rules += RULE_DELIMITER_TRICK
        }
        if (jailbreakPatterns.any { it.containsMatchIn(input) }) {
            rules += RULE_JAILBREAK_FRAGMENT
        }
        return rules.toList()
    }

    private fun isAllowedCharacter(char: Char): Boolean = when {
        char == '\n' || char == '\r' || char == '\t' -> true
        char.code in 0x00..0x1f || char.code == 0x7f -> false
        else -> true
    }

    private fun prefixQuotedLines(value: String): String = buildString(value.length + 2) {
        var atLineStart = true
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (atLineStart) {
                append("> ")
                atLineStart = false
            }
            append(char)
            atLineStart = when {
                char == '\n' -> true
                char == '\r' && index + 1 < value.length && value[index + 1] == '\n' -> false
                char == '\r' -> true
                else -> false
            }
            index += 1
        }
    }
}
