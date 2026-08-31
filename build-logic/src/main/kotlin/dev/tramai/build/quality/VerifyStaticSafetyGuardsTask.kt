package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

@CacheableTask
abstract class VerifyStaticSafetyGuardsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: Property<String>

    @get:OutputDirectory
    abstract val reportsDir: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = File(repositoryRoot.get())
        val config = StaticSafetyGuardConfigParser.parse(configFile.get().asFile.readText(), root)
        val scanner = StaticSafetySourceScanner(config, root)
        val raw = sourceFiles.files.filter { it.isFile }.flatMap { scanner.scan(it) }
        val verifier = StaticSafetyExemptionVerifier(config)
        val findings =
            verifier
                .markExempt(raw)
                .sortedWith(compareBy({ it.path }, { it.line }, { it.rule }, { it.symbol }))
        writeReports(findings, config)
        val violations = failures(findings, verifier)
        if (violations.isNotEmpty()) throw GradleException(violations.joinToString("\n"))
        logger.lifecycle(
            "static-safety-guards: ${findings.size} findings, " +
                "${config.exemptions.size} exemption entries, 0 unexplained",
        )
    }

    private fun failures(
        findings: List<SafetyFinding>,
        verifier: StaticSafetyExemptionVerifier,
    ): List<String> {
        val out = mutableListOf<String>()
        findings.filterNot { it.exempt }.forEach { out += format(it) }
        out += verifier.countMismatches(findings)
        verifier.staleTriples(findings).forEach { (key, _) ->
            out += "stale exemption: ${key.first} | ${key.second} | ${key.third}"
        }
        if (out.isNotEmpty()) {
            out += "Fix the code or add a scoped exemption with rationale to config/quality/static-safety-guards.yml"
        }
        return out
    }

    private fun writeReports(
        findings: List<SafetyFinding>,
        config: StaticSafetyGuardConfig,
    ) {
        val dir = reportsDir.get().asFile.apply { mkdirs() }
        dir.resolve("findings.txt").writeText(findings.joinToString("\n") { format(it) })
        val ruleCounts = config.rules.joinToString("\n") { r -> "${r.id}: ${findings.count { it.rule == r.id }}" }
        dir.resolve("summary.txt").writeText(
            "Static safety guards\n" +
                "findings: ${findings.size}\n" +
                "unexplained: ${findings.count { !it.exempt }}\n" +
                "exemptions live: ${config.exemptions.size}\n" +
                "stale exemptions: ${StaticSafetyExemptionVerifier(config).staleTriples(findings).size}\n" +
                ruleCounts,
        )
    }

    private fun format(f: SafetyFinding): String {
        val prefix = if (f.exempt) "(exempt) " else ""
        return "${f.rule} | ${f.path} | ${f.line} | ${f.symbol} | $prefix${f.snippet}"
    }
}
