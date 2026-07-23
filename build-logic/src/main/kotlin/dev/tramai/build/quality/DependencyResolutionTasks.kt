package dev.tramai.build.quality

import org.gradle.api.DefaultTask
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
import javax.inject.Inject

abstract class GenerateResolvedDependencyBaselineTask : DefaultTask() {

    init {
        outputs.upToDateWhen { false }
    }

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun collect() {
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

        val normalized = DependencyEdgeNormalizer.normalize(records)
        outputFile.get().asFile.let { file ->
            file.parentFile.mkdirs()
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
}
