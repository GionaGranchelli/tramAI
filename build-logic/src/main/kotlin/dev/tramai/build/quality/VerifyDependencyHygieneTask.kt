package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipFile

/**
 * Epic 10.1c dependency-hygiene gate.
 *
 * For every module: declared direct dependencies (main/test/testFixtures
 * configurations) vs source imports, with a module+configuration+coordinate
 * exemption catalog for non-static usages (JDBC drivers, ServiceLoader,
 * auto-config, runtime-only). Unused MAIN-scope dependencies fail; test-scope
 * are reported as info only; stale exemptions fail.
 */
abstract class VerifyDependencyHygieneTask : DefaultTask() {

    @get:Nested
    abstract val units: ListProperty<DependencyUnitSpec>

    @get:Classpath
    @get:InputFiles
    abstract val compileClasspath: ConfigurableFileCollection

    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val exemptionFiles: ConfigurableFileCollection

    @get:Internal
    abstract val repositoryRoot: Property<String>

    @get:OutputDirectory
    abstract val reportsDir: DirectoryProperty

    @TaskAction
    fun verify() {
        val exemptionFile = exemptionFiles.files.firstOrNull { it.exists() }
        val exemptions = DependencyExemptionsParser.parse(exemptionFile?.readText())
        val reportDir = reportsDir.get().asFile.apply { mkdirs() }

        // coordinate -> package prefixes, extracted from the resolved classpath jars.
        // Jar paths follow the module-cache layout .../<group>/<name>/<version>/.../<name>-<version>.jar
        val packagePrefixes = mutableMapOf<String, MutableSet<String>>()
        for (file in compileClasspath.files) {
            if (!file.isFile || !file.name.endsWith(".jar")) continue
            val coordinate = coordinateFromPath(file) ?: continue
            val pkgs = packagePrefixes.getOrPut(coordinate) { mutableSetOf() }
            pkgs += jarPackagePrefixes(file)
        }

        val allViolations = mutableListOf<String>()
        val allInfo = mutableListOf<String>()
        val report = StringBuilder()
        for (unit in units.get().sortedBy { it.modulePath }) {
            val result = DependencyUsageEvaluator.evaluate(unit, packagePrefixes, exemptions)
            allViolations += result.violations
            allInfo += result.info
            report.appendLine("[${unit.modulePath}]")
            result.info.forEach { report.appendLine("    $it") }
            result.violations.forEach { report.appendLine("    VIOLATION: $it") }
        }
        File(reportDir, "report.txt").writeText(report.toString())
        File(reportDir, "summary.txt").writeText(
            buildString {
                appendLine("TramAI dependency-hygiene summary")
                appendLine("=================================")
                appendLine("modules analyzed         : ${units.get().size}")
                appendLine("exemptions applied       : ${exemptions.size}")
                appendLine("violations               : ${allViolations.size}")
                appendLine("info (test-scope/unused) : ${allInfo.size}")
            },
        )
        if (allViolations.isNotEmpty()) {
            throw GradleException(
                "verifyDependencyHygiene: ${allViolations.size} violation(s).\n" +
                    allViolations.take(50).joinToString("\n") { "  $it" } +
                    if (allViolations.size > 50) "\n  ... and ${allViolations.size - 50} more" else "" +
                    "\n\nRemove the unused declaration or add a module+configuration+coordinate exemption " +
                    "with rationale to config/dependency-hygiene/exemptions.yml.",
            )
        }
        logger.lifecycle("dependency-hygiene: ${units.get().size} modules clean (${allInfo.size} info entries)")
    }

    private fun coordinateFromPath(file: File): String? {
        val path = file.absolutePath
        // .../modules-2/files-2.1/<group>/<name>/<version>/<hash>/<name>-<version>.jar
        val marker = "files-2.1/"
        val idx = path.indexOf(marker)
        if (idx < 0) return null
        val rest = path.substring(idx + marker.length)
        val segments = rest.split("/")
        if (segments.size < 3) return null
        // groups can contain dots only, names may contain dashes; version dir is
        // the third segment after group/name.
        val group = segments[0]
        val name = segments[1]
        return "$group:$name"
    }

    private fun jarPackagePrefixes(file: File): Set<String> {
        val pkgs = mutableSetOf<String>()
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (!name.endsWith(".class") || !name.contains("/")) continue
                    if (name.substringAfterLast("/").contains("$")) continue // inner classes
                    val pkg = name.substringBeforeLast("/").replace("/", ".")
                    val parts = pkg.split(".")
                    pkgs.add(if (parts.size >= 2) parts.take(2).joinToString(".") else pkg)
                }
            }
        } catch (e: Exception) {
            // An unreadable jar fails closed — a dependency whose packages cannot be
            // determined can never be proven used.
            throw GradleException("verifyDependencyHygiene: cannot read jar ${file.absolutePath}: ${e.message}")
        }
        return pkgs
    }
}
