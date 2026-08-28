package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Outcome of a dependency-resolution attempt.
 *
 * [failureMessage] is null on success. The resolution provider NEVER throws:
 * failures are carried as data so the value can be serialized into the
 * configuration cache at store time without aborting the build at
 * configuration. The task action decides whether a carried failure becomes a
 * fail-closed marker (probe) or a build abort (baseline).
 */
data class DependencyResolutionResult(
    val records: List<ResolvedDependency>,
    val failureMessage: String? = null,
)

/**
 * Generates a resolved dependency baseline for one consumer project.
 *
 * Configuration-cache compatible: no Task.project access at execution time;
 * all state is declared as task inputs. Fails loudly on unresolved
 * dependencies (fail-closed).
 */
abstract class GenerateResolvedDependencyBaselineTask : DefaultTask() {

    init {
        outputs.upToDateWhen { false }
    }

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val consumerPath: Property<String>

    @get:Internal
    abstract val resolution: Property<DependencyResolutionResult>

    @TaskAction
    fun collect() {
        val result = resolution.get()
        result.failureMessage?.let { message ->
            throw GradleException("Failed to resolve dependencies for ${consumerPath.get()}: $message")
        }
        val normalized = DependencyEdgeNormalizer.normalize(result.records)
        outputFile.get().asFile.let { file ->
            file.parentFile.mkdirs()
            ReportNormalizer.writeJson(normalized, file)
        }
    }
}

/**
 * Resolves a consumer's compile/runtime classpath and returns raw dependency
 * records. Shared by [GenerateResolvedDependencyBaselineTask] and
 * [ArchitectureDependencyProbeTask] (per-project probe files consumed by the
 * architecture gate). Throws [GradleException] on an unresolved dependency —
 * callers decide whether that aborts the build or becomes fail-closed evidence.
 */
internal fun collectResolvedDependencies(
    consumerPath: String,
    configurations: Collection<Configuration>,
): List<ResolvedDependency> {
    val records = mutableListOf<ResolvedDependency>()

    // Callers pre-filter to compile/runtimeClasspath; the name check remains as a
    // safety net so this helper can never touch another project's configurations.
    configurations
        .filter { it.name in listOf("compileClasspath", "runtimeClasspath") }
        .forEach { config ->
            val configName = config.name
            if (!config.isCanBeResolved) return@forEach

            val resolutionResult = config.incoming.resolutionResult
            val root = resolutionResult.rootComponent.get()

            traverseDependencyTree(
                consumer = consumerPath,
                configuration = configName,
                component = root,
                path = listOf(consumerPath),
                depth = 0,
                ancestry = mutableSetOf(root.id.displayName),
                records = records
            )
        }
    return records
}

/**
 * Fail-soft per-project dependency probe for the architecture gate. Unlike
 * [GenerateResolvedDependencyBaselineTask] it NEVER throws on unresolved
 * dependencies: a resolution failure is written into the output file as a
 * typed marker, so the gate can report fail-closed evidence instead of the
 * task graph aborting before the report is written.
 *
 * Configuration-cache compatible: no Task.project access at execution time;
 * all state is declared as task inputs.
 */
abstract class ArchitectureDependencyProbeTask : DefaultTask() {

    init {
        outputs.upToDateWhen { false }
    }

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:Input
    abstract val consumerPath: Property<String>

    @get:Internal
    abstract val resolution: Property<DependencyResolutionResult>

    @TaskAction
    fun collect() {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        val result = resolution.get()
        if (result.failureMessage != null) {
            ReportNormalizer.writeJson(
                listOf(
                    mapOf(
                        "resolutionFailed" to true,
                        "message" to result.failureMessage,
                    ),
                ),
                file,
            )
            return
        }
        val normalized = DependencyEdgeNormalizer.normalize(result.records)
        ReportNormalizer.writeJson(normalized, file)
    }
}

private fun traverseDependencyTree(
    consumer: String,
    configuration: String,
    component: ResolvedComponentResult,
    path: List<String>,
    depth: Int,
    ancestry: MutableSet<String>,
    records: MutableList<ResolvedDependency>
) {
    component.dependencies.toList().sortedBy { it.requested.displayName }.forEach { dep ->
        if (dep is UnresolvedDependencyResult) {
            throw GradleException(
                "Failed to resolve $consumer:$configuration dependency " +
                    "${dep.requested.displayName}: ${dep.failure.message}"
            )
        }
        if (dep is ResolvedDependencyResult) {
            val selected = dep.selected
            val selectedId = selected.id
            val pathElement = when (selectedId) {
                is ModuleComponentIdentifier -> "${selectedId.group}:${selectedId.module}:${selectedId.version}"
                is ProjectComponentIdentifier -> selectedId.projectPath
                else -> selectedId.displayName
            }
            val nextPath = path + pathElement

            if (selectedId is ModuleComponentIdentifier) {
                val requested = (dep.requested as? ModuleComponentSelector)?.version
                records.add(
                    ResolvedDependency(
                        group = selectedId.group,
                        artifact = selectedId.module,
                        selectedVersion = selectedId.version,
                        requestedVersion = requested,
                        direct = depth == 0,
                        configuration = configuration,
                        selectionReason = selected.selectionReason.descriptions
                            .map { it.description }
                            .sorted()
                            .joinToString("; "),
                        dependencyPath = nextPath,
                        consumers = listOf(consumer)
                    )
                )
            }

            if (selectedId.displayName !in ancestry) {
                ancestry.add(selectedId.displayName)
                traverseDependencyTree(
                    consumer = consumer,
                    configuration = configuration,
                    component = selected,
                    path = nextPath,
                    depth = depth + 1,
                    ancestry = ancestry,
                    records = records
                )
            }
        }
    }
}
