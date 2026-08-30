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
import java.util.zip.ZipException
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
    private companion object {
        const val MAX_VIOLATIONS_REPORTED = 50
        const val PACKAGE_SEGMENTS = 2
    }

    @get:Nested
    abstract val units: ListProperty<DependencyUnitSpec>

    @get:Classpath
    @get:InputFiles
    abstract val classpathEvidence: ConfigurableFileCollection

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
        classpathEvidence.files.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".jar")) return@forEach
            val coordinate = coordinateFromPath(file) ?: return@forEach
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
                    allViolations.take(MAX_VIOLATIONS_REPORTED).joinToString("\n") { "  $it" } +
                    if (allViolations.size > MAX_VIOLATIONS_REPORTED) {
                        "\n  ... and ${allViolations.size - MAX_VIOLATIONS_REPORTED} more"
                    } else {
                        ""
                    } +
                    "\n\nRemove the unused declaration or add a module+configuration+coordinate exemption " +
                    "with rationale to config/dependency-hygiene/exemptions.yml.",
            )
        }
        logger.lifecycle("dependency-hygiene: ${units.get().size} modules clean (${allInfo.size} info entries)")
    }

    internal fun coordinateFromPath(file: File): String? {
        // invariantSeparatorsPath normalizes Windows \ to / so the cache-layout
        // marker and split work identically on every platform (10.1c review).
        val path = file.invariantSeparatorsPath
        // .../modules-2/files-2.1/<group>/<name>/<version>/<hash>/<name>-<version>.jar
        val marker = "files-2.1/"
        val idx = path.indexOf(marker)
        if (idx < 0) return null
        val segments = path.substring(idx + marker.length).split("/")
        // groups can contain dots only, names may contain dashes; version dir is
        // the third segment after group/name.
        return if (segments.size < MIN_PATH_SEGMENTS) null else "${segments[0]}:${segments[1]}"
    }

    private fun jarPackagePrefixes(file: File): Set<String> {
        val pkgs = mutableSetOf<String>()
        try {
            ZipFile(file).use { zip ->
                zip
                    .entries()
                    .toList()
                    .map { it.name }
                    .filter { isClassFile(it) }
                    .forEach { name -> pkgs.add(prefixOf(name)) }
            }
        } catch (e: ZipException) {
            // An unreadable jar fails closed — a dependency whose packages cannot be
            // determined can never be proven used.
            throw GradleException(
                "verifyDependencyHygiene: cannot read jar ${file.absolutePath}: ${e.message}",
                e,
            )
        }
        return pkgs
    }

    private fun isClassFile(name: String): Boolean {
        if (!name.endsWith(".class") || !name.contains("/")) return false
        return !name.substringAfterLast("/").contains("$") // inner classes
    }

    private fun prefixOf(name: String): String {
        val pkg = name.substringBeforeLast("/").replace("/", ".")
        val parts = pkg.split(".")
        return if (parts.size >= PACKAGE_SEGMENTS) {
            parts.take(PACKAGE_SEGMENTS).joinToString(".")
        } else {
            pkg
        }
    }
}

/**
 * Extracts `<group>:<name>` from a Gradle module-cache jar path
 * (.../modules-2/files-2.1/<group>/<name>/<version>/<hash>/<name>-<version>.jar),
 * or null when the path does not match the cache layout. Top-level so the
 * separator normalization is unit-testable without a task instance.
 */
internal fun coordinateFromPath(file: File): String? {
    // Normalize BOTH separators to / — invariantSeparatorsPath only converts the
    // host separator, so on Linux a Windows-style path would keep its backslashes
    // and the cache-layout marker would never match (10.1c review).
    val path = file.path.replace('\\', '/')
    val marker = "files-2.1/"
    val idx = path.indexOf(marker)
    if (idx < 0) return null
    val segments = path.substring(idx + marker.length).split("/")
    // groups can contain dots only, names may contain dashes; version dir is
    // the third segment after group/name.
    return if (segments.size < MIN_PATH_SEGMENTS) null else "${segments[0]}:${segments[1]}"
}

private const val MIN_PATH_SEGMENTS = 3
