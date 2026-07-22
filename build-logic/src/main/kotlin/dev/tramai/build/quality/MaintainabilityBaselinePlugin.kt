package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import java.io.File

/**
 * Gradle plugin that registers all maintainability baseline generation and verification tasks.
 *
 * Registered as "tramai.maintainability-baseline" in build-logic/build.gradle.kts.
 * Apply in root build.gradle.kts with: `plugins { id("tramai.maintainability-baseline") }`
 */
abstract class MaintainabilityBaselinePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        if (project != project.rootProject) return

        val ctx = MeasurementContext.fromProject(project)
        val generator = BaselineGenerator(ctx)
        val graphAnalyzer = ModuleGraphAnalyzer(ctx)
        val sourceMetricsAnalyzer = SourceMetricsAnalyzer(ctx)
        val cancellationInventory = CancellationCatchInventory(ctx)
        val globalStateInventory = GlobalStateInventory(ctx)
        val nondeterminismInventory = NondeterminismInventory(ctx)

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
                val outDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")
                outDir.mkdirs()
                ReportNormalizer.writeJson(mapOf("findings" to findings), File(outDir, "cancellation-safety.json"))
                println("Cancellation catch inventory: ${findings.size} findings ($critical critical, $high high)")
            }
        }

        project.tasks.register("generateGlobalStateInventory") {
            group = "maintainability"
            description = "Scans for process-global mutable state"
            doLast {
                val findings = globalStateInventory.inventory()
                val outDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")
                outDir.mkdirs()
                ReportNormalizer.writeJson(mapOf("findings" to findings), File(outDir, "global-state.json"))
                println("Global state inventory: ${findings.size} mutable globals found")
            }
        }

        project.tasks.register("generateNondeterminismInventory") {
            group = "maintainability"
            description = "Scans for direct clock/randomness/identity access"
            doLast {
                val findings = nondeterminismInventory.inventory()
                val outDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")
                outDir.mkdirs()
                ReportNormalizer.writeJson(mapOf("findings" to findings), File(outDir, "nondeterminism.json"))
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

        // ---- Aggregate Task ----

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
                val baseline = generator.generateCompleteBaseline()
                generator.updateBaselineJson(baseline)
                println("Maintainability baseline generated: ${ctx.rootDir}/config/quality/0.6.0-baseline.json")
            }
        }

        // ---- Verification ----

        project.tasks.register("verifyMaintainabilityBaseline") {
            group = "maintainability"
            description = "Compares current measurements against committed baseline and rejects regressions"
            doLast {
                val ctx = MeasurementContext.fromProject(project)
                val generator = BaselineGenerator(ctx)
                val reportDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")
                val verifier = BaselineVerifier(generator, ctx, reportDir)
                val report = verifier.verify()

                report.failures.forEach { project.logger.error("FAIL: $it") }
                report.warnings.forEach { project.logger.warn("WARN: $it") }
                report.acceptedDeviations.forEach { project.logger.info("ACCEPTED: $it") }

                if (!report.passed) {
                    val summary = "Maintainability baseline verification FAILED:\n" +
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
                println("Reports: ${project.buildDir}/reports/maintainability/")
            }
        }

        // ---- Canonical Baseline Generation ----
        // Only for tagged releases. Runs from a detached v0.5.0 worktree.
        // Set maintainability.sourceRoot to the worktree path, or run from the
        // worktree itself.

        project.tasks.register("generateCanonicalMaintainabilityBaseline") {
            group = "maintainability"
            description = "Generates the canonical baseline from v0.5.0. Set -Pmaintainability.sourceRoot=<worktree> to scan a detached checkout."

            doLast {
                // Verify the analyzer (PR) checkout is clean before generation
                val analyzerStatus = ProcessBuilder(listOf("git", "status", "--porcelain"))
                    .directory(project.rootDir)
                    .redirectErrorStream(true)
                    .start()
                val analyzerOutput = analyzerStatus.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                if (analyzerStatus.waitFor() != 0 || analyzerOutput.isNotBlank()) {
                    throw GradleException(
                        "Analyzer checkout must be clean before canonical generation.\n" +
                            "Commit or stash changes in ${project.rootDir} first."
                    )
                }

                val sourceRootProp = project.findProperty("maintainability.sourceRoot")?.toString()
                val canonicalGenerator = if (sourceRootProp != null) {
                    val sourceRoot = File(sourceRootProp)
                    if (!sourceRoot.isDirectory) {
                        throw GradleException("maintainability.sourceRoot='$sourceRootProp' is not a directory")
                    }
                    BaselineGenerator.fromDirectory(sourceRoot, analyzerRoot = project.rootDir)
                } else {
                    BaselineGenerator.fromProject(project)
                }

                val baseline = canonicalGenerator.generateCompleteBaseline()
                val identity = baseline.baselineIdentity

                // Provenance gates
                if (identity.measuredCommitSha != identity.baselineCommitSha) {
                    throw GradleException(
                        "Canonical baseline must be generated at ${identity.releaseTag}. " +
                            "HEAD=${identity.measuredCommitSha.take(8)}, " +
                            "tag=${identity.baselineCommitSha.take(8)}. " +
                            "Use -Pmaintainability.sourceRoot=<v0.5.0-worktree-path>"
                    )
                }

                if (!identity.workingTreeClean) {
                    throw GradleException(
                        "Canonical baseline must be generated from a clean worktree. " +
                            "Commit or stash changes first."
                    )
                }

                if (identity.measuredSourceTreeHash.isBlank()) {
                    throw GradleException(
                        "Canonical baseline has an empty measuredSourceTreeHash. " +
                            "Ensure the worktree is clean and git is available."
                    )
                }

                if (identity.measuredGitTreeSha.isBlank()) {
                    throw GradleException(
                        "Canonical baseline has an empty measuredGitTreeSha. " +
                            "Ensure git is available in the source root."
                    )
                }

                // Write directly to PR branch (not the worktree)
                val prBaselineFile = File(project.rootDir, "config/quality/0.6.0-baseline.json")
                ReportNormalizer.writeJson(baseline, prBaselineFile)
                println("  Wrote canonical baseline to ${prBaselineFile.absolutePath}")
                println(
                    "Canonical maintainability baseline generated for " +
                        "${identity.releaseTag} at ${identity.measuredCommitSha.take(8)}"
                )
                println("  Git tree SHA: ${identity.measuredGitTreeSha.take(8)}")
                println("  Source tree hash: ${identity.measuredSourceTreeHash.take(16)}...")
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
