package dev.tramai.build.quality

/**
 * Scans raw Kotlin source text for broad exception catches in suspend-capable code.
 * Testable independently of file I/O and Gradle project model.
 *
 * Deterministic: same input always produces same output regardless of execution context.
 */
object KotlinCancellationCatchScanner {
    private val broadCatchPatterns =
        listOf(
            Regex(
                """catch\s*\(\s*(?:[A-Za-z_][A-Za-z0-9_]*|_)\s*:\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:Exception|Throwable|RuntimeException)\s*\)""",
            ),
            Regex("""runCatching\s*\{"""),
        )

    private val suspendPatterns =
        listOf(
            Regex("""\bsuspend\s+fun\b"""),
            Regex("""\bsuspend\s*\{"""),
            Regex("""\bsuspend\s+\("""),
        )

    /**
     * Result of line joining: [joined] lines with their [originalIndices] mapping
     * each joined index back to the first original line that contributed to it.
     */
    private data class JoinedLines(
        val lines: List<String>,
        // joined[i] started at original[originalIndices[i]]
        val originalIndices: List<Int>,
    )

    fun scan(
        source: String,
        module: String,
        file: String,
    ): List<CancellationCatchFinding> {
        val findings = mutableListOf<CancellationCatchFinding>()
        val lines = source.lines()
        val suspendRanges = findSuspendRanges(lines)
        // Only built when a broad catch actually needs sibling resolution.
        val catchIndex by lazy { buildSourceIndex(source) }

        // Join multiline catches while tracking original line positions
        val joined = joinCatchLines(lines)

        for ((joinedIdx, line) in joined.lines.withIndex()) {
            val originalLineIdx = joined.originalIndices[joinedIdx]
            val originalLineNum = originalLineIdx + 1 // 1-indexed for suspend range check

            // Skip comment lines
            val trimmed = line.trim()
            if (trimmed.startsWith("//")) continue
            if (trimmed.startsWith("*") && !trimmed.contains("*/")) continue
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) continue

            for ((patternIdx, pattern) in broadCatchPatterns.withIndex()) {
                val match = pattern.find(line) ?: continue
                // Skip matches inside string literals
                if (isInsideStringLiteral(line, match.range.first)) continue

                val inSuspend = suspendRanges.any { originalLineNum in it }
                val functionName = findEnclosingFunction(lines, originalLineIdx)

                val catchType =
                    when {
                        line.contains("Throwable") -> "Throwable"
                        line.contains("RuntimeException") -> "RuntimeException"
                        line.contains("Exception") -> "Exception"
                        line.contains("runCatching") -> "runCatching"
                        else -> "unknown"
                    }

                // patternIdx 0 is the `catch (…: Exception|Throwable|RuntimeException)`
                // clause — the only shape that can share a try with a sibling
                // catch clause. runCatching has no sibling to be rescued by.
                val preservesCancellationViaSiblingCatch =
                    patternIdx == 0 &&
                        checkEarlierSiblingPreservesCancellation(source, catchIndex, originalLineIdx)
                val rethrowsCancellation =
                    checkRethrowsCancellation(lines, originalLineIdx) || preservesCancellationViaSiblingCatch
                val transformsException = checkTransformsException(lines, originalLineIdx)

                val risk =
                    when {
                        rethrowsCancellation -> "accepted"
                        inSuspend && transformsException -> "high"
                        inSuspend -> "critical"
                        !inSuspend -> "medium"
                        else -> "medium"
                    }

                findings.add(
                    CancellationCatchFinding(
                        module = module,
                        file = file,
                        function = functionName,
                        catchType = catchType,
                        isSuspendCapable = inSuspend,
                        rethrowsCancellation = rethrowsCancellation,
                        transformsException = transformsException,
                        risk = risk,
                        sourceLine = originalLineNum,
                        sourceFingerprint = fingerprintConstruct(lines, originalLineIdx, match),
                    ),
                )
            }
        }

