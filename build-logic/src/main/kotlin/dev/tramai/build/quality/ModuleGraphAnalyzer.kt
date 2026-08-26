package dev.tramai.build.quality

import org.gradle.api.artifacts.ProjectDependency
import java.io.File

/**
 * Reads the project model and produces the module dependency graph.
 * In Gradle mode uses the project model; in canonical/filesystem mode
 * produces module inventory only (edges require Gradle resolution).
 */
class ModuleGraphAnalyzer(private val ctx: MeasurementContext) {

    data class GraphResult(
        val modules: List<ModuleInfo>,
        val moduleDependencies: DependencyGraphData,
        val moduleDependenciesTest: DependencyGraphData
    )

    fun analyze(): GraphResult {
        val modules = ctx.modules.map { mod ->
            ModuleInfo(
                name = mod.name,
                path = mod.path,
                layer = mod.layer,
                publishable = mod.publishable
            )
        }

        // Dependency edges require Gradle — skip in canonical/filesystem mode
        val gradleProject = ctx.gradleProject
        if (gradleProject == null) {
            return GraphResult(
                modules = modules,
                moduleDependencies = DependencyGraphData(modules = modules.map { it.path }),
                moduleDependenciesTest = DependencyGraphData(modules = modules.map { it.path })
            )
        }

        val productionConfigurations = setOf("api", "implementation", "compileOnly", "runtimeOnly")
        val testConfigurations = setOf("testApi", "testImplementation", "testCompileOnly", "testRuntimeOnly")
        val productionEdges = mutableListOf<DependencyEdge>()
        val testEdges = mutableListOf<DependencyEdge>()

        val projects = gradleProject.allprojects.filter { it != gradleProject && it.buildFile.exists() }
        val projectPaths = projects.map { it.path }.toSet()

        for (proj in projects) {
            for (configName in productionConfigurations) {
                val config = proj.configurations.findByName(configName) ?: continue
                try {
                    config.dependencies.withType(ProjectDependency::class.java).forEach { dep ->
                        val depPath = (dep as ProjectDependency).path
                        if (depPath in projectPaths) {
                            productionEdges.add(
                                DependencyEdge(from = proj.path, to = depPath, scope = configName)
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Configuration might not be resolvable at configuration time
                }
            }
            for (configName in testConfigurations) {
                val config = proj.configurations.findByName(configName) ?: continue
                try {
                    config.dependencies.withType(ProjectDependency::class.java).forEach { dep ->
                        val depPath = (dep as ProjectDependency).path
                        if (depPath in projectPaths) {
                            testEdges.add(
                                DependencyEdge(from = proj.path, to = depPath, scope = configName)
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        val productionCycles = findCycles(projectPaths.toList(), productionEdges)
        val testCycles = findCycles(projectPaths.toList(), testEdges)

        return GraphResult(
            modules = modules,
            moduleDependencies = DependencyGraphData(
                modules = projectPaths.sorted(),
                edges = productionEdges.distinct().sortedWith(
                    compareBy<DependencyEdge> { it.from }.thenBy { it.to }.thenBy { it.scope }
                ),
                cycles = productionCycles
            ),
            moduleDependenciesTest = DependencyGraphData(
                modules = projectPaths.sorted(),
                edges = testEdges.distinct().sortedWith(
                    compareBy<DependencyEdge> { it.from }.thenBy { it.to }.thenBy { it.scope }
                ),
                cycles = testCycles
            )
        )
    }

    fun generateDot(graph: GraphResult): String = buildString {
        appendLine("digraph TramaiModules {")
        appendLine("  rankdir=TB;")
        appendLine("  node [shape=box, style=filled, fillcolor=lightyellow];")
        for (mod in graph.modules) {
            val color = when (mod.layer) {
                "core-contracts" -> "lightblue"
                "runtime-execution" -> "lightgreen"
                "governance" -> "lightcoral"
                "provider-adapters" -> "lightyellow"
                "framework-integrations" -> "plum"
                "composition" -> "wheat"
                "applications-examples" -> "lightgray"
                else -> "white"
            }
            appendLine("  \"${mod.path}\" [fillcolor=$color, label=\"${mod.name}\\n${mod.layer}\"];")
        }
        for (edge in graph.moduleDependencies.edges) {
            appendLine("  \"${edge.from}\" -> \"${edge.to}\" [label=\"${edge.scope}\"];")
        }
        appendLine("}")
    }

    fun generateMermaid(graph: GraphResult): String = buildString {
        appendLine("```mermaid")
        appendLine("graph TD")
        for (edge in graph.moduleDependencies.edges) {
            val from = edge.from.replace(":", "").replace("-", "_")
            val to = edge.to.replace(":", "").replace("-", "_")
            appendLine("  $from -->|${edge.scope}| $to")
        }
        appendLine("```")
    }

    internal fun findCycles(nodes: List<String>, edges: List<DependencyEdge>): List<List<String>> {
        val adj = mutableMapOf<String, MutableList<String>>()
        for (node in nodes) adj[node] = mutableListOf()
        for (edge in edges) adj.getOrPut(edge.from) { mutableListOf() }.add(edge.to)

        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()

        fun dfs(node: String, path: MutableList<String>) {
            if (node in stack) {
                val cycleStart = path.indexOf(node)
                if (cycleStart >= 0) {
                    cycles.add(path.subList(cycleStart, path.size).toList())
                }
                return
            }
            if (node in visited) return
            visited.add(node)
            stack.add(node)
            path.add(node)
            for (neighbor in adj[node].orEmpty()) {
                dfs(neighbor, path)
            }
            path.removeAt(path.lastIndex)
            stack.remove(node)
        }

        for (node in nodes) {
            dfs(node, mutableListOf())
        }

        return cycles.distinct().sortedBy { it.joinToString(" -> ") }
    }
}
