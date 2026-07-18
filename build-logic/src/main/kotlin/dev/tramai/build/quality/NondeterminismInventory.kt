package dev.tramai.build.quality

import org.gradle.api.Project
import java.io.File

/**
 * Scans Kotlin source files for direct access to time, identity, and randomness sources.
 */
class NondeterminismInventory(private val rootProject: Project) {

    private data class PatternDef(val regex: Regex, val category: String, val classification: String)

    private val patterns = listOf(
        PatternDef(Regex("""System\.currentTimeMillis\(\)"""), "clock", "business_time"),
        PatternDef(Regex("""System\.nanoTime\(\)"""), "clock", "performance_measurement"),
        PatternDef(Regex("""Instant\.now\(\)"""), "clock", "business_time"),
        PatternDef(Regex("""Clock\.systemUTC\(\)"""), "clock", "scheduling_time"),
        PatternDef(Regex("""Clock\.systemDefaultZone\(\)"""), "clock", "scheduling_time"),
        PatternDef(Regex("""UUID\.randomUUID\(\)"""), "identity", "correlation_identity"),
        PatternDef(Regex("""Random\.Default"""), "randomness", "retry_jitter"),
        PatternDef(Regex("""ThreadLocalRandom"""), "randomness", "retry_jitter"),
        PatternDef(Regex("""Math\.random\(\)"""), "randomness", "retry_jitter"),
        PatternDef(Regex("""SecureRandom\(\)"""), "randomness", "cryptographic"),
        PatternDef(Regex("""delay\s*\(\s*\d+"""), "timing", "backoff"),
        PatternDef(Regex("""Thread\.sleep\("""), "timing", "backoff")
    )

    fun inventory(): List<NondeterminismFinding> {
        val findings = mutableListOf<NondeterminismFinding>()
        val projects = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }

        for (proj in projects) {
            val srcDir = File(proj.projectDir, "src/main/kotlin")
            if (!srcDir.exists()) continue

            srcDir.walkTopDown().forEach { file ->
                if (!file.isFile || file.extension != "kt") return@forEach
                processFile(file, proj.name, findings)
            }
        }

        return findings.sortedBy { it.classification }
    }

    private fun processFile(file: File, moduleName: String, findings: MutableList<NondeterminismFinding>) {
        val lines = file.readLines()
        val relativePath = ReportNormalizer.repoRelativePath(file, rootProject.rootDir)

        for ((lineIdx, line) in lines.withIndex()) {
            // Skip comments
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue

            for (pattern in patterns) {
                if (pattern.regex.containsMatchIn(line)) {
                    findings.add(
                        NondeterminismFinding(
                            module = moduleName,
                            file = relativePath,
                            line = lineIdx + 1,
                            source = pattern.regex.pattern.take(40),
                            classification = pattern.classification,
                            category = pattern.category
                        )
                    )
                }
            }
        }
    }
}
