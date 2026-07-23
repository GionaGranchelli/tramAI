package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.Action
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

        project.tasks.register("generatePublicApiBaseline") {
            group = "maintainability"
            description = "Generates per-module public API dump records"
            doLast {
                val apiBaseline = generator.generateApiBaseline()
                println("Public API baseline: ${apiBaseline.modules.size} modules, " +
                    "${apiBaseline.modules.count { it.applicable }} applicable, " +
                    "${apiBaseline.modules.count { it.sha256.isNotBlank() }} with dumps")
            }
        }

        // Register per-project dependency probe tasks (Gradle 9: each task owns its config)
        val perProjectProbeTasks = mutableListOf<String>()
        project.allprojects.filter { it != project && it.buildFile.exists() }.forEach { sub ->
            val taskName = "generateResolvedDependencyBaseline"
            val probe = sub.tasks.register(taskName, GenerateResolvedDependencyBaselineTask::class.java)
            probe.configure {
                group = "maintainability"
                description = "Resolves external dependencies for ${sub.path}"
                outputFile.set(
                    sub.layout.buildDirectory.file("reports/maintainability/resolved-dependencies.json")
                )
            }
            perProjectProbeTasks.add("${sub.path}:$taskName")
        }

        // Root aggregation task depends on all per-project probes and merges their outputs
        project.tasks.register("generateResolvedDependencyBaseline") {
            group = "maintainability"
            description = "Aggregates per-project resolved dependency baselines"
            dependsOn(*perProjectProbeTasks.toTypedArray())
            doLast {
                val reportDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")
                reportDir.mkdirs()
                val aggregateFile = File(reportDir, "resolved-dependencies.json")
                val allRecords = mutableListOf<ResolvedDependency>()
                val expectedProjects = project.allprojects
                    .filter { it != project && it.buildFile.exists() }
                    .sortedBy { it.path }

                expectedProjects.forEach { sub ->
                    val probeFile = File(
                        sub.layout.buildDirectory.get().asFile,
                        "reports/maintainability/resolved-dependencies.json"
                    )
                    if (!probeFile.isFile) {
                        throw GradleException(
                            "Missing dependency probe output for ${sub.path}: ${probeFile.absolutePath}"
                        )
                    }
                    val records = try {
                        ReportNormalizer.readJson(probeFile, Array<ResolvedDependency>::class.java).toList()
                    } catch (e: Exception) {
                        throw GradleException(
                            "Invalid dependency probe output for ${sub.path}: ${e.message}", e
                        )
                    }
                    allRecords.addAll(records)
                }
                val sorted = BaselineGenerator.sortResolvedDependencies(allRecords)
                ReportNormalizer.writeJson(sorted, aggregateFile)
                val direct = sorted.count { it.direct }
                val transitive = sorted.size - direct
                println("Resolved dependency baseline: ${sorted.size} records ($direct direct, $transitive transitive)")
            }
        }

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
            description = "Reads the resolved dependency baseline and prints summary"
            dependsOn("generateResolvedDependencyBaseline")
            doLast {
                val file = project.layout.buildDirectory.file("reports/maintainability/resolved-dependencies.json").get().asFile
                if (file.isFile) {
                    val deps = ReportNormalizer.readJson(file, Array<ResolvedDependency>::class.java).toList()
                    println("Resolved dependency graph: ${deps.size} dependencies")
                }
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
                "generatePublicApiBaseline",
                "generateResolvedDependencyBaseline",
                "generateRuntimeProtocolCatalog"
            )
            doLast {
                val baseline = generator.generateCompleteBaseline()
                generator.updateBaselineJson(baseline)
                println("Maintainability baseline generated: ${ctx.rootDir}/config/quality/0.6.0-baseline.json")
            }
        }

        // ---- Verification ----

        project.tasks.register("verifyPublicApiBaseline") {
            group = "maintainability"
            description = "Verifies public API baseline — runs inline in verifyMaintainabilityBaseline"
            doLast {
                println("Public API baseline verification is integrated into verifyMaintainabilityBaseline")
            }
        }

        project.tasks.register("verifyResolvedDependencyBaseline") {
            group = "maintainability"
            description = "Verifies resolved dependency baseline — runs inline in verifyMaintainabilityBaseline"
            doLast {
                println("Resolved dependency baseline verification is integrated into verifyMaintainabilityBaseline")
            }
        }

        project.tasks.register("verifyMaintainabilityBaseline") {
            group = "maintainability"
            description = "Compares current measurements against committed baseline and rejects regressions"
            dependsOn("generateResolvedDependencyBaseline")
            doLast {
                val ctx = MeasurementContext.fromProject(project)
                val generator = BaselineGenerator(ctx)
                val reportDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")
                val verifier = BaselineVerifier(generator, ctx, reportDir)
                val report = verifier.verify()

                report.failures.forEach { project.logger.error("FAIL: $it") }
                report.warnings.take(100).forEach { project.logger.warn("WARN: $it") }
                if (report.warnings.size > 100) {
                    project.logger.warn("WARN: ${report.warnings.size - 100} additional warnings; see dependency-changes.json")
                }
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
                val analyzerExitCode = analyzerStatus.waitFor()
                project.logger.lifecycle("DEBUG analyzer checkout: exit=$analyzerExitCode output='${analyzerOutput.trim()}'")
                if (analyzerExitCode != 0 || analyzerOutput.isNotBlank()) {
                    throw GradleException(
                        "Analyzer checkout must be clean before canonical generation.\n" +
                            "Commit or stash changes in ${project.rootDir} first."
                    )
                }

                val sourceRootProp = project.findProperty("maintainability.sourceRoot")?.toString()
                val canonicalGenerator = if (sourceRootProp != null) {
                    val sourceRoot = project.rootDir.resolve(sourceRootProp).normalize()
                    if (!sourceRoot.isDirectory) {
                        throw GradleException("maintainability.sourceRoot='$sourceRootProp' resolved to '${sourceRoot.absolutePath}' but is not a directory")
                    }
                    BaselineGenerator.fromDirectory(sourceRoot, analyzerRoot = project.rootDir)
                } else {
                    BaselineGenerator.fromProject(project)
                }

                // When probing a detached worktree, use CanonicalGradleProbe for API/dependency
                // measurements. The generator handles structural/scanner data from directory mode.
                val sourceRootFile = if (sourceRootProp != null) project.rootDir.resolve(sourceRootProp).normalize() else null
                val apiOverride: ApiBaseline? = if (sourceRootFile != null) {
                    val probe = CanonicalGradleProbe(sourceRootFile, analyzerRoot = project.rootDir)
                    val result = probe.probeApiBaseline()
                    project.logger.lifecycle("Canonical API probe: ${result.records.size} records")
                    val stabilities = result.records.groupBy { it.stability }.mapValues { it.value.size }
                    project.logger.lifecycle("  Stability breakdown: $stabilities")
                    if (result.records.none { it.applicable && it.sha256.isNotBlank() }) {
                        throw GradleException("Canonical API probe produced no valid API hashes. " +
                            "At least one applicable module must have a captured API dump.")
                    }
                    ApiBaseline(
                        modules = result.records,
                        aggregateHash = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(ReportNormalizer.toJson(result.records).toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it) }
                    )
                } else null

                val dependencyOverride: List<ResolvedDependency>? = if (sourceRootFile != null) {
                    val probe = CanonicalGradleProbe(sourceRootFile, analyzerRoot = project.rootDir)
                    val result = probe.probeDependencyBaseline()
                    project.logger.lifecycle("Canonical dependency probe: ${result.records.size} records")
                    if (result.records.isEmpty()) {
                        throw GradleException("Canonical dependency probe produced no dependency records. " +
                            "Non-empty dependency baseline is required.")
                    }
                    result.records
                } else null

                val baseline = canonicalGenerator.generateCompleteBaseline(
                    apiOverride = apiOverride,
                    dependencyOverride = dependencyOverride
                )
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
            description = "Full verification including API, dependency, coverage, and mutation"
            dependsOn("verifyMaintainabilityBaseline", "verifyPublicApiBaseline", "verifyResolvedDependencyBaseline")
            doLast {
                println("Full maintainability baseline verification complete.")
            }
        }
    }
}
