package dev.tramai.orchestration

import dev.tramai.core.security.PromptSanitizer

data object DefaultPromptSanitizer : PromptSanitizer {
    const val RULE_CONTROL_CHAR = "sanitizer/control-char"
    const val RULE_DELIMITER_TRICK = "sanitizer/delimiter-trick"
    const val RULE_JAILBREAK_FRAGMENT = "sanitizer/jailbreak-fragment"

    private val delimiterPatterns = listOf(
        Regex("""\[\s*(?:/)?\s*${splitWordPattern("system")}(?:[\s-]+)${splitWordPattern("instructions")}\s*]""", RegexOption.IGNORE_CASE),
        Regex("""\[(?:/)?${splitWordPattern("system")}_${splitWordPattern("instructions")}\s*]""", RegexOption.IGNORE_CASE),
        Regex("""\[\s*(?:/)?\s*${splitWordPattern("user")}(?:[\s-]+)${splitWordPattern("prompt")}\s*]""", RegexOption.IGNORE_CASE),
        Regex("""\[(?:/)?${splitWordPattern("user")}_${splitWordPattern("prompt")}\s*]""", RegexOption.IGNORE_CASE),
        Regex("""\b${splitPhrasePattern("start", "of", "input")}\b""", RegexOption.IGNORE_CASE),
        Regex("""\b${splitPhrasePattern("end", "of", "input")}\b""", RegexOption.IGNORE_CASE),
        Regex("""={4,}"""),
    )

    private val jailbreakPatterns = listOf(
        Regex("""\b${splitPhrasePattern("ignore", "previous", "instructions")}\b""", RegexOption.IGNORE_CASE),
        Regex("""\b${splitPhrasePattern("ignore", "all", "instructions")}\b""", RegexOption.IGNORE_CASE),
        Regex("""\b${splitPhrasePattern("you", "are", "now")}\b""", RegexOption.IGNORE_CASE),
        Regex("""\b${splitWordPattern("dan")}\b""", RegexOption.IGNORE_CASE),
        Regex("""\b${splitPhrasePattern("do", "not", "follow")}\b""", RegexOption.IGNORE_CASE),
        Regex("""\b${splitWordPattern("disregard")}\b""", RegexOption.IGNORE_CASE),
        Regex("""(?<![A-Za-z0-9+/=])(?=[A-Za-z]*[0-9+/])[A-Za-z0-9+/]{32,}={0,2}(?![A-Za-z0-9+/=])"""),
    )

    override fun sanitize(input: String): String {
        if (input.isEmpty()) {
            return input
        }

        val normalizedInput = normalizePromptSecurityText(input)
        val withoutControlChars = normalizedInput.filter(::isAllowedCharacter)
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
        val normalizedInput = normalizePromptSecurityText(input)
        val rules = linkedSetOf<String>()
        if (input.any { !isAllowedCharacter(it) }) {
            rules += RULE_CONTROL_CHAR
        }
        if (delimiterPatterns.any { it.containsMatchIn(normalizedInput) }) {
            rules += RULE_DELIMITER_TRICK
        }
        if (jailbreakPatterns.any { it.containsMatchIn(normalizedInput) }) {
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
