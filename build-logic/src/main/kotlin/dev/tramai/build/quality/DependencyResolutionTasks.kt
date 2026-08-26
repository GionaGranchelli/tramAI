package dev.tramai.build.quality

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.GradleException
import java.io.File

abstract class GenerateResolvedDependencyBaselineTask : DefaultTask() {

    init {
        outputs.upToDateWhen { false }
    }

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun collect() {
        val normalized = DependencyEdgeNormalizer.normalize(collectResolvedDependencies(project))
        outputFile.get().asFile.let { file ->
            file.parentFile.mkdirs()
            ReportNormalizer.writeJson(normalized, file)
        }
    }
}

/**
 * Resolves [project]'s compile/runtime classpath and returns raw dependency
 * records. Shared by [GenerateResolvedDependencyBaselineTask] (per-project
 * probe files) and the architecture gate (in-process aggregate generation).
 * Throws [GradleException] on an unresolved dependency — callers decide whether
 * that aborts the build (probe task) or becomes fail-closed evidence (gate).
 */
internal fun collectResolvedDependencies(project: Project): List<ResolvedDependency> {
    val records = mutableListOf<ResolvedDependency>()

    // Only resolve configurations owned by THIS project (Gradle 9 lock rule)
    listOf("compileClasspath", "runtimeClasspath").forEach { configName ->
        val config = project.configurations.findByName(configName)
        if (config == null || !config.isCanBeResolved) return@forEach

        val resolutionResult = config.incoming.resolutionResult
        val root = resolutionResult.rootComponent.get()

        traverseDependencyTree(
            consumer = project.path,
            configuration = configName,
            component = root,
            path = listOf(project.path),
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
 */
abstract class ArchitectureDependencyProbeTask : DefaultTask() {

    init {
        outputs.upToDateWhen { false }
    }

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun collect() {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        try {
            val normalized = DependencyEdgeNormalizer.normalize(collectResolvedDependencies(project))
            ReportNormalizer.writeJson(normalized, file)
        } catch (exception: Exception) {
            ReportNormalizer.writeJson(
                listOf(
                    mapOf(
                        "resolutionFailed" to true,
                        "message" to (exception.message ?: exception.javaClass.name),
                    ),
                ),
                file,
            )
        }
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
