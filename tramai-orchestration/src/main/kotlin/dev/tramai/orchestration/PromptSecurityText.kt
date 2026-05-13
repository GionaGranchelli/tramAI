package dev.tramai.orchestration

import java.text.Normalizer

internal fun normalizePromptSecurityText(input: String): String = buildString(input.length) {
    Normalizer.normalize(input, Normalizer.Form.NFKC).forEach { character ->
        append(confusableAsciiMap[character] ?: character)
    }
}

internal fun splitWordPattern(word: String): String = word
    .map { Regex.escape(it.toString()) }
    .joinToString("""(?:[\p{Punct}_]+)?""")

internal fun splitPhrasePattern(vararg words: String): String = words.joinToString("""(?:[\s\p{Punct}_]+)+""") {
    splitWordPattern(it)
}

private val confusableAsciiMap = mapOf(
    'А' to 'A',
    'а' to 'a',
    'Β' to 'B',
    'Е' to 'E',
    'е' to 'e',
    'Η' to 'H',
    'І' to 'I',
    'і' to 'i',
    'Ј' to 'J',
    'Κ' to 'K',
    'М' to 'M',
    'Ν' to 'N',
    'О' to 'O',
    'о' to 'o',
    'Р' to 'P',
    'р' to 'p',
    'Ѕ' to 'S',
    'ѕ' to 's',
    'Τ' to 'T',
    'Χ' to 'X',
    'х' to 'x',
    'Υ' to 'Y',
    'у' to 'y',
    'Ζ' to 'Z',
)
