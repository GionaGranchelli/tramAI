package dev.tramai.testing

import java.io.File

/**
 * Pure text scanning for the provider enrollment contract.
 *
 * Extracted from [ProviderTckEnrollmentArchitectureTest] so the scanning rule can
 * be tested directly, rather than only through a repository-wide assertion.
 *
 * The heuristic reads structural characters (`{`, `(`, `)`, `:`) and declaration
 * keywords from a `class`/`object` keyword to the next `{`, then treats what follows
 * the first depth-0 `:` as a supertype list.
 *
 * **Nothing that is not code may contribute structure to that heuristic.** Before
 * matching, comments are dropped and the *contents* of string, character and raw-string
 * literals are masked to whitespace (newlines preserved, delimiters kept). Only the
 * delimiters survive, so token boundaries keep their shape while literal contents cannot
 * be read as structure. Both directions matter:
 *
 * - a KDoc that merely *mentions* `ModelProvider` was read as an unrelated declaration's
 *   supertype list, producing a false failure that named innocent code;
 * - a literal containing `{`, `(` or `:` shifts where the header scan stops or where the
 *   supertype section begins, and a literal containing `class Fake : ModelProvider {`
 *   would otherwise invent an implementation — **silent** false results, the one failure
 *   mode a gate must not have.
 *
 * Masking rather than copying literals through is the whole point: copying makes `//` in a
 * URL harmless but leaves `{`, `(`, `)` and declaration-looking text live.
 *
 * This is lexical normalisation, not a Kotlin parser: the architecture check stays simple.
 */
internal object ProviderEnrollmentScan {
    /** Class/object name + header up to the first `{`, spanning newlines. */
    private val CLASS_HEADER = Regex("""(?s)(?:class|object)\s+(\w+)(.*?)\{""")

    private const val TRIPLE_QUOTE = "\"\"\""

    /**
     * Declaration names in [text] whose supertype section names `ModelProvider`.
     *
     * A class/object implements `ModelProvider` when its header — the text between its
     * name and the first `{` — lists `ModelProvider` among its supertypes. Depth-aware,
     * so a constructor parameter like `class X(val provider: ModelProvider, ...)` never
     * counts, while `class X(...) : ModelProvider, SomeBase(...)` (multiline or not) is
     * caught even when the base constructor's `)` comes after `ModelProvider`.
     */
    fun modelProviderImplementations(text: String): List<String> {
        val code = neutralize(text)
        return CLASS_HEADER
            .findAll(code)
            .mapNotNull { match ->
                val name = match.groupValues[1]
                val header = match.groupValues[2]
                if (supertypeSection(header).contains("ModelProvider")) name else null
            }.toList()
    }

    /** Implementations declared in [file]. */
    fun modelProviderImplementations(file: File): List<String> = modelProviderImplementations(file.readText())

    /** Everything after the first top-level `:` in a class header (the supertype list). */
    fun supertypeSection(header: String): String {
        var depth = 0
        for ((index, ch) in header.withIndex()) {
            when (ch) {
                '(' -> depth++
                ')' -> depth--
                ':' -> if (depth == 0) return header.substring(index + 1)
            }
        }
        return ""
    }

    /**
     * [text] with comments dropped and literal contents masked, leaving only code structure.
     *
     * Line breaks inside comments and literals are preserved so masked text keeps readable
     * line structure for diagnostics.
     */
    fun neutralize(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                }

                text.startsWith("/*", i) -> {
                    i = maskBlockComment(text, i, out)
                }

                text.startsWith(TRIPLE_QUOTE, i) -> {
                    i = maskLiteral(text, i, TRIPLE_QUOTE, out)
                }

                text[i] == '"' -> {
                    i = maskLiteral(text, i, "\"", out)
                }

                text[i] == '\'' -> {
                    i = maskLiteral(text, i, "'", out)
                }

                else -> {
                    out.append(text[i++])
                }
            }
        }
        return out.toString()
    }

    /**
     * Masks the body of the literal at [start] (terminator [quote]), keeping the delimiters and
     * whitespace/newlines in place of the contents.
     *
     * `"..."` and `'...'` honour backslash escapes; raw strings ([TRIPLE_QUOTE]) do not. Every
     * literal is interpolation-aware: a `${...}` expression may contain nested strings, chars,
     * comments or braces, and its contents are masked too, so a nested `"{"` cannot end the outer
     * literal early and leak structure back into the scan.
     */
    private fun maskLiteral(
        text: String,
        start: Int,
        quote: String,
        out: StringBuilder,
    ): Int {
        out.append(quote)
        var i = start + quote.length
        val raw = quote == TRIPLE_QUOTE
        while (i < text.length) {
            when {
                text.startsWith(quote, i) -> {
                    out.append(quote)
                    return i + quote.length
                }

                !raw && text[i] == '\\' && i + 1 < text.length -> {
                    out.append("  ")
                    i += 2
                }

                text.startsWith("\${", i) -> {
                    i = maskTemplateExpression(text, i, out)
                }

                else -> {
                    out.append(if (text[i] == '\n') '\n' else ' ')
                    i++
                }
            }
        }
        return i
    }

    /**
     * Masks the `${...}` expression starting at [start] (which points at the `$`), returning the
     * index just past its closing `}`.
     *
     * Nested strings, chars, comments and braces inside the expression are consumed lexically, so
     * the interpolation's end is found rather than guessed.
     */
    private fun maskTemplateExpression(
        text: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var depth = 1
        var i = start + 2
        while (i < text.length && depth > 0) {
            when {
                text.startsWith("/*", i) -> {
                    i = maskBlockComment(text, i, out)
                }

                text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }

                text.startsWith(TRIPLE_QUOTE, i) -> {
                    i = maskLiteral(text, i, TRIPLE_QUOTE, out)
                }

                text[i] == '"' -> {
                    i = maskLiteral(text, i, "\"", out)
                }

                text[i] == '\'' -> {
                    i = maskLiteral(text, i, "'", out)
                }

                text[i] == '{' -> {
                    depth++
                    out.append(' ')
                    i++
                }

                text[i] == '}' -> {
                    depth--
                    out.append(' ')
                    i++
                }

                else -> {
                    out.append(if (text[i] == '\n') '\n' else ' ')
                    i++
                }
            }
        }
        return i
    }

    /**
     * Masks the block comment starting at [start] (which points at `/`), returning the index just
     * past its close.
     *
     * Kotlin block comments **nest**, so the close is the one that brings the depth back to zero.
     * Stopping at the first terminator would leave a `class X : ModelProvider {` written between an
     * inner and an outer close sitting in code position, and the scanner would report it.
     */
    private fun maskBlockComment(
        text: String,
        start: Int,
        out: StringBuilder,
    ): Int {
        var depth = 0
        var i = start
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> {
                    depth++
                    out.append("  ")
                    i += 2
                }

                text.startsWith("*/", i) -> {
                    depth--
                    out.append("  ")
                    i += 2
                    if (depth == 0) return i
                }

                else -> {
                    out.append(if (text[i] == '\n') '\n' else ' ')
                    i++
                }
            }
        }
        return i
    }
}
