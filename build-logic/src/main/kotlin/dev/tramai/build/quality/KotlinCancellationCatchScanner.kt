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
            if (trimmed.startsWith("catch") && !trimmed.contains(")")) {
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

    /** Check if the catch block rethrows CancellationException (within catch body). */
    private fun checkRethrowsCancellation(lines: List<String>, catchIdx: Int): Boolean {
        val catchBodyEnd = findCatchBodyEnd(lines, catchIdx)
        val end = minOf(catchBodyEnd, lines.size)
        for (i in catchIdx + 1 until end) {
            if (lines[i].contains("CancellationException") &&
                (lines[i].contains("throw") || lines[i].contains("rethrow"))
            ) {
                return true
            }
        }
        return false
    }

    /** Find the end of the catch body (closing brace of the catch block). */
    private fun findCatchBodyEnd(lines: List<String>, catchIdx: Int): Int {
        var braceCount = 0
        var foundOpening = false
        for (i in catchIdx until lines.size) {
            braceCount += lines[i].count { it == '{' }
            braceCount -= lines[i].count { it == '}' }
            if (braceCount > 0) foundOpening = true
            if (foundOpening && braceCount == 0) return i
        }
        return catchIdx + 1
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
