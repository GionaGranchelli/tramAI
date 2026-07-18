package dev.tramai.build.quality

import org.gradle.api.Project
import java.io.File

/**
 * Counts lines of code per module and source set.
 */
class SourceMetricsAnalyzer(private val rootProject: Project) {

    fun analyze(): Map<String, ModuleSourceMetrics> {
        val projects = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }
        val result = mutableMapOf<String, ModuleSourceMetrics>()

        for (proj in projects) {
            val projectDir = proj.projectDir
            val production = countSourceSet(projectDir, "src/main/kotlin")
            val test = countSourceSet(projectDir, "src/test/kotlin")
            val testFixtures = countSourceSet(projectDir, "src/testFixtures/kotlin")

            val ratio = if (production.codeLines > 0) {
                test.codeLines.toDouble() / production.codeLines.toDouble()
            } else 0.0

            result[proj.name] = ModuleSourceMetrics(
                module = proj.name,
                production = production,
                test = test,
                testFixtures = testFixtures,
                testToProductionRatio = Math.round(ratio * 100.0) / 100.0
            )
        }

        return result
    }

    private fun countSourceSet(projectDir: File, relativePath: String): SourceSetMetrics {
        val dir = File(projectDir, relativePath)
        if (!dir.exists() || !dir.isDirectory) return SourceSetMetrics()

        var totalLines = 0
        var nonBlankLines = 0
        var commentLines = 0
        var codeLines = 0
        var files = 0

        dir.walkTopDown().forEach { file ->
            if (file.isFile && file.extension == "kt") {
                files++
                val counts = ReportNormalizer.countLines(file)
                totalLines += counts.totalLines
                nonBlankLines += counts.nonBlankLines
                commentLines += counts.commentLines
                codeLines += counts.codeLines
            }
        }

        return SourceSetMetrics(files, totalLines, nonBlankLines, commentLines, codeLines)
    }
}
