package dev.tramai.build.quality

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Reads the Gradle project model and produces the module dependency graph.
 */
class ModuleGraphAnalyzer(private val rootProject: Project) {

    data class GraphResult(
        val modules: List<ModuleInfo>,
        val moduleDependencies: DependencyGraphData,
        val moduleDependenciesTest: DependencyGraphData
    )

    fun analyze(): GraphResult {
        val projects = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }
        val modules = projects.map { proj ->
            ModuleInfo(
                name = proj.name,
                path = proj.path,
                layer = classifyLayer(proj),
                publishable = isPublishable(proj)
            )
        }

        val productionConfigurations = setOf("api", "implementation", "compileOnly", "runtimeOnly")
        val testConfigurations = setOf("testApi", "testImplementation", "testCompileOnly", "testRuntimeOnly")
        val productionEdges = mutableListOf<DependencyEdge>()
        val testEdges = mutableListOf<DependencyEdge>()

        for (proj in projects) {
            for (config in proj.configurations) {
                if (config.name !in productionConfigurations && config.name !in testConfigurations) continue

                try {
                    for (dep in config.dependencies) {
                        if (dep is ProjectDependency) {
                            val edge = DependencyEdge(
                                from = proj.path,
                                to = dep.dependencyProject.path,
                                scope = config.name
                            )
                            if (config.name in productionConfigurations) {
                                productionEdges.add(edge)
                            } else {
                                testEdges.add(edge)
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Some lazily-created configurations are not inspectable at analysis time.
                }
            }
        }

        val modulePaths = modules.map { it.path }
        val distinctProductionEdges = productionEdges.distinct()
        val distinctTestEdges = testEdges.distinct()

        return GraphResult(
            modules = modules,
            moduleDependencies = DependencyGraphData(
                modules = modulePaths,
                edges = distinctProductionEdges,
                cycles = findCycles(modulePaths, distinctProductionEdges)
            ),
            moduleDependenciesTest = DependencyGraphData(
                modules = modulePaths,
                edges = distinctTestEdges,
                cycles = findCycles(modulePaths, distinctTestEdges)
            )
        )
    }

    private fun classifyLayer(project: Project): String {
        val name = project.name
        return when {
            name == "tramai-core" -> "core-contracts"
            name == "tramai-bom" -> "core-contracts"
            name in listOf("tramai-engine", "tramai-structured", "tramai-orchestration", "tramai-standalone") -> "runtime-execution"
            name in listOf("tramai-security", "tramai-sovereign") -> "governance-security"
            name.startsWith("tramai-persistence") -> "persistence"
            name in listOf("tramai-anthropic", "tramai-azure-openai", "tramai-bedrock", "tramai-deepseek",
                "tramai-gemini", "tramai-ollama", "tramai-openai") -> "provider-adapters"
            name == "tramai-spring" || name.startsWith("tramai-spring-boot-starter") -> "framework-integrations"
            name in listOf("tramai-observability", "tramai-platform", "tramai-server", "tramai-dashboard",
                "tramai-mcp") -> "operations-observability"
            name in listOf("tramai-rag", "tramai-memory", "tramai-memory-store", "tramai-scheduler",
                "tramai-embedding") -> "higher-capabilities"
            name.startsWith("tramai-vectorstore") -> "higher-capabilities"
            project.path.startsWith(":examples:") -> "applications-examples"
            name == "tramai-testing" -> "testing-support"
            else -> "unknown"
        }
    }

    private fun isPublishable(project: Project): Boolean {
        // Known publishable modules from root build.gradle.kts
        val publishableNames = setOf(
            "tramai-anthropic", "tramai-azure-openai", "tramai-bedrock", "tramai-bom",
            "tramai-core", "tramai-deepseek", "tramai-embedding", "tramai-engine",
            "tramai-gemini", "tramai-memory", "tramai-observability", "tramai-ollama",
            "tramai-openai", "tramai-orchestration", "tramai-platform", "tramai-spring",
            "tramai-standalone", "tramai-sovereign", "tramai-persistence-file",
            "tramai-structured", "tramai-testing", "tramai-vectorstore-spi",
            "tramai-vectorstore-chroma", "tramai-vectorstore-pgvector", "tramai-rag",
            "tramai-security", "tramai-spring-boot-starter-sovereign",
            "tramai-spring-boot-starter-sovereign-persistence-file",
            "tramai-spring-boot-starter-sovereign-persistence-jdbc",
            "tramai-spring-boot-starter-sovereign-ops",
            "tramai-spring-boot-starter-sovereign-ops-rest",
            "tramai-spring-boot-starter-sovereign-ops-actuator",
            "tramai-spring-boot-starter-sovereign-ops-micrometer",
            "tramai-spring-boot-starter-sovereign-ops-observability",
            "tramai-spring-boot-starter-local-provider-openai",
            "tramai-scheduler"
        )
        return project.name in publishableNames
    }

    private fun findCycles(moduleNames: List<String>, edges: List<DependencyEdge>): List<List<String>> {
        val adj = mutableMapOf<String, MutableList<String>>()
        for (edge in edges) {
            adj.getOrPut(edge.from) { mutableListOf() }.add(edge.to)
        }

        val cycles = mutableListOf<List<String>>()
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()
        val stack = mutableListOf<String>()

        fun dfs(node: String) {
            visited.add(node)
            inStack.add(node)
            stack.add(node)

            for (neighbor in adj.getOrDefault(node, emptyList())) {
                if (neighbor !in visited) {
                    dfs(neighbor)
                } else if (neighbor in inStack) {
                    val cycleStart = stack.indexOf(neighbor)
                    if (cycleStart >= 0) {
                        cycles.add(stack.subList(cycleStart, stack.size) + neighbor)
                    }
                }
            }

            stack.removeAt(stack.size - 1)
            inStack.remove(node)
        }

        for (module in moduleNames) {
            if (module !in visited) {
                dfs(module)
            }
        }

        return cycles
    }

    fun generateDot(graph: GraphResult): String {
        val sb = StringBuilder()
        sb.appendLine("digraph TramaiModules {")
        sb.appendLine("  rankdir=TB;")
        sb.appendLine("  node [shape=box, style=filled];")

        val layerColors = mapOf(
            "core-contracts" to "lightblue",
            "runtime-execution" to "lightgreen",
            "governance-security" to "lightcoral",
            "persistence" to "lightyellow",
            "provider-adapters" to "lightsalmon",
            "framework-integrations" to "plum",
            "operations-observability" to "lightgray",
            "higher-capabilities" to "wheat",
            "applications-examples" to "white",
            "testing-support" to "lightcyan",
            "unknown" to "white"
        )

        for (mod in graph.modules) {
            val color = layerColors[mod.layer] ?: "white"
            sb.appendLine("  \"${mod.path}\" [fillcolor=$color, label=\"${mod.path}\\n(${mod.layer})\"];")
        }

        for (edge in graph.moduleDependencies.edges) {
            sb.appendLine("  \"${edge.from}\" -> \"${edge.to}\" [label=\"${edge.scope}\"];")
        }

        sb.appendLine("}")
        return sb.toString()
    }

    fun generateMermaid(graph: GraphResult): String {
        val sb = StringBuilder()
        sb.appendLine("graph TD")
        val safeNames = graph.modules.associate {
            it.path to it.path.removePrefix(":").replace("-", "_").replace(":", "_")
        }
        for (edge in graph.moduleDependencies.edges) {
            sb.appendLine("  ${safeNames[edge.from]} --> ${safeNames[edge.to]}")
        }
        return sb.toString()
    }
}
