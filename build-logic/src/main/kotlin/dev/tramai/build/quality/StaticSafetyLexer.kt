package dev.tramai.build.quality

internal enum class Kind { ID, PUNCT }

internal data class Tok(
    val text: String,
    val line: Int,
    val offset: Int,
    val kind: Kind,
)

/**
 * Token-aware lexer. Skips comments and string bodies but still lexes
 * executable `${...}` interpolation (a forbidden call inside a string
 * interpolation is a real call site, not documentation).
 */
internal class StaticSafetyLexer(
    private val s: String,
) {
    fun lex(): List<Tok> = lexRange(0, s.length, 1)

    private data class Step(
        val tokens: List<Tok>,
        val next: Int,
        val line: Int,
        val block: Int,
    )

    private fun lexRange(
        from: Int,
        until: Int,
        startLine: Int,
    ): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = from
        var line = startLine
        var block = 0
        while (i < until) {
            val step = step(i, line, block, until)
            out += step.tokens
            i = step.next
            line = step.line
            block = step.block
        }
        return out
    }

    private fun step(
        i: Int,
        line: Int,
        block: Int,
        until: Int,
    ): Step {
        val c = s[i]
        if (block > 0) return blockStep(i, line, block, until)
        return when {
            i + 1 < until && s.startsWith("//", i) -> lineStep(i, line, until)
            i + 1 < until && s.startsWith("/*", i) -> Step(emptyList(), i + 2, line, 1)
            s.startsWith("\"\"\"", i) -> rawStringStep(i, line, until)
            c == '"' || c == '\'' -> stringStep(i, line, until)
            isId(c) -> idStep(i, line, until)
            c in ".(){}" -> Step(listOf(Tok(c.toString(), line, i, Kind.PUNCT)), i + 1, line, block)
            c == '\n' -> Step(emptyList(), i + 1, line + 1, block)
            else -> Step(emptyList(), i + 1, line, block)
        }
    }

    private fun blockStep(
        i: Int,
        line: Int,
        block: Int,
        until: Int,
    ): Step {
        var j = i
        var l = line
        var b = block
        when {
            j + 1 < until && s.startsWith("/*", j) -> {
                b++
                j += ESCAPE_STEP
            }

            j + 1 < until && s.startsWith("*/", j) -> {
                b--
                j += ESCAPE_STEP
            }

            else -> {
                if (s[j] == '\n') l++
                j++
            }
        }
        return Step(emptyList(), j, l, b)
    }

    private fun lineStep(
        i: Int,
        line: Int,
        until: Int,
    ): Step {
        var j = i + ESCAPE_STEP
        while (j < until && s[j] != '\n') j++
        return Step(emptyList(), j, line, 0)
    }

    private fun idStep(
        i: Int,
        line: Int,
        until: Int,
    ): Step {
        var j = i + 1
        while (j < until && isId(s[j])) j++
        return Step(listOf(Tok(s.substring(i, j), line, i, Kind.ID)), j, line, 0)
    }

    private fun stringStep(
        i: Int,
        line: Int,
        until: Int,
    ): Step {
        val q = s[i]
        var j = i + 1
        var l = line
        val tokens = mutableListOf<Tok>()
        while (j < until && s[j] != q) {
            when {
                s[j] == '\\' -> {
                    j += ESCAPE_STEP
                }

                s[j] == '\n' -> {
                    l++
                    j++
                }

                s[j] == '$' && j + 1 < until && s[j + 1] == '{' -> {
                    val end = interpolationEnd(j + ESCAPE_STEP, until, l)
                    tokens += lexRange(j + ESCAPE_STEP, end.first, l)
                    l = end.second
                    j = end.first
                }

                else -> {
                    j++
                }
            }
        }
        return Step(tokens, j + 1, l, 0)
    }

    private fun rawStringStep(
        i: Int,
        line: Int,
        until: Int,
    ): Step {
        var j = i + RAW_QUOTE_LEN
        var l = line
        val tokens = mutableListOf<Tok>()
        while (j + 2 < until && !s.startsWith("\"\"\"", j)) {
            when {
                s[j] == '\n' -> {
                    l++
                    j++
                }

                s[j] == '$' && j + 1 < until && s[j + 1] == '{' -> {
                    val end = interpolationEnd(j + ESCAPE_STEP, until, l)
                    tokens += lexRange(j + ESCAPE_STEP, end.first, l)
                    l = end.second
                    j = end.first
                }

                else -> {
                    j++
                }
            }
        }
        return Step(tokens, j + RAW_QUOTE_LEN, l, 0)
    }

    /** Returns (index after the matching '}', absolute line after the content) for `${...}` starting at from. */
    private fun interpolationEnd(
        from: Int,
        until: Int,
        startLine: Int,
    ): Pair<Int, Int> {
        var d = 0
        var i = from
        var line = startLine
        while (i < until) {
            when (s[i]) {
                '\n' -> {
                    line++
                }

                '"', '\'' -> {
                    i = quotedEnd(s, i, until) - 1
                }

                '{' -> {
                    d++
                }

                '}' -> {
                    if (d == 0) {
                        i++
                        break
                    } else {
                        d--
                    }
                }
            }
            i++
        }
        return i to line
    }
}

private const val ESCAPE_STEP = 2
private const val RAW_QUOTE_LEN = 3

private fun isId(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

private fun quotedEnd(
    s: String,
    i: Int,
    until: Int,
): Int {
    val q = s[i]
    var j = i + 1
    while (j < until && s[j] != q) {
        if (s[j] == '\\') {
            j += ESCAPE_STEP
        } else {
            j++
        }
    }
    return j + 1
}
