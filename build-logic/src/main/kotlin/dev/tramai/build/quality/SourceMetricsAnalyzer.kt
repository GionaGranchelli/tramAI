package dev.tramai.build.quality

import java.io.File

/**
 * Counts lines of code per module and source set.
 */
class SourceMetricsAnalyzer(private val ctx: MeasurementContext) {

    fun analyze(): Map<String, ModuleSourceMetrics> {
        val result = mutableMapOf<String, ModuleSourceMetrics>()

        for (mod in ctx.modules) {
            val production = countSourceSet(mod.sourceDirs)
            val test = countSourceSet(mod.testSourceDirs)
            val testFixtures = countSourceSet(mod.testFixtureDirs)

            val ratio = if (production.codeLines > 0) {
                test.codeLines.toDouble() / production.codeLines.toDouble()
            } else 0.0

            result[mod.name] = ModuleSourceMetrics(
                module = mod.name,
                production = production,
                test = test,
                testFixtures = testFixtures,
                testToProductionRatio = ratio
            )
        }

        return result
    }

    private fun countSourceSet(dirs: List<File>): SourceSetMetrics {
        var files = 0
        var totalLines = 0
        var nonBlankLines = 0
        var commentLines = 0
        var codeLines = 0

        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.walkTopDown().forEach { file ->
                if (!file.isFile || file.extension != "kt") return@forEach
                files++
                val lines = file.readLines()
                totalLines += lines.size
                var inBlockComment = false
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    nonBlankLines++
                    if (inBlockComment) {
                        commentLines++
                        if (trimmed.contains("*/")) inBlockComment = false
                        continue
                    }
                    if (trimmed.startsWith("//")) {
                        commentLines++
                        continue
                    }
                    if (trimmed.startsWith("/*")) {
                        commentLines++
                        if (!trimmed.contains("*/")) inBlockComment = true
                        continue
                    }
                    codeLines++
                }
            }
        }

        return SourceSetMetrics(
            files = files,
            totalLines = totalLines,
            nonBlankLines = nonBlankLines,
            commentLines = commentLines,
            codeLines = codeLines
        )
    }
}
