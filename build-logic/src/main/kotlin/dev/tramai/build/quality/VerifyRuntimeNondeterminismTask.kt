package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File

/**
 * Epic 8.3d PR 2 — typed, configuration-cache-safe verifier for the
 * nondeterminism authority contract (`config/quality/runtime-nondeterminism.yml`).
 *
 * Declares its exact inputs (allowlist file, production source files, scan spec)
 * and one machine-readable report output. No Task.project access at execution;
 * the scanner is rebuilt from the declared inputs only.
 */
@DisableCachingByDefault(because = "Verification task has no reusable output artifact")
abstract class VerifyRuntimeNondeterminismTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val allowlistFile: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    /** JSON array of {"name": "...", "dir": "..."} where dir is relative to rootDir. */
    @get:Input
    abstract val scanSpec: Property<String>

    @get:Input
    abstract val rootDir: Property<String>

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    data class ReportEntry(
        val code: String,
        val severity: String,
        val message: String,
        val modulePath: String?,
        val findingId: String?
    )

    data class VerificationReport(
        val schemaVersion: String,
        val totalFindings: Int,
        val findingsByCategory: Map<String, Int>,
        val findingsByDisposition: Map<String, Int>,
        val unclassifiedCount: Int,
        val staleCount: Int,
        val passed: Boolean,
        val diagnostics: List<ReportEntry>
    )

    @TaskAction
    fun verify() {
        val root = File(rootDir.get())
        val modules = buildModules(root)
        val ctx = MeasurementContext(root, modules)
        val findings = NondeterminismInventory(ctx).inventory()
        // Consume the DECLARED allowlist input — never a re-derived path — so the
        // Gradle input model and the execution read set can never diverge.
        val parseResult = NondeterminismAllowlistParser(allowlistFile.get().asFile).parse()
        val verifier = NondeterminismAllowlistVerifier(findings, parseResult.entries)
        val diagnostics = parseResult.diagnostics + verifier.verify()
        val summary = verifier.summary(diagnostics)

        // Machine-readable report
        val report = VerificationReport(
            schemaVersion = "1",
            totalFindings = summary.totalFindings,
            findingsByCategory = summary.findingsByCategory,
            findingsByDisposition = summary.findingsByDisposition,
            unclassifiedCount = summary.unclassifiedCount,
            staleCount = summary.staleCount,
            passed = summary.passed,
            diagnostics = diagnostics.map { d ->
                ReportEntry(d.code.name, d.severity.name, d.message, d.modulePath, d.findingId)
            }
        )
        ReportNormalizer.writeJson(report, reportFile.get().asFile)

        diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }
            .forEach { logger.error("FAIL: $it") }
        diagnostics.filter { it.severity == DiagnosticSeverity.WARNING }
            .forEach { logger.warn("WARN: $it") }

        if (!summary.passed) {
            val message = "Nondeterminism authority contract verification FAILED:\n" +
                diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }
                    .joinToString("\n") { "  - $it" } +
                "\n\nEvery production nondeterminism source must be classified in " +
                "config/quality/runtime-nondeterminism.yml (or removed). See report: " +
                reportFile.get().asFile
            throw GradleException(message)
        }

        logger.lifecycle(
            "Nondeterminism authority contract PASSED — ${summary.totalFindings} findings, " +
                "${summary.unclassifiedCount} unclassified, ${summary.staleCount} stale."
        )
    }

    private fun buildModules(root: File): List<DiscoveredModule> {
        val mapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        val entries: List<Map<String, String>> = mapper.readValue(
            scanSpec.get(),
            object : TypeReference<List<Map<String, String>>>() {}
        )
        return entries.map { spec ->
            val name = spec.getValue("name")
            val dir = spec.getValue("dir")
            val moduleDir = File(root, dir)
            DiscoveredModule(
                name = name,
                path = ":$name",
                projectDir = moduleDir,
                buildFile = File(moduleDir, "build.gradle.kts"),
                sourceDirs = listOf(File(moduleDir, "src/main/kotlin"), File(moduleDir, "src/main/java")),
                testSourceDirs = listOf(File(moduleDir, "src/test/kotlin")),
                testFixtureDirs = listOf(File(moduleDir, "src/testFixtures/kotlin")),
                publishable = false,
                layer = "unknown",
                apiStability = "unclassified"
            )
        }
    }
}
