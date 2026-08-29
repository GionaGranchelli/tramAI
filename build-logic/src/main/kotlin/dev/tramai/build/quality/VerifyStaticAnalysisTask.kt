package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The one canonical static-analysis task (Epic 10.1b).
 *
 * Executes the pinned Detekt CLI against the committed central configuration
 * and baseline over the repository source universe, then applies the
 * DetektBaselineGrowthVerifier contract. Fail-closed: any non-baselined
 * finding, baseline growth, malformed baseline, deleted/emptied baseline,
 * tool failure, or unresolved base ref fails the task. Reports are written
 * even when findings exist.
 */
abstract class VerifyStaticAnalysisTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val detektClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val configFile: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineFile: RegularFileProperty

    @get:Input
    abstract val baseRef: Property<String>

    @get:Optional
    @get:Input
    abstract val changeClass: Property<String>

    @get:OutputDirectory
    abstract val reportsDir: DirectoryProperty

    @get:Internal
    abstract val repositoryRoot: Property<String>

    @TaskAction
    fun verifyStaticAnalysis() {
        val root = File(repositoryRoot.get())
        val baseRefValue = baseRef.get()
        val currentBaselineFile = baselineFile.asFile.orNull?.takeIf { it.exists() }

        // 1. Baseline-growth contract — fast, fails before the long Detekt run.
        val baseBaselineXml = gitShowOrNull(root, baseRefValue, "config/detekt/baseline.xml")
        val verdict =
            DetektBaselineGrowthVerifier.verify(
                DetektGrowthInput(
                    baseBaselineXml = baseBaselineXml,
                    currentBaselineXml = currentBaselineFile?.readText(),
                    changeClass = changeClass.orNull?.takeIf { it.isNotBlank() },
                    runtimeSourceChanged = gitDiffRuntimeChanged(root, baseRefValue),
                )
            )
        if (!verdict.passed) {
            throw GradleException("[${verdict.code}] ${verdict.message}")
        }
        logger.lifecycle("static-analysis: ${verdict.message}")

        // 2. Execute Detekt (pinned CLI) against the committed config + baseline.
        val inputDirs =
            sourceFiles.files
                .map { root.toPath().relativize(it.toPath()).toString().substringBeforeLast('/') }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        if (inputDirs.isEmpty()) {
            throw GradleException("verifyStaticAnalysis: no Kotlin sources matched the source universe.")
        }
        val reportDir = reportsDir.get().asFile.apply { mkdirs() }
        val detektXml = File(reportDir, "detekt.xml")
        val detektSarif = File(reportDir, "detekt.sarif")
        val detektHtml = File(reportDir, "detekt.html")
        val args =
            mutableListOf(
                "--input",
                inputDirs.joinToString(","),
                "--config",
                configFile.get().asFile.absolutePath,
                "--report",
                "xml:${detektXml.absolutePath}",
                "--report",
                "sarif:${detektSarif.absolutePath}",
                "--report",
                "html:${detektHtml.absolutePath}",
            )
        currentBaselineFile?.let { args += listOf("--baseline", it.absolutePath) }

        val javaExecutable =
            System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
        val detektJar =
            detektClasspath.files.single {
                it.name.contains("detekt-cli") && it.name.endsWith(".jar")
            }
        val proc =
            ProcessBuilder(
                listOf(javaExecutable, "-Xmx3g", "-jar", detektJar.absolutePath) + args
            )
                .directory(root)
                .redirectErrorStream(true)
                .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()

        // 3. Observability summary — written even on failure.
        val summary =
            buildSummary(
                reportDir = reportDir,
                detektXml = detektXml,
                baselineVerdict = verdict,
                baseBaselineXml = baseBaselineXml,
                currentBaselineXml = currentBaselineFile?.readText(),
            )
        File(reportDir, "summary.txt").writeText(summary)

        if (exit != 0) {
            throw GradleException(
                "verifyStaticAnalysis: Detekt reported non-baselined findings (exit $exit).\n" +
                    output.takeLast(8000) +
                    "\n\nSummary written to $reportDir/summary.txt. " +
                    "Fix the findings — the baseline is a ceiling, not an allowance budget."
            )
        }
        logger.lifecycle("static-analysis: no new findings; reports at $reportDir")
    }

    private fun buildSummary(
        reportDir: File,
        detektXml: File,
        baselineVerdict: DetektGrowthVerdict,
        baseBaselineXml: String?,
        currentBaselineXml: String?,
    ): String {
        val newFindings = countReportFindings(detektXml)
        val baseTotal = (DetektBaselineParser.parse(baseBaselineXml) as? BaselineParseResult.Success)?.document?.currentIssueIds?.size ?: 0
        val currentTotal = (DetektBaselineParser.parse(currentBaselineXml) as? BaselineParseResult.Success)?.document?.currentIssueIds?.size ?: 0
        val byRule = newFindings.entries.sortedByDescending { it.value }.joinToString("\n") { "  ${it.key}: ${it.value}" }
        return buildString {
            appendLine("TramAI static-analysis summary")
            appendLine("============================")
            appendLine("baselined legacy findings : $currentTotal (base: $baseTotal)")
            appendLine("new/unbaselined findings   : ${newFindings.values.sum()}")
            appendLine("baseline additions         : ${baselineVerdict.added.size}")
            appendLine("baseline removals          : ${baselineVerdict.removed.size}")
            if (baselineVerdict.added.isNotEmpty()) {
                appendLine("added baseline entries (${baselineVerdict.added.size}):")
                baselineVerdict.added.take(50).forEach { appendLine("  + $it") }
                if (baselineVerdict.added.size > 50) appendLine("  ... and ${baselineVerdict.added.size - 50} more")
            }
            if (baselineVerdict.removed.isNotEmpty()) {
                appendLine("removed baseline entries (${baselineVerdict.removed.size}):")
                baselineVerdict.removed.take(50).forEach { appendLine("  - $it") }
                if (baselineVerdict.removed.size > 50) appendLine("  ... and ${baselineVerdict.removed.size - 50} more")
            }
            appendLine("new findings by rule:")
            appendLine(if (byRule.isBlank()) "  (none)" else byRule)
            appendLine("reports: detekt.xml, detekt.sarif, detekt.html")
        }
    }

    private fun countReportFindings(xmlFile: File): Map<String, Int> {
        if (!xmlFile.exists()) return emptyMap()
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            val doc = factory.newDocumentBuilder().parse(xmlFile)
            val counts = mutableMapOf<String, Int>()
            val errors = doc.getElementsByTagName("error")
            for (i in 0 until errors.length) {
                val rule = errors.item(i).attributes.getNamedItem("source")?.nodeValue ?: "unknown"
                counts[rule] = (counts[rule] ?: 0) + 1
            }
            counts
        } catch (e: Exception) {
            logger.warn("static-analysis: could not parse detekt.xml summary: ${e.message}")
            emptyMap()
        }
    }

    private data class GitResult(val exitCode: Int, val output: String)

    private fun runGit(root: File, vararg args: String): GitResult {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        return GitResult(proc.waitFor(), out)
    }

    private fun gitShowOrNull(root: File, ref: String, path: String): String? {
        val rev = runGit(root, "rev-parse", "--verify", "--quiet", "$ref^{commit}")
        if (rev.exitCode != 0) {
            throw GradleException(
                "verifyStaticAnalysis: static-analysis base ref '$ref' does not resolve. " +
                    "Pass -PtramaiStaticAnalysisBaseRef=<exact sha> (CI: pull_request.base.sha / event.before)."
            )
        }
        val show = runGit(root, "show", "$ref:$path")
        return if (show.exitCode == 0) show.output else null
    }

    private fun gitDiffRuntimeChanged(root: File, ref: String): Boolean {
        val diff = runGit(root, "diff", "--name-only", "${ref}...HEAD")
        if (diff.exitCode != 0) {
            throw GradleException("verifyStaticAnalysis: git diff against '$ref' failed: ${diff.output.take(500)}")
        }
        // TramAI runtime production = a module's src/main, NOT the build-logic
        // included build (build-logic/src/main is tooling).
        return diff.output.lineSequence().any { it.contains("/src/main/") && !it.startsWith("build-logic/") }
    }
}
