package dev.tramai.build.quality

import java.io.File

/**
 * Scans Kotlin source files for direct access to time, identity, and randomness sources.
 *
 * Epic 8.3d PR 2 hardening:
 *  - callable-reference composition boundaries (`System::nanoTime`, `System::currentTimeMillis`)
 *    are now first-class patterns (previously only the call forms `System.nanoTime()` were seen);
 *  - the Kotlin `Random` singleton invocation forms are covered:
 *      * fully-qualified `kotlin.random.Random.next*()` (used by the retry-jitter authority)
 *      * bare `Random.next*()` (imported `kotlin.random.Random` singleton usage);
 *  - findings are deterministically sorted (module, file, line, source) so output ordering is
 *    stable regardless of pattern-list or walk order.
 *
 * Identity discipline: the semantic identity used for allowlist matching is
 * (module, file, source) — line numbers never participate (line movement must
 * not invalidate an otherwise unchanged entry). Category and scanner
 * classification are metadata carried on the finding; they are validated for
 * mismatch but never form part of the match key.
 */
class NondeterminismInventory(private val ctx: MeasurementContext) {

    private data class PatternDef(
        val regex: Regex,
        val category: String,
        val classification: String,
        /** Canonical source label emitted for every match; null = emit raw matched text. */
        val emitSource: ((MatchResult) -> String)? = null
    )

    private val patterns = listOf(
        // ── Clock / time ──
        PatternDef(Regex("""System\.currentTimeMillis\(\)"""), "clock", "business_time"),
        PatternDef(Regex("""System\.nanoTime\(\)"""), "clock", "performance_measurement"),
        PatternDef(Regex("""Instant\.now\(\)"""), "clock", "business_time"),
        PatternDef(Regex("""Clock\.systemUTC\(\)"""), "clock", "scheduling_time"),
        PatternDef(Regex("""Clock\.systemDefaultZone\(\)"""), "clock", "scheduling_time"),
        // 8.3d PR 2: callable-reference composition boundaries
        PatternDef(Regex("""System\s*::\s*currentTimeMillis\b"""), "clock", "business_time") {
            "System::currentTimeMillis"
        },
        PatternDef(Regex("""System\s*::\s*nanoTime\b"""), "clock", "performance_measurement") {
            "System::nanoTime"
        },
        // ── Identity ──
        PatternDef(Regex("""UUID\.randomUUID\(\)"""), "identity", "correlation_identity"),
        // ── Randomness ──
        PatternDef(Regex("""Random\.Default"""), "randomness", "retry_jitter"),
        PatternDef(Regex("""ThreadLocalRandom"""), "randomness", "retry_jitter"),
        PatternDef(Regex("""Math\.random\(\)"""), "randomness", "retry_jitter"),
        PatternDef(Regex("""SecureRandom\(\)"""), "randomness", "cryptographic"),
        // 8.3d PR 2: Kotlin Random singleton invocation forms.
        // Fully-qualified: kotlin.random.Random.nextDouble() etc.
        PatternDef(
            Regex("""kotlin\.random\.Random\.(nextDouble|nextInt|nextLong|nextFloat|nextBoolean|nextBits|nextBytes|nextUInt|nextULong|nextUBytes)\s*\("""),
            "randomness",
            "retry_jitter"
        ) { m -> "kotlin.random.Random.${m.groupValues[1]}()" },
        // Bare singleton (imported `kotlin.random.Random`). Negative lookbehind excludes
        // instance receivers (`secureRandom.nextBytes` — preceded by \w) and the
        // fully-qualified form (preceded by `.`), so each call is counted exactly once.
        PatternDef(
            Regex("""(?<![.\w])Random\.(nextDouble|nextInt|nextLong|nextFloat|nextBoolean|nextBits|nextBytes|nextUInt|nextULong|nextUBytes)\s*\("""),
            "randomness",
            "retry_jitter"
        ) { m -> "Random.${m.groupValues[1]}()" },
        // ── Timing ──
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

                    for (pat in patterns) {
                        pat.regex.findAll(content).forEach { match ->
                            val lineNum = content.substring(0, match.range.first).count { it == '\n' } + 1
                            findings.add(
                                NondeterminismFinding(
                                    module = mod.name,
                                    file = relativePath,
                                    line = lineNum,
                                    source = pat.emitSource?.invoke(match) ?: match.value,
                                    classification = pat.classification,
                                    category = pat.category
                                )
                            )
                        }
                    }
                }
            }
        }

        // Deterministic output ordering (S0-G): module, file, line, source.
        return findings.sortedWith(
            compareBy({ it.module }, { it.file }, { it.line }, { it.source })
        )
    }
}