        return findings
            .sortedByDescending { riskWeight(it.risk) }
            // Deduplicate: same (module, file, function, catchType, sourceLine) → keep worst risk
            .distinctBy { "${it.module}::${it.file}::${it.function}::${it.catchType}::${it.sourceLine}" }
    }

    /**
     * Joins multiline catch declarations while tracking original line positions.
     * Kotlin allows:
     *   catch (
     *       e: Exception
     *   ) {
     * This normalizes them to single lines for pattern matching but preserves
     * the original starting line index for position-dependent checks.
     */
    private fun joinCatchLines(lines: List<String>): JoinedLines {
        val resultLines = mutableListOf<String>()
        val resultIndices = mutableListOf<Int>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            // Match lines that contain a catch keyword followed by `(` without a closing `)`.
            // Common patterns: `catch (`, `} catch (`, `} catch (`
            val catchStart = trimmed.indexOf("catch")
            val isMultiLineCatch =
                catchStart >= 0 &&
                    trimmed.substring(catchStart).let { after ->
                        after.startsWith("catch") && after.contains("(") && !after.contains(")")
                    }
            if (isMultiLineCatch) {
                val sb = StringBuilder(lines[i])
                val startIdx = i
                i++
                while (i < lines.size) {
                    sb.append(" ").append(lines[i].trim())
                    if (lines[i].contains(")")) break
                    i++
                }
                resultLines.add(sb.toString())
                resultIndices.add(startIdx)
            } else {
                resultLines.add(lines[i])
                resultIndices.add(i)
            }
            i++
        }
        return JoinedLines(resultLines, resultIndices)
    }

    private fun findSuspendRanges(lines: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            if (suspendPatterns[0].containsMatchIn(lines[i]) ||
                suspendPatterns[1].containsMatchIn(lines[i]) ||
                suspendPatterns[2].containsMatchIn(lines[i])
            ) {
                val start = i + 1
                val end = findBlockEnd(lines, i)
                ranges.add(start..end)
                // findBlockEnd returns one past the closing brace; stepping to
                // end-1 lets the loop's i++ land ON end, so a suspend fun whose
                // declaration sits immediately after a block is not skipped.
                i = end - 1
            }
            i++
        }
        return ranges
    }

    /** Finds matching closing brace accounting for nested braces. */
    private fun findBlockEnd(
        lines: List<String>,
        startIdx: Int,
    ): Int {
        var braceCount = 0
        var found = false
        for (i in startIdx until lines.size) {
            braceCount += lines[i].count { it == '{' }
            braceCount -= lines[i].count { it == '}' }
            if (braceCount > 0) found = true
            if (found && braceCount == 0) return i + 1
        }
        return startIdx + 1
    }

    private fun findEnclosingFunction(
        lines: List<String>,
        idx: Int,
    ): String {
        for (i in idx downTo 0) {
            val funMatch = Regex("""fun\s+(\w+)""").find(lines[i].trim())
            if (funMatch != null) return funMatch.groupValues[1]
        }
        return "<unknown>"
    }

    /**
     * Conservative source-content fingerprint of the ENTIRE broad-catch
     * construct: from the line containing the catch/runCatching site through
     * its balanced body closing brace. Brace balancing is lexical-state-aware:
     * braces inside line comments, nested block comments, regular strings,
     * char literals and multi-line raw strings never change the depth. If the
     * source cannot be confidently balanced (unterminated comment or string),
     * returns an empty fingerprint — Phase 2 then refuses relocation, the
     * safe direction for a safety gate.
     *
     * [match] anchors the construct to the ACTUAL broad-catch finding being
     * emitted — balancing starts at the matched keyword, so an outer catch
     * whose opening line also contains an inner runCatching balances the
     * outer construct, never the inner one.
     *
     * Normalization is deliberately minimal — each line is trimmed at the
     * edges and blank lines dropped, but comments and string literal contents
     * are preserved verbatim. Two different pieces of executable code cannot
     * collapse to the same fingerprint. A formatting-only difference producing
     * a fingerprint mismatch (false positive) is also the safe direction.
     *
     * Not persisted (see @JsonIgnore on CancellationCatchFinding).
     */
    private fun fingerprintConstruct(
        lines: List<String>,
        startIdx: Int,
        match: MatchResult,
    ): String {
        val endIdx = findConstructEndIdx(lines, startIdx, match) ?: return ""
        return (startIdx..endIdx)
            .map { lines[it].trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private enum class LexState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, RAW_STRING }

    /**
     * Finds the index of the line that closes the construct opening at
     * [startIdx], or null when the construct never closes or the source ends
     * inside a comment/string that cannot be balanced. Brace balancing starts
     * at the position of the matched keyword in [match] (the actual finding's
     * anchor), so a `try { ... } catch` on the same line does not close the
     * construct early and an inner runCatching on the catch's opening line is
     * not mistaken for the outer construct. Only structural braces in normal
     * code change the depth; braces in comments (nested block comments
     * included), strings, char literals and raw strings are ignored, with
     * state carried across lines.
     */
    private fun findConstructEndIdx(
        lines: List<String>,
        startIdx: Int,
        match: MatchResult,
    ): Int? {
        var depth = 0
        var opened = false
        var state = LexState.CODE
        var blockCommentDepth = 0
        // Anchor: the matched keyword's position. The match may be on a joined
        // line; for joined multiline catches the first original line contains
        // the keyword at the same offset, so this is still a valid anchor.
        var pos = match.range.first.coerceIn(0, lines[startIdx].length)
        for (i in startIdx until lines.size) {
            val line = lines[i]
            val lineStart = if (i == startIdx) pos else 0
            var j = lineStart
            while (j < line.length) {
                val c = line[j]
                when (state) {
                    LexState.CODE -> {
                        when {
                            c == '/' && j + 1 < line.length && line[j + 1] == '/' -> {
                                state = LexState.LINE_COMMENT
                                j++
                            }

                            c == '/' && j + 1 < line.length && line[j + 1] == '*' -> {
                                state = LexState.BLOCK_COMMENT
                                blockCommentDepth = 1
                                j++
                            }

                            c == '"' && j + 2 < line.length && line[j + 1] == '"' && line[j + 2] == '"' -> {
                                state = LexState.RAW_STRING
                                j += 2
                            }

                            c == '"' -> {
                                state = LexState.STRING
                            }

                            c == '\'' -> {
                                state = LexState.CHAR
                            }

                            c == '{' -> {
                                depth++
                                opened = true
                            }

                            c == '}' -> {
                                if (opened) {
                                    depth--
                                    if (depth == 0) return i
                                }
                            }

                            else -> {}
                        }
                    }

                    LexState.LINE_COMMENT -> { /* skip to end of line */ }

                    LexState.BLOCK_COMMENT -> {
                        when {
                            c == '/' && j + 1 < line.length && line[j + 1] == '*' -> {
                                // Kotlin block comments nest
                                blockCommentDepth++
                                j++
                            }

                            c == '*' && j + 1 < line.length && line[j + 1] == '/' -> {
                                blockCommentDepth--
                                if (blockCommentDepth == 0) state = LexState.CODE
                                j++
                            }

                            else -> {}
                        }
                    }

                    LexState.STRING, LexState.CHAR -> {
                        if (c == '\\') {
                            j++ // skip escaped char
                        } else if ((state == LexState.STRING && c == '"') || (state == LexState.CHAR && c == '\'')) {
                            state = LexState.CODE
                        }
                    }

                    LexState.RAW_STRING -> {
                        if (c == '"' && j + 2 < line.length && line[j + 1] == '"' && line[j + 2] == '"') {
                            state = LexState.CODE
                            j += 2
                        }
                    }
                }
                j++
            }
            if (state == LexState.LINE_COMMENT) state = LexState.CODE
        }
        // Construct never closed, or source ends inside a comment/string we
        // cannot balance → refuse relocation (blank fingerprint).
        return null
    }

    /** Extract the catch variable name from a catch declaration line. */
    private fun extractCatchVariable(line: String): String? {
        val match = Regex("""catch\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*:""").find(line)
        return match?.groupValues?.get(1)
    }

    /**
     * Strips inline comments (// ...) from a line of source code.
     * Does NOT handle block comments correctly — those are assumed to be
     * on their own lines (and already skipped by the caller).
     */
    private fun stripComment(line: String): String {
        val idx = line.indexOf("//")
        return if (idx >= 0) line.substring(0, idx) else line
    }

    /** Check if the catch block rethrows CancellationException (within catch body, including nested blocks).
     *  Requires that the caught exception variable itself is rethrown AND the throw is inside the
     *  same branch as the CancellationException check, not merely nearby.
     *  Also recognizes `catchVar.rethrowIfCancellation()` as a safe pattern.
     *  For the rethrowIfCancellation() helper, it must be the FIRST non-blank, non-comment
     *  line in the catch body — before it, only blank lines and comments are allowed. */
    private fun checkRethrowsCancellation(
        lines: List<String>,
        catchIdx: Int,
    ): Boolean {
        val catchVar = extractCatchVariable(lines[catchIdx]) ?: return false
        val catchBodyEnd = findCatchBodyEnd(lines, catchIdx)
        val end = minOf(catchBodyEnd, lines.size)

        // Pattern: <catchVar>.rethrowIfCancellation() — extension-based cancellation rethrow
        val rethrowIfCancellationPattern =
            Regex(
                """\b${Regex.escape(catchVar)}\.rethrowIfCancellation\s*\(\s*\)""",
            )

        // Check rethrowIfCancellation() — must be first non-blank, non-comment line
        var foundHelper = false
        for (i in catchIdx + 1 until end) {
            val stripped = stripComment(lines[i])
            val trimmed = stripped.trim()
            if (trimmed.isBlank()) continue
            // Skip multi-line block comment starts and line-comment-only lines
            if (trimmed.startsWith("//")) continue
            if (trimmed.startsWith("*") && !trimmed.contains("*/")) continue
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) continue
            // This is the first real statement — check if the ENTIRE line is
            // just: <catchVar>.rethrowIfCancellation()  (with optional trailing semicolon)
            val fullLinePattern =
                Regex(
                    """^\s*${Regex.escape(catchVar)}\.rethrowIfCancellation\s*\(\s*\)\s*;?\s*$""",
                )
            if (fullLinePattern.matches(trimmed)) {
                foundHelper = true
            }
            break
        }
        if (foundHelper) return true

        // Pattern: throw <catchVar> (exact variable rethrow)
        val throwVarPattern = Regex("""\bthrow\s+${Regex.escape(catchVar)}\b""")
        // Pattern: if (variable is [...][.]CancellationException[...])
        val cancellationIfPattern =
            Regex(
                """\bif\s*\(\s*${Regex.escape(catchVar)}\s+is\s+[A-Za-z_.]*CancellationException""",
            )

        for (i in catchIdx + 1 until end) {
            val stripped = stripComment(lines[i])
            if (stripped.isBlank()) continue

            // Only consider lines that are checking for CancellationException on our caught variable
            val cancellationCheck = cancellationIfPattern.find(stripped)
            if (cancellationCheck == null) continue

            // Found an if (var is CancellationException) — now check if the matching block
            // (the brace-balanced range) or the same line contains `throw var`
            // Handle inline: `if (e is CancellationException) throw e` — check same line
            val restOfLine = stripped.substring(cancellationCheck.range.last + 1)
            if (throwVarPattern.containsMatchIn(restOfLine)) return true

            // Handle block: `if (e is CancellationException) { throw e }`
            val ifBlockEnd = findBlockAfterIf(lines, i, end)
            if (ifBlockEnd < 0) continue

            // Search inside the if-block for `throw var`
            for (j in i + 1 until minOf(ifBlockEnd, end)) {
                if (throwVarPattern.containsMatchIn(stripComment(lines[j]))) return true
            }
        }

        return false
    }

    /**
     * Lexically-resolved structure of one source text: bracket pairs in both
     * directions (closer→opener and opener→closer), plus a mask marking which
     * offsets are executable code (never inside a comment or a literal).
     * Sibling-catch recognition must not be fooled by braces, parens or
     * keywords that sit inside comments and strings.
     */
    private class SourceIndex(
        val openOffsets: Map<Int, Int>,
        val closeOffsets: Map<Int, Int>,
        val codeMask: BooleanArray,
        val lineStarts: IntArray,
    )

    /** Single forward pass: bracket pairing, code mask and line starts. */
    private fun buildSourceIndex(source: String): SourceIndex {
        val openOffsets = HashMap<Int, Int>()
        val closeOffsets = HashMap<Int, Int>()
        val codeMask = BooleanArray(source.length) { true }
        scanSourceStructure(source, openOffsets, closeOffsets, codeMask)
        return SourceIndex(openOffsets, closeOffsets, codeMask, buildLineStarts(source))
    }

    /**
     * The lexical walk: bracket pairing and code mask. Owns every piece of lexical state
     * (stack, state, blockCommentDepth, spanStart, i) plus span closing; the output containers
     * are passed in and written exactly as before.
     */
    private fun scanSourceStructure(
        source: String,
        openOffsets: HashMap<Int, Int>,
        closeOffsets: HashMap<Int, Int>,
        codeMask: BooleanArray,
    ) {
        val stack = ArrayDeque<Int>()
        var state = LexState.CODE
        var blockCommentDepth = 0
        var spanStart = -1

        fun closeSpan(endExclusive: Int) {
            if (spanStart >= 0) {
                for (k in spanStart until minOf(endExclusive, source.length)) codeMask[k] = false
                spanStart = -1
            }
        }
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when (state) {
                LexState.CODE -> {
                    when {
                        c == '/' && i + 1 < source.length && source[i + 1] == '/' -> {
                            spanStart = i
                            state = LexState.LINE_COMMENT
                            i++
                        }

                        c == '/' && i + 1 < source.length && source[i + 1] == '*' -> {
                            spanStart = i
                            state = LexState.BLOCK_COMMENT
                            blockCommentDepth = 1
                            i++
                        }

                        c == '"' && i + 2 < source.length && source[i + 1] == '"' && source[i + 2] == '"' -> {
                            spanStart = i
                            state = LexState.RAW_STRING
                            i += 2
                        }

                        c == '"' -> {
                            spanStart = i
                            state = LexState.STRING
                        }

                        c == '\'' -> {
                            spanStart = i
                            state = LexState.CHAR
                        }

                        c == '(' || c == '{' -> {
                            stack.addLast(i)
                        }

                        c == ')' || c == '}' -> {
                            stack.removeLastOrNull()?.let {
                                openOffsets[i] = it
                                closeOffsets[it] = i
                            }
                        }

                        else -> {}
                    }
                }

                LexState.LINE_COMMENT -> {
                    val transition = advanceLineComment(source, i)
                    transition.closeSpanAt?.let { closeSpan(it) }
                    state = transition.state
                    i += transition.consumed
                }

                LexState.BLOCK_COMMENT -> {
                    val transition = advanceBlockComment(source, i, blockCommentDepth)
                    transition.blockCommentDepth?.let { blockCommentDepth = it }
                    transition.closeSpanAt?.let { closeSpan(it) }
                    state = transition.state
                    i += transition.consumed
                }

                LexState.STRING, LexState.CHAR -> {
                    val transition = advanceQuoted(source, i, state)
                    transition.closeSpanAt?.let { closeSpan(it) }
                    state = transition.state
                    i += transition.consumed
                }

                LexState.RAW_STRING -> {
                    val transition = advanceRawString(source, i)
                    transition.closeSpanAt?.let { closeSpan(it) }
                    state = transition.state
                    i += transition.consumed
                }
            }
            i++
        }
        closeSpan(source.length)
    }

    /** Minimal state-transition description for a single lexical step. */
    private data class LexTransition(
        val state: LexState,
        val consumed: Int = 0,
        val closeSpanAt: Int? = null,
        // null = this transition does not alter depth; a value = assign exactly this depth.
        // Deliberately not a delta and not a command.
        val blockCommentDepth: Int? = null,
    )

    /**
     * LINE_COMMENT handling for the character at absolute [offset]: a newline closes the span at
     * that offset and returns to code; any other character stays inside the line comment.
     */
    private fun advanceLineComment(source: String, offset: Int): LexTransition =
        if (source[offset] == '\n') {
            LexTransition(state = LexState.CODE, closeSpanAt = offset)
        } else {
            LexTransition(LexState.LINE_COMMENT)
        }

    /**
     * RAW_STRING handling for the character at absolute [offset]: a closing triple quote consumes
     * two further characters and closes the span three characters past the delimiter start. The
     * asymmetry against [advanceLineComment] is the original behaviour, preserved literally.
     */
    private fun advanceRawString(source: String, offset: Int): LexTransition =
        if (startsRawStringAt(source, offset)) {
            LexTransition(state = LexState.CODE, consumed = 2, closeSpanAt = offset + 3)
        } else {
            LexTransition(LexState.RAW_STRING)
        }

    /**
     * STRING/CHAR handling for the character at absolute [offset] while in [state]: a backslash
     * consumes the escaped character, the matching quote closes the span one past [offset], and any
     * other character stays inside the literal. The branch is shared by both states, so the incoming
     * state is the only extra input — no new transition fields are needed.
     */
    private fun advanceQuoted(source: String, offset: Int, state: LexState): LexTransition =
        when {
            source[offset] == '\\' -> LexTransition(state = state, consumed = 1)
            state == LexState.STRING && source[offset] == '"' -> {
                LexTransition(state = LexState.CODE, closeSpanAt = offset + 1)
            }

            state == LexState.CHAR && source[offset] == '\'' -> {
                LexTransition(state = LexState.CODE, closeSpanAt = offset + 1)
            }

            else -> LexTransition(state = state)
        }

    /** True when a nested block-comment opener starts at [offset]. */
    private fun startsBlockCommentAt(source: String, offset: Int): Boolean =
        source.startsWith("/*", offset)

    /** True when a block-comment closer starts at [offset]. */
    private fun endsBlockCommentAt(source: String, offset: Int): Boolean =
        source.startsWith("*/", offset)

    /**
     * BLOCK_COMMENT handling for the character at absolute [offset] at nesting [depth]: `/*` nests
     * one level deeper, `*/` unwinds one level and returns to code when the outermost closes. Both
     * delimiter cases consume one further character, matching the original inner i++.
     */
    private fun advanceBlockComment(source: String, offset: Int, depth: Int): LexTransition =
        when {
            startsBlockCommentAt(source, offset) ->
                LexTransition(
                    state = LexState.BLOCK_COMMENT,
                    consumed = 1,
                    blockCommentDepth = depth + 1,
                )

            endsBlockCommentAt(source, offset) ->
                if (depth == 1) {
                    LexTransition(
                        state = LexState.CODE,
                        consumed = 1,
                        closeSpanAt = offset + 1,
                        blockCommentDepth = 0,
                    )
                } else {
                    LexTransition(
                        state = LexState.BLOCK_COMMENT,
                        consumed = 1,
                        blockCommentDepth = depth - 1,
                    )
                }

            else -> LexTransition(state = LexState.BLOCK_COMMENT)
        }

    /**
     * Line start offsets of [source], including offset 0. Independent preparation pass: it reads
     * nothing from and writes nothing into the lexical walk, so its equivalence can be proven
     * separately from the state machine.
     */
    private fun buildLineStarts(source: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        source.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
        return starts.toIntArray()
    }

    /**
     * True when a triple-quote raw-string delimiter starts at [offset]. Pure: reads [source] only.
     * Extracted from the lexical walk so the close condition keeps its original operands (bounds
     * check plus delimiter match) without tripping the condition-complexity threshold.
     */
    private fun startsRawStringAt(source: String, offset: Int): Boolean =
        offset + 2 < source.length && source.startsWith("\"\"\"", offset)

    /** Previous executable-code offset before [offset], or -1. */
    private fun previousCodeOffset(
        source: String,
        index: SourceIndex,
        offset: Int,
    ): Int {
        var i = minOf(offset, source.length) - 1
        while (i >= 0 && (!index.codeMask[i] || source[i].isWhitespace())) i--
        return i
    }

    /** First [target] code character at/after [from]; null if other code intervenes. */
    private fun codeOffsetOf(
        source: String,
        index: SourceIndex,
        from: Int,
        target: Char,
    ): Int? {
        var i = from
        while (i < source.length) {
            if (index.codeMask[i]) {
                val c = source[i]
                if (c == target) return i
                if (!c.isWhitespace()) return null
            }
            i++
        }
        return null
    }

    /** True when [keyword] ends exactly at [offset] as a standalone token. */
    private fun keywordEndsAt(
        source: String,
        offset: Int,
        keyword: String,
    ): Boolean {
        val start = offset - keyword.length + 1
        if (start < 0 || !source.regionMatches(start, keyword, 0, keyword.length)) return false
        return start == 0 || !(source[start - 1].isLetterOrDigit() || source[start - 1] == '_')
    }

    /**
     * Offset of the `catch` keyword of the clause that produced a finding on
     * [lineIdx]. ponytail: takes the last `catch` token on the line — source in
     * this repo is one catch clause per line; a compact multi-catch line would
     * resolve to the wrong clause and degrade to "not accepted".
     */
    private fun catchKeywordOffset(
        source: String,
        index: SourceIndex,
        lineIdx: Int,
    ): Int? {
        if (lineIdx !in index.lineStarts.indices) return null
        val lineStart = index.lineStarts[lineIdx]
        val lineEnd = if (lineIdx + 1 < index.lineStarts.size) index.lineStarts[lineIdx + 1] else source.length
        return Regex("""\bcatch\b""")
            .findAll(source.substring(lineStart, lineEnd))
            .map { lineStart + it.range.first }
            .filter { it < source.length && index.codeMask[it] }
            .lastOrNull()
    }

    /**
     * True when the broad catch clause on [catchLineIdx] shares its `try`
     * expression with an EARLIER sibling catch clause that catches
     * CancellationException (bare or qualified) and ultimately throws that
     * clause's own catch variable — cleanup statements before the throw are
     * allowed. Cancellation is then preserved by the try statement as a whole,
     * so the broad catch is cancellation-safe.
     *
     * Resolved structurally, not by a file-wide regex: the chain of catch
     * clauses is walked backwards through lexically balanced braces/parens until
     * the `try` keyword, so a CancellationException catch belonging to a nested
     * try inside this clause's body, or to an unrelated try, or appearing AFTER
     * this clause is never mistaken for a sibling. Kotlin itself rejects some of
     * those orderings at compile time; the gate decides from syntax alone.
     */
    private fun checkEarlierSiblingPreservesCancellation(
        source: String,
        index: SourceIndex,
        catchLineIdx: Int,
    ): Boolean {
        var keywordOffset = catchKeywordOffset(source, index, catchLineIdx) ?: return false
        while (true) {
            // The construct directly before a sibling catch is the closing brace
            // of the preceding catch body (or of the `try` block for the first one).
            val closer = previousCodeOffset(source, index, keywordOffset)
            if (closer < 0 || source[closer] != '}') return false
            val blockOpen = index.openOffsets[closer] ?: return false
            val beforeBlock = previousCodeOffset(source, index, blockOpen)
            if (beforeBlock < 0 || source[beforeBlock] != ')') return false
            val paramOpen = index.openOffsets[beforeBlock] ?: return false
            val beforeParams = previousCodeOffset(source, index, paramOpen)
            if (!keywordEndsAt(source, beforeParams, "catch")) return false
            val siblingKeyword = beforeParams - "catch".length + 1
            if (catchClauseRethrowsItsOwnVariable(source, index, siblingKeyword)) return true
            keywordOffset = siblingKeyword
        }
    }

    /**
     * True when the catch clause opening at [keywordOffset] catches
     * CancellationException and its body throws that clause's own catch
     * variable as a standalone statement. A different variable, a new
     * exception, a transformation (`throw c.cause`) or no throw at all are all
     * rejected.
     */
    private fun catchClauseRethrowsItsOwnVariable(
        source: String,
        index: SourceIndex,
        keywordOffset: Int,
    ): Boolean {
        val paramOpen = codeOffsetOf(source, index, keywordOffset + "catch".length, '(') ?: return false
        val paramClose = index.closeOffsets[paramOpen] ?: return false
        val parameter = source.substring(paramOpen + 1, paramClose).trim()
        val parsed = catchParameterPattern.find(parameter) ?: return false
        val variable = parsed.groupValues[1]
        if (parsed.groupValues[2].substringAfterLast('.') != "CancellationException") return false

        val bodyOpen = codeOffsetOf(source, index, paramClose + 1, '{') ?: return false
        val bodyClose = index.closeOffsets[bodyOpen] ?: return false
        val throwOwnVariable = Regex("""\bthrow\s+${Regex.escape(variable)}\s*[;}]?\s*$""")
        return source
            .substring(bodyOpen, bodyClose + 1)
            .lines()
            .any { throwOwnVariable.containsMatchIn(stripComment(it)) }
    }

    /** `variable: Type` of a catch parameter list. */
    private val catchParameterPattern = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([A-Za-z_][A-Za-z0-9_.]*)$""")

    /**
     * Find the end of the block that follows an `if (...)` statement.
     * Returns the index of the closing `}` (exclusive), or -1 if not found.
     * Handles: `if (cond) statement` (single-line, no brace) and `if (cond) { ... }`.
     */
    private fun findBlockAfterIf(
        lines: List<String>,
        ifIdx: Int,
        limit: Int,
    ): Int {
        val ifLine = lines[ifIdx]
        val braceIdx = ifLine.indexOf('{')
        if (braceIdx >= 0) {
            // Block form: `if (cond) {` or `if (cond) { ... }`
            // Count opening braces on the if line, then balance forward
            var braceCount = 1
            for (i in ifIdx + 1 until limit) {
                braceCount += lines[i].count { it == '{' }
                braceCount -= lines[i].count { it == '}' }
                if (braceCount <= 0) return i
            }
        }
        return -1
    }

    /** Find the end of the catch body (closing brace of the catch block).
     *  Counts only `{` from the catch line (ignoring `}` which may close the try block),
     *  then balances from the next line onward. */
    private fun findCatchBodyEnd(
        lines: List<String>,
        catchIdx: Int,
    ): Int {
        // Count opening braces on the catch line (ignore closes — they belong to try/if blocks)
        var braceCount = lines[catchIdx].count { it == '{' }
        if (braceCount == 0) {
            // No opening brace on catch line — look for it on the next line
            for (i in catchIdx + 1 until lines.size) {
                braceCount += lines[i].count { it == '{' }
                braceCount -= lines[i].count { it == '}' }
                if (braceCount > 0) break
                if (braceCount < 0) return i
            }
        }
        // Now balance until we hit 0
        for (i in catchIdx + 1 until lines.size) {
            braceCount += lines[i].count { it == '{' }
            braceCount -= lines[i].count { it == '}' }
            if (braceCount <= 0) return i
        }
        return lines.size
    }

    /** Returns true if the given position on the line is inside a string literal. */
    private fun isInsideStringLiteral(
        line: String,
        pos: Int,
    ): Boolean {
        var inString = false
        var quoteChar: Char? = null
        var escaped = false
        for (i in 0 until pos) {
            if (i >= line.length) break
            val ch = line[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (inString) {
                if (ch == quoteChar) {
                    inString = false
                    quoteChar = null
                }
            } else {
                if (ch == '\"' || ch == '\'') {
                    inString = true
                    quoteChar = ch
                }
            }
        }
        return inString
    }

    private fun checkTransformsException(
        lines: List<String>,
        catchIdx: Int,
    ): Boolean {
        val catchBodyEnd = findCatchBodyEnd(lines, catchIdx)
        val end = minOf(catchBodyEnd, lines.size)
        for (i in catchIdx + 1 until end) {
            if (Regex("""throw\s+\w+Exception""").containsMatchIn(lines[i])) {
                return true
            }
        }
        return false
    }

    private fun riskWeight(risk: String): Int =
        when (risk) {
            "critical" -> 4
            "high" -> 3
            "medium" -> 2
            "accepted" -> 1
            else -> 0
        }
}
