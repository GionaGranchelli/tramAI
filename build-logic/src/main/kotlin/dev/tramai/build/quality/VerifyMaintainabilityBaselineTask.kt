package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Typed maintainability-baseline verifier (Epic 9.2d-a3c2). Compares the
 * committed baseline against freshly generated measurements with the exact
 * same [BaselineVerifier] semantics, but executes in directory mode: the
 * action builds a [MeasurementContext] from the declared catalog file input
 * and reads the committed baseline through its declared [committedBaselineFile]
 * input. No Task.project access at execution time — declared inputs are the
 * execution authority (a3 discipline).
 */
abstract class VerifyMaintainabilityBaselineTask : DefaultTask() {
    private companion object {
        const val MAX_WARNINGS_LOGGED = 100
    }

    @get:InputFile
    @get:org.gradle.api.tasks.Optional
    abstract val committedBaselineFile: RegularFileProperty

    @get:InputFile
    abstract val deviationsFile: RegularFileProperty

    @get:InputFile
    abstract val moduleCatalogFile: RegularFileProperty

    @get:InputFile
    abstract val moduleBoundariesFile: RegularFileProperty

    /**
     * Settings script the root is derived from (Epic 9.2d-a3c2 round 2).
     * Root = settingsFile.parentFile — never a positional guess from the
     * catalog path. Declared so a settings change invalidates the gate.
     */
    @get:InputFile
    abstract val settingsFile: RegularFileProperty

    /**
     * Project dependency graph captured from the Gradle model at
     * configuration time (Epic 9.2d-a3c2 round 2). Directory mode cannot
     * rediscover project edges; this declared snapshot is the execution
     * authority for cycle / forbidden-edge / dependency-policy checks.
     */
    @get:Input
    abstract val dependencyGraph: Property<DependencyGraphSnapshot>

    /** Aggregate resolved-dependency baseline produced by generateResolvedDependencyBaseline. */
    @get:InputFile
    abstract val resolvedDependenciesFile: RegularFileProperty

    /**
     * The repository tree the generators measure: module sources, build
     * scripts, settings, version properties, committed API dumps, release
     * notes. Declared so configuration-cache invalidation observes source
     * changes (the gate must re-run, never serve a stale PASS).
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceTree: ConfigurableFileCollection

    /** Modules with an apiCheck task (BCV) — configuration-time snapshot. */
    @get:Input
    abstract val apiValidationModules: ListProperty<String>

    @get:OutputDirectory
    abstract val reportDir: DirectoryProperty

    @TaskAction
    fun verify() {
        // Root derived from the DECLARED settings file — never a positional
        // guess from the catalog path (a3c2 round 2: an alternative declared
        // catalog must not break root resolution or fall back to the
        // conventional catalog).
        val rootDir = settingsFile.get().asFile.parentFile
        val catalog = ModuleCatalog(moduleCatalogFile.get().asFile)
        // Parse before building the context: discoverModulesFromSettings reads
        // layer/publishability via entryFor, which requires parsed state.
        catalog.parse()
        val ctx = MeasurementContext.fromDirectory(rootDir, catalog)
        val generator =
            BaselineGenerator(
                ctx = ctx,
                outputDir = reportDir.get().asFile,
                writeRepositoryArtifacts = false,
            )
        val verifier =
            BaselineVerifier(
                generator = generator,
                ctx = ctx,
                reportDir = reportDir.get().asFile,
                declaredInputs =
                    DeclaredBaselineInputs(
                        committedBaselineFile = committedBaselineFile.orNull?.asFile,
                        resolvedDependenciesFile = resolvedDependenciesFile.get().asFile,
                        apiValidationModules = apiValidationModules.get().toSet(),
                        deviationsFile = deviationsFile.get().asFile,
                        moduleCatalogFile = moduleCatalogFile.get().asFile,
                        moduleBoundariesFile = moduleBoundariesFile.get().asFile,
                        dependencyGraph = dependencyGraph.get(),
                    ),
            )
        val report = verifier.verify()

        report.failures.forEach { logger.error("FAIL: $it") }
        report.warnings.take(MAX_WARNINGS_LOGGED).forEach { logger.warn("WARN: $it") }
        if (report.warnings.size > MAX_WARNINGS_LOGGED) {
            val hidden = report.warnings.size - MAX_WARNINGS_LOGGED
            logger.warn("WARN: $hidden additional warnings; see dependency-changes.json")
        }
        report.acceptedDeviations.forEach { logger.info("ACCEPTED: $it") }

        if (!report.passed) {
            val summary =
                "Maintainability baseline verification FAILED:\n" +
                    report.failures.joinToString("\n") { "  - $it" } +
                    "\n\nRun './gradlew generateMaintainabilityBaseline' to regenerate." +
                    "\nAdd deviations to config/quality/maintainability-deviations.yml for accepted regressions."
            throw GradleException(summary)
        }

        println("Maintainability baseline verification PASSED.")
        if (report.acceptedDeviations.isNotEmpty()) {
            println("Accepted deviations: ${report.acceptedDeviations.size}")
        }
        if (report.warnings.isNotEmpty()) {
            println("Warnings: ${report.warnings.size}")
        }
        println("Reports: ${reportDir.get().asFile}")
    }
}
