package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/**
 * Configuration-time snapshot of the three independent module-topology signals
 * fed to [VerifyModuleManifestTask] (Epic 9.2d-a3c1). Pure data — small
 * List<String> values; no Gradle model objects cross task boundaries.
 */
internal object ModuleTopologySnapshot {
    /** All configured non-root projects with build files, deterministic (sorted). */
    fun projectPaths(project: Project): List<String> =
        project.allprojects
            .filter { it != project && it.buildFile.exists() }
            .map { it.path }
            .sorted()

    /**
     * BOM api constraint module paths ("tramai-*" / "examples:*" names, ":"
     * prefixed, sorted). LAZY ONLY: must be read inside a Provider after
     * evaluation — the java-platform model is incomplete at plugin apply time
     * (Epic 9.2d-a1 rule). Reads the configuration model; never resolves.
     */
    fun bomPaths(project: Project): List<String> =
        project.allprojects
            .firstOrNull { it.name == "tramai-bom" }
            ?.configurations
            ?.findByName("api")
            ?.dependencyConstraints
            .orEmpty()
            .mapNotNull { constraint ->
                val name = constraint.name
                if (name.startsWith("tramai-") || name.startsWith("examples:")) ":$name" else null
            }.sorted()
}

/**
 * Typed module-manifest verifier (Epic 9.2d-a3c1). Compares the authoritative
 * module catalog against three independent Gradle model signals via the pure
 * [ModuleManifestVerifier]. No Task.project access at execution time; the
 * catalog is read through its declared file input (rootDir derived from it).
 */
abstract class VerifyModuleManifestTask : DefaultTask() {
    @get:InputFile
    abstract val moduleCatalogFile: RegularFileProperty

    @get:Input
    abstract val projectPaths: ListProperty<String>

    @get:Input
    abstract val publishedPaths: ListProperty<String>

    @get:Input
    abstract val bomPaths: ListProperty<String>

    @TaskAction
    fun verify() {
        val catalogFile = moduleCatalogFile.get().asFile
        // Exact-file authority: the declared catalog input IS the file parsed
        // (a3 discipline — never re-discover a conventional path from it).
        val catalog = ModuleManifest.catalogFile(catalogFile)
        val diagnostics =
            ModuleManifestVerifier.verify(
                catalogModules = catalog.modules,
                projectPaths = projectPaths.get().toSet(),
                publishedPaths = publishedPaths.get().toSet(),
                bomPaths = bomPaths.get().toSet(),
            )
        if (diagnostics.isNotEmpty()) {
            throw GradleException(diagnostics.joinToString("\n") { "[${it.code}] ${it.message}" })
        }
    }
}
