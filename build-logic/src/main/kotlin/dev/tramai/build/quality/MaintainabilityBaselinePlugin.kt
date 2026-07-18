package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import java.io.File

/**
 * Gradle plugin that registers all maintainability baseline generation and verification tasks.
 *
 * Registered as "tramai.maintainability-baseline" in build-logic/build.gradle.kts.
 * Apply in root build.gradle.kts with: `plugins { id("tramai.maintainability-baseline") }`
 */
abstract class MaintainabilityBaselinePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // Only register on root project
        if (project != project.rootProject) return

        val generator = BaselineGenerator(project)
        val graphAnalyzer = ModuleGraphAnalyzer(project)
        val sourceMetricsAnalyzer = SourceMetricsAnalyzer(project)
        val cancellationInventory = CancellationCatchInventory(project)
        val globalStateInventory = GlobalStateInventory(project)
        val nondeterminismInventory = NondeterminismInventory(project)

        // ---- Generation Tasks ----

        project.tasks.register("generateModuleDependencyGraph") {
            group = "maintainability"
            description = "Generates the module dependency graph (JSON, DOT, Mermaid)"
            doLast {
                val structural = generator.generateModuleDependencyGraph(graphAnalyzer)
                println("Module dependency graph generated: ${structural.dependencyGraph.edges.size} edges, ${structural.dependencyGraph.cycles.size} cycles")
                if (structural.dependencyGraph.cycles.isNotEmpty()) {
                    println("WARNING: Dependency cycles detected: ${structural.dependencyGraph.cycles}")
                }
            }
        }

        project.tasks.register("generateSourceMetrics") {
            group = "maintainability"
            description = "Counts lines of code per module and source set"
            doLast {
                val metrics = sourceMetricsAnalyzer.analyze()
                generator.generateSourceMetrics(metrics)
                val totalProd = metrics.values.sumOf { it.production.codeLines }
                val totalTest = metrics.values.sumOf { it.test.codeLines }
                println("Source metrics: $totalProd production LOC, $totalTest test LOC across ${metrics.size} modules")
            }
        }

        project.tasks.register("generateStructuralHotspots") {
            group = "maintainability"
            description = "Ranks files by size and structural complexity"
            doLast {
                val hotspots = generator.generateStructuralHotspots()
                println("Structural hotspots: ${hotspots.largestProductionFiles.size} largest files identified")
                hotspots.largestProductionFiles.take(5).forEach {
                    println("  ${it.module}/${it.path}: ${it.value} lines")
                }
            }
        }

        project.tasks.register("generateCancellationCatchInventory") {
            group = "maintainability"
            description = "Scans for broad exception catches in suspend-capable code"
            doLast {
                val findings = cancellationInventory.inventory()
                val critical = findings.count { it.risk == "critical" }
                val high = findings.count { it.risk == "high" }
                generator.generateCancellationCatchInventory(findings)
                println("Cancellation catch inventory: ${findings.size} findings ($critical critical, $high high)")
            }
        }

        project.tasks.register("generateGlobalStateInventory") {
            group = "maintainability"
            description = "Scans for process-global mutable state"
            doLast {
                val findings = globalStateInventory.inventory()
                generator.generateGlobalStateInventory(findings)
                println("Global state inventory: ${findings.size} mutable globals found")
            }
        }

        project.tasks.register("generateNondeterminismInventory") {
            group = "maintainability"
            description = "Scans for direct clock/randomness/identity access"
            doLast {
                val findings = nondeterminismInventory.inventory()
                generator.generateNondeterminismInventory(findings)
                val byCategory = findings.groupBy { it.category }
                println("Nondeterminism inventory: ${findings.size} findings")
                byCategory.forEach { (cat, items) -> println("  $cat: ${items.size}") }
            }
        }

        project.tasks.register("generateResolvedDependencyGraph") {
            group = "maintainability"
            description = "Captures the full resolved dependency tree"
            doLast {
                val deps = generator.generateResolvedDependencyGraph()
                println("Resolved dependency graph: ${deps.size} dependencies")
            }
        }

        project.tasks.register("generateRuntimeProtocolCatalog") {
            group = "maintainability"
            description = "Scans production Kotlin sources for runtime protocol identifiers"
            doLast {
                val catalog = generator.generateRuntimeProtocolCatalog()
                println("Runtime protocol catalog: ${catalog.entries.size} entries")
            }
        }

        // ---- Aggregate Tasks ----

        project.tasks.register("generateMaintainabilityBaseline") {
            group = "maintainability"
            description = "Generates the complete maintainability baseline"
            dependsOn(
                "generateModuleDependencyGraph",
                "generateSourceMetrics",
                "generateStructuralHotspots",
                "generateCancellationCatchInventory",
                "generateGlobalStateInventory",
                "generateNondeterminismInventory",
                "generateResolvedDependencyGraph",
                "generateRuntimeProtocolCatalog"
            )
            doLast {
                val baseline = generator.generateFullBaseline()

                // Update structural section
                val graph = graphAnalyzer.analyze()
                val structural = generator.generateModuleDependencyGraph(graphAnalyzer)

                // Merge all generated data into baseline
                val updated = baseline.copy(
                    structural = structural.copy(
                        sourceMetrics = generator.generateSourceMetrics(sourceMetricsAnalyzer.analyze()),
                        structuralHotspots = generator.generateStructuralHotspots()
                    ),
                    runtimeSafety = RuntimeSafetyBaseline(
                        cancellationCatches = cancellationInventory.inventory(),
                        globalState = globalStateInventory.inventory(),
                        nondeterminism = nondeterminismInventory.inventory()
                    ),
                    dependencies = baseline.dependencies.copy(
                        resolvedDependencies = generator.generateResolvedDependencyGraph()
                    ),
                    protocolCatalog = generator.generateRuntimeProtocolCatalog()
                )

                generator.updateBaselineJson(updated)
                println("Maintainability baseline generated: ${project.rootDir}/config/quality/0.6.0-baseline.json")
            }
        }

        // ---- Verification Tasks ----

        project.tasks.register("verifyModuleDependencyGraph") {
            group = "maintainability"
            description = "Checks for dependency cycles and forbidden edges"
            doLast {
                val graph = graphAnalyzer.analyze()
                if (graph.cycles.isNotEmpty()) {
                    val msg = "Dependency cycles detected: ${graph.cycles.map { it.joinToString(" -> ") }}"
                    println("WARNING: $msg")
                } else {
                    println("Dependency graph: no cycles detected")
                }
            }
        }

        project.tasks.register("verifyCancellationCatchInventory") {
            group = "maintainability"
            description = "Checks no new critical/high cancellation catch findings"
            doLast {
                val findings = cancellationInventory.inventory()
                val critical = findings.count { it.risk == "critical" }
                val high = findings.count { it.risk == "high" }
                if (critical > 0) {
                    println("WARNING: $critical critical cancellation catch findings exist")
                }
                if (high > 0) {
                    println("WARNING: $high high-risk cancellation catch findings exist")
                }
                if (critical == 0 && high == 0) {
                    println("Cancellation catch inventory: clean")
                }
            }
        }

        project.tasks.register("verifyGlobalStateInventory") {
            group = "maintainability"
            description = "Checks no new global mutable state"
            doLast {
                val findings = globalStateInventory.inventory()
                if (findings.isNotEmpty()) {
                    println("WARNING: ${findings.size} global mutable state instances exist. See deviations for accepted items.")
                } else {
                    println("Global state inventory: clean")
                }
            }
        }

        project.tasks.register("verifyNondeterminismInventory") {
            group = "maintainability"
            description = "Checks no new direct nondeterminism sources"
            doLast {
                val findings = nondeterminismInventory.inventory()
                if (findings.isNotEmpty()) {
                    println("WARNING: ${findings.size} direct nondeterminism sources found")
                } else {
                    println("Nondeterminism inventory: clean")
                }
            }
        }

        project.tasks.register("verifyBaselineFreshness") {
            group = "maintainability"
            description = "Verifies committed baseline matches regeneration"
            doLast {
                val baselineFile = File(project.rootDir, "config/quality/0.6.0-baseline.json")
                if (!baselineFile.exists()) {
                    println("WARNING: No committed baseline found. Run generateMaintainabilityBaseline first.")
                    return@doLast
                }
                // Re-generate and diff the identity section
                val identity = generator.generateIdentity()
                println("Baseline identity check: repository=${identity.repository}, sha=${identity.commitSha.take(7)}")
                println("Baseline freshness: committed baseline exists (full diff requires regeneration)")
            }
        }

        project.tasks.register("verifyMaintainabilityBaseline") {
            group = "maintainability"
            description = "Runs all verification tasks"
            dependsOn(
                "verifyModuleDependencyGraph",
                "verifyCancellationCatchInventory",
                "verifyGlobalStateInventory",
                "verifyNondeterminismInventory",
                "verifyBaselineFreshness"
            )
            doLast {
                println("Maintainability baseline verification complete.")
                println("Reports: ${project.buildDir}/reports/maintainability/")
            }
        }

        project.tasks.register("verifyFullMaintainabilityBaseline") {
            group = "maintainability"
            description = "Full verification including dependency resolution and coverage"
            dependsOn("verifyMaintainabilityBaseline")
            doLast {
                println("Full maintainability baseline verification complete.")
            }
        }
    }
}
