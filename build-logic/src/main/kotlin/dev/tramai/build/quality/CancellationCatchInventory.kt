package dev.tramai.build.quality

import org.gradle.api.Project
import java.io.File

/**
 * Scans Kotlin source files for broad exception catches in suspend-capable code.
 */
class CancellationCatchInventory(private val rootProject: Project) {

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

    fun inventory(): List<CancellationCatchFinding> {
        val findings = mutableListOf<CancellationCatchFinding>()
        val projects = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }

        for (proj in projects) {
            listOf("src/main/kotlin", "src/test/kotlin").forEach { sourceSet ->
                val srcDir = File(proj.projectDir, sourceSet)
                if (!srcDir.exists()) return@forEach

                srcDir.walkTopDown().forEach { file ->
                    if (!file.isFile || file.extension != "kt") return@forEach
                    processFile(file, proj.name, findings)
                }
            }
        }

        return findings.sortedByDescending { riskWeight(it.risk) }
    }

    private fun processFile(file: File, moduleName: String, findings: MutableList<CancellationCatchFinding>) {
        val content = file.readText()
        val lines = content.lines()
        val relativePath = ReportNormalizer.repoRelativePath(file, rootProject.rootDir)

        // Find suspend functions and their line ranges
        val suspendRanges = findSuspendRanges(lines)

        // Find broad catches
        for ((lineIdx, line) in lines.withIndex()) {
            for (pattern in broadCatchPatterns) {
                if (pattern.containsMatchIn(line)) {
                    val lineNum = lineIdx + 1
                    val inSuspend = suspendRanges.any { lineNum in it }
                    val functionName = findEnclosingFunction(lines, lineIdx)

                    val catchType = when {
                        line.contains("Throwable") -> "Throwable"
                        line.contains("RuntimeException") -> "RuntimeException"
                        line.contains("Exception") -> "Exception"
                        line.contains("runCatching") -> "runCatching"
                        else -> "unknown"
                    }

                    val rethrowsCancellation = checkRethrowsCancellation(lines, lineIdx)
                    val transformsException = checkTransformsException(lines, lineIdx)

                    val risk = when {
                        rethrowsCancellation -> "accepted"
                        inSuspend && transformsException -> "high"
                        inSuspend -> "critical"
                        !inSuspend -> "medium"
                        else -> "medium"
                    }

                    findings.add(
                        CancellationCatchFinding(
                            module = moduleName,
                            file = relativePath,
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
        }
    }

    private fun findSuspendRanges(lines: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            if (suspendPatterns[0].containsMatchIn(lines[i]) ||
                suspendPatterns[1].containsMatchIn(lines[i]) ||
                suspendPatterns[2].containsMatchIn(lines[i])) {
                val start = i + 1
                val end = findBlockEnd(lines, i)
                ranges.add(start..end)
                i = end
            }
            i++
        }
        return ranges
    }

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
            val line = lines[i].trim()
            val funMatch = Regex("""fun\s+(\w+)""").find(line)
            if (funMatch != null) return funMatch.groupValues[1]
        }
        return "<unknown>"
    }

    private fun checkRethrowsCancellation(lines: List<String>, catchIdx: Int): Boolean {
        // Look for CancellationException rethrow within ~20 lines after the catch
        val end = minOf(catchIdx + 20, lines.size)
        for (i in catchIdx + 1 until end) {
            if (lines[i].contains("CancellationException") &&
                (lines[i].contains("throw") || lines[i].contains("rethrow"))) {
                return true
            }
        }
        return false
    }

    private fun checkTransformsException(lines: List<String>, catchIdx: Int): Boolean {
        val end = minOf(catchIdx + 10, lines.size)
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
