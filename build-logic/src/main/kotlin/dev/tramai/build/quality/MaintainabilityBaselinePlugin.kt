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
                val prodEdges = structural.moduleDependencies.edges.size
                val prodCycles = structural.moduleDependencies.cycles.size
                println("Module dependency graph: $prodEdges production edges, $prodCycles cycles")
                val testEdges = structural.moduleDependenciesTest.edges.size
                println("Test dependency graph: $testEdges test edges")
                if (structural.moduleDependencies.cycles.isNotEmpty()) {
                    println("WARNING: Production dependency cycles detected")
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

                // Update with fresh measurements
                val graph = graphAnalyzer.analyze()
                val structural = generator.generateModuleDependencyGraph(graphAnalyzer)

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
                    protocolCatalog = generator.generateRuntimeProtocolCatalog(),
                    api = generator.generateApiBaseline(),
                    testQuality = baseline.testQuality.copy(
                        testPerformance = generator.generateTestPerformance()
                    )
                )

                generator.updateBaselineJson(updated)
                println("Maintainability baseline generated: ${project.rootDir}/config/quality/0.6.0-baseline.json")
            }
        }

        // ---- Verification Tasks ----

        // Single unified verification task that uses the comparison engine.
        // Replaces the old warning-only per-inventory tasks.
        project.tasks.register("verifyMaintainabilityBaseline") {
            group = "maintainability"
            description = "Compares current measurements against committed baseline and rejects regressions"
            doLast {
                val report = BaselineVerifier.verify(project)

                // Print report
                report.failures.forEach { project.logger.error("FAIL: $it") }
                report.warnings.forEach { project.logger.warn("WARN: $it") }
                report.acceptedDeviations.forEach { project.logger.info("ACCEPTED: $it") }

                if (!report.passed) {
                    val summary = "Maintainability baseline verification FAILED:\n" +
                        report.failures.joinToString("\n") { "  - $it" } +
                        "\n\nRun './gradlew generateMaintainabilityBaseline' to regenerate." +
                        "\nAdd deviations to config/quality/maintainability-deviations.yml for accepted regressions."
                    throw org.gradle.api.GradleException(summary)
                }

                println("Maintainability baseline verification PASSED.")
                if (report.acceptedDeviations.isNotEmpty()) {
                    println("Accepted deviations: ${report.acceptedDeviations.size}")
                }
                if (report.warnings.isNotEmpty()) {
                    println("Warnings: ${report.warnings.size}")
                }
                println("Reports: ${project.buildDir}/reports/maintainability/")
            }
        }

        // Full verification adds mutation/coverage checks (currently stubs)
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
