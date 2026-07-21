package dev.tramai.build.quality

import java.io.File

/**
 * Scans Kotlin source files for direct access to time, identity, and randomness sources.
 */
class NondeterminismInventory(private val ctx: MeasurementContext) {

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

        for (mod in ctx.modules) {
            mod.sourceDirs.forEach { srcDir ->
                if (!srcDir.exists()) return@forEach
                srcDir.walkTopDown().forEach { file ->
                    if (!file.isFile || file.extension != "kt") return@forEach
                    val content = file.readText()
                    val relativePath = ReportNormalizer.repoRelativePath(file, ctx.rootDir)
                    val lines = content.lines()

                    for (pat in patterns) {
                        pat.regex.findAll(content).forEach { match ->
                            val lineNum = content.substring(0, match.range.first).count { it == '\n' } + 1
                            findings.add(
                                NondeterminismFinding(
                                    module = mod.name,
                                    file = relativePath,
                                    line = lineNum,
                                    source = match.value,
                                    classification = pat.classification,
                                    category = pat.category
                                )
                            )
                        }
                    }
                }
            }
        }

        return findings
    }
}
