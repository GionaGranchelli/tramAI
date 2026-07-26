package dev.tramai.build.quality

/**
 * Scans raw Kotlin source text for broad exception catches in suspend-capable code.
 * Testable independently of file I/O and Gradle project model.
 *
 * Deterministic: same input always produces same output regardless of execution context.
 */
object KotlinCancellationCatchScanner {

    private val broadCatchPatterns = listOf(
        Regex(
            """catch\s*\(\s*(?:[A-Za-z_][A-Za-z0-9_]*|_)\s*:\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:Exception|Throwable|RuntimeException)\s*\)"""
        ),
        Regex("""runCatching\s*\{""")
    )

    private val suspendPatterns = listOf(
        Regex("""\bsuspend\s+fun\b"""),
        Regex("""\bsuspend\s*\{"""),
        Regex("""\bsuspend\s+\(""")
    )

    /**
     * Result of line joining: [joined] lines with their [originalIndices] mapping
     * each joined index back to the first original line that contributed to it.
     */
    private data class JoinedLines(
        val lines: List<String>,
        val originalIndices: List<Int>  // joined[i] started at original[originalIndices[i]]
    )

    fun scan(source: String, module: String, file: String): List<CancellationCatchFinding> {
        val findings = mutableListOf<CancellationCatchFinding>()
        val lines = source.lines()
        val suspendRanges = findSuspendRanges(lines)

        // Join multiline catches while tracking original line positions
        val joined = joinCatchLines(lines)

        for ((joinedIdx, line) in joined.lines.withIndex()) {
            val originalLineIdx = joined.originalIndices[joinedIdx]
            val originalLineNum = originalLineIdx + 1  // 1-indexed for suspend range check

            // Skip comment lines
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue

            for (pattern in broadCatchPatterns) {
                val match = pattern.find(line) ?: continue
                // Skip matches inside string literals
                if (isInsideStringLiteral(line, match.range.first)) continue

                val inSuspend = suspendRanges.any { originalLineNum in it }
                val functionName = findEnclosingFunction(lines, originalLineIdx)

                val catchType = when {
                    line.contains("Throwable") -> "Throwable"
                    line.contains("RuntimeException") -> "RuntimeException"
                    line.contains("Exception") -> "Exception"
                    line.contains("runCatching") -> "runCatching"
                    else -> "unknown"
                }

                val rethrowsCancellation = checkRethrowsCancellation(lines, originalLineIdx)
                val transformsException = checkTransformsException(lines, originalLineIdx)

                val risk = when {
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
                        risk = risk
                    )
                )
            }
        }

        return findings
            .sortedByDescending { riskWeight(it.risk) }
            // Deduplicate: same (module, file, function, catchType) → keep worst risk
            .distinctBy { "${it.module}::${it.file}::${it.function}::${it.catchType}" }
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
            val isMultiLineCatch = catchStart >= 0 &&
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
                i = end
            }
            i++
        }
        return ranges
    }

    /** Finds matching closing brace accounting for nested braces. */
    private fun findBlockEnd(lines: List<String>, startIdx: Int): Int {
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

    private fun findEnclosingFunction(lines: List<String>, idx: Int): String {
        for (i in idx downTo 0) {
            val funMatch = Regex("""fun\s+(\w+)""").find(lines[i].trim())
            if (funMatch != null) return funMatch.groupValues[1]
        }
        return "<unknown>"
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
    private fun checkRethrowsCancellation(lines: List<String>, catchIdx: Int): Boolean {
        val catchVar = extractCatchVariable(lines[catchIdx]) ?: return false
        val catchBodyEnd = findCatchBodyEnd(lines, catchIdx)
        val end = minOf(catchBodyEnd, lines.size)

        // Pattern: <catchVar>.rethrowIfCancellation() — extension-based cancellation rethrow
        val rethrowIfCancellationPattern = Regex(
            """\b${Regex.escape(catchVar)}\.rethrowIfCancellation\s*\(\s*\)"""
        )

        // Check rethrowIfCancellation() — must be first non-blank, non-comment line
        var foundHelper = false
        for (i in catchIdx + 1 until end) {
            val stripped = stripComment(lines[i])
            val trimmed = stripped.trim()
            if (trimmed.isBlank()) continue
            // Skip single-line and block comment markers
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
            // This is the first real statement — check if it's the helper
            val helperMatch = rethrowIfCancellationPattern.find(stripped)
            if (helperMatch != null && !isInsideStringLiteral(stripped, helperMatch.range.first)) {
                foundHelper = true
            }
            break
        }
        if (foundHelper) return true

        // Pattern: throw <catchVar> (exact variable rethrow)
        val throwVarPattern = Regex("""\bthrow\s+${Regex.escape(catchVar)}\b""")
        // Pattern: if (variable is [...][.]CancellationException[...])
        val cancellationIfPattern = Regex(
            """\bif\s*\(\s*${Regex.escape(catchVar)}\s+is\s+[A-Za-z_.]*CancellationException"""
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
     * Find the end of the block that follows an `if (...)` statement.
     * Returns the index of the closing `}` (exclusive), or -1 if not found.
     * Handles: `if (cond) statement` (single-line, no brace) and `if (cond) { ... }`.
     */
    private fun findBlockAfterIf(lines: List<String>, ifIdx: Int, limit: Int): Int {
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
    private fun findCatchBodyEnd(lines: List<String>, catchIdx: Int): Int {
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
    private fun isInsideStringLiteral(line: String, pos: Int): Boolean {
        var inString = false
        var quoteChar: Char? = null
        var escaped = false
        for (i in 0 until pos) {
            if (i >= line.length) break
            val ch = line[i]
            if (escaped) { escaped = false; continue }
            if (ch == '\\') { escaped = true; continue }
            if (inString) {
                if (ch == quoteChar) { inString = false; quoteChar = null }
            } else {
                if (ch == '\"' || ch == '\'') { inString = true; quoteChar = ch }
            }
        }
        return inString
    }

    private fun checkTransformsException(lines: List<String>, catchIdx: Int): Boolean {
        val catchBodyEnd = findCatchBodyEnd(lines, catchIdx)
        val end = minOf(catchBodyEnd, lines.size)
        for (i in catchIdx + 1 until end) {
            if (Regex("""throw\s+\w+Exception""").containsMatchIn(lines[i])) {
                return true
            }
        }
        return false
    }

    private fun riskWeight(risk: String): Int = when (risk) {
        "critical" -> 4
        "high" -> 3
        "medium" -> 2
        "accepted" -> 1
        else -> 0
    }
}
