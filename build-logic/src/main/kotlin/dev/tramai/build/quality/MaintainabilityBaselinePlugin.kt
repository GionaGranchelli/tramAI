package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.Action
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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
        val testQualityConfiguration = TestQualityConfiguration.load(project.rootDir)
        val criticalModules = testQualityConfiguration.criticalModules.toSet()
        val reportDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")

        // Register JaCoCo on critical modules using provider-safe API (no tasks.matching / executionData(TaskCollection))
        val criticalTestTaskPaths = criticalModules.sorted().map { "$it:test" }
        val criticalCoverageReportTaskPaths = criticalModules.sorted().map { "$it:jacocoTestReport" }

        project.allprojects
            .filter { it.path in criticalModules }
            .forEach { criticalProject ->
                criticalProject.pluginManager.withPlugin("java") {
                    criticalProject.pluginManager.apply("jacoco")

                    val testTask = criticalProject.tasks.named("test", Test::class.java)
                    val excludedPatterns = testQualityConfiguration.coverage.exclusions.map { it.pattern }

                    criticalProject.tasks.named("jacocoTestReport", JacocoReport::class.java) {
                        dependsOn(testTask)
                        reports {
                            xml.required.set(true)
                            html.required.set(true)
                        }
                        // Filter class directories to exclude patterns using Gradle fileTree
                        val mainSourceSet = criticalProject.extensions.getByType(
                            org.gradle.api.plugins.JavaPluginExtension::class.java
                        ).sourceSets.getByName("main")
                        classDirectories.from(
                            mainSourceSet.output.classesDirs.files.map { root ->
                                criticalProject.fileTree(root) {
                                    exclude(excludedPatterns)
                                }
                            }
                        )
                    }
                }
            }

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

        // Fail-soft per-project dependency probes for the architecture gate.
        // Unlike the tasks above they never throw on unresolved dependencies;
        // the gate reads their outputs and converts resolution failure into
        // typed fail-closed evidence, so the report is always written.
        val architectureProbeTasks = mutableListOf<String>()
        project.allprojects.filter { it != project && it.buildFile.exists() }.forEach { sub ->
            val probe = sub.tasks.register("architectureDependencyProbe", ArchitectureDependencyProbeTask::class.java)
            probe.configure {
                group = "verification"
                description = "Resolves external dependencies for ${sub.path} (fail-soft, architecture gate)"
                outputFile.set(
                    sub.layout.buildDirectory.file("reports/maintainability/architecture-dependencies.json")
                )
            }
            architectureProbeTasks.add("${sub.path}:architectureDependencyProbe")
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

        project.tasks.register("generateCoverageBaseline") {
            group = "maintainability"
            description = "Generates JaCoCo coverage measurements for critical modules"
            dependsOn(criticalCoverageReportTaskPaths)
            doLast {
                val coverage = CoverageCollector(
                    project.rootDir,
                    testQualityConfiguration
                ).collect()
                reportDir.mkdirs()
                ReportNormalizer.writeJson(coverage, File(reportDir, "coverage-summary.json"))
                println(
                    "Critical coverage baseline: ${coverage.criticalModules.size} modules, " +
                        "${"%.2f".format(coverage.overallLineCoverage)}% lines, " +
                        "${"%.2f".format(coverage.overallBranchCoverage)}% branches"
                )
            }
        }

        project.tasks.register("generateCriticalMutationBaseline") {
            group = "maintainability"
            description = "Runs targeted PITest mutation analysis and generates the critical mutation baseline"
            doLast {
                val mutationRoot = File(reportDir, "mutation")
                mutationRoot.mkdirs()
                val initScript = File(reportDir, "critical-mutation-probe.init.gradle")
                initScript.writeText(
                    mutationInitScript(testQualityConfiguration, mutationRoot),
                    Charsets.UTF_8
                )
                testQualityConfiguration.mutation.targetFamilies.keys.sorted().forEach { family ->
                    runNestedGradle(
                        project,
                        listOf(
                            "--init-script",
                            initScript.absolutePath,
                            "-PtramaiMutationFamily=$family",
                            "canonicalMutationProbe"
                        )
                    )
                }
                val mutation = generator.generateMutationBaseline(
                    testQualityConfiguration,
                    mutationRoot
                )
                ReportNormalizer.writeJson(mutation, File(reportDir, "mutation-summary.json"))
                println(
                    "Critical mutation baseline: ${mutation.totalMutants} mutants, " +
                        "${"%.2f".format(mutation.mutationScore)}% killed"
                )
            }
        }

        project.tasks.register("generateTestPerformanceBaseline") {
            group = "maintainability"
            description = "Runs one warm-up and three measured critical-module test executions"
            doLast {
                val outputRoot = File(reportDir, "test-performance")
                val runsRoot = File(outputRoot, "runs")
                if (runsRoot.exists()) runsRoot.deleteRecursively()
                runsRoot.mkdirs()
                val testTaskPaths = criticalModules.sorted().map { "$it:test" }

                runNestedGradle(project, listOf("--rerun-tasks") + testTaskPaths)
                val collector = TestPerformanceCollector(project.rootDir, testQualityConfiguration)
                val observations = (1..3).flatMap { run ->
                    runNestedGradle(project, listOf("--rerun-tasks") + testTaskPaths)
                    copyTestReports(project.rootDir, criticalModules, File(runsRoot, run.toString()))
                    collector.collectMeasuredRun(
                        run = run,
                        gradleVersion = project.gradle.gradleVersion,
                        reportRoot = runsRoot
                    )
                }
                val performance = TestPerformanceAggregator().aggregate(observations)
                ReportNormalizer.writeJson(
                    observations,
                    File(outputRoot, "observations.json")
                )
                ReportNormalizer.writeJson(performance, File(outputRoot, "median.json"))
                println(
                    "Test performance baseline: ${performance.totalTestCount} tests, " +
                        "${performance.totalDurationMs}ms aggregate median"
                )
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

        project.tasks.register("generateFullMaintainabilityBaseline") {
            group = "maintainability"
            description = "Generates structural, API, dependency, coverage, mutation, and timing baselines"
            dependsOn(
                "generateMaintainabilityBaseline",
                "generateCoverageBaseline",
                "generateTestPerformanceBaseline",
                "generateCriticalMutationBaseline"
            )
            doLast {
                val baselineFile = File(project.rootDir, "config/quality/0.6.0-baseline.json")
                val generated = generator.generateFullBaseline()
                val structuralBaseline = ReportNormalizer.readJson(
                    baselineFile,
                    BaselineDocument::class.java
                )
                val coverage = ReportNormalizer.readJson(
                    File(reportDir, "coverage-summary.json"),
                    CoverageData::class.java
                )
                val mutation = ReportNormalizer.readJson(
                    File(reportDir, "mutation-summary.json"),
                    MutationData::class.java
                )
                val performance = ReportNormalizer.readJson(
                    File(reportDir, "test-performance/median.json"),
                    TestPerformanceData::class.java
                )
                generator.updateBaselineJson(
                    structuralBaseline.copy(
                        baselineIdentity = generated.baselineIdentity,
                        testQuality = TestQualityBaseline(performance, coverage, mutation),
                        generatedAt = generated.generatedAt,
                        generatedBy = "generateFullMaintainabilityBaseline",
                        environment = generated.environment
                    )
                )
                println("Full maintainability baseline generated: ${baselineFile.absolutePath}")
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

        project.tasks.register("verifyCancellationSafety") {
            group = "maintainability"
            description = "Scans all production source for broad catches in suspend-capable code and rejects newly introduced critical/high findings and risk worsenings. Accepts -PtramaiCancellationBaseSha for PR base SHA comparison."
            doLast {
                val scanningCtx = MeasurementContext.fromProject(project)
                val inventory = CancellationCatchInventory(scanningCtx)
                val findings = inventory.inventory()

                // Derive scope from all non-example modules
                val scopedModules = scanningCtx.modules
                    .map { it.path }
                    .filterNot { it.startsWith(":examples:") }
                    .toSet()

                val scopedFindings = findings.filter { it.module in scopedModules }

                // Determine comparison mode
                val baseSha = project.findProperty("tramaiCancellationBaseSha")?.toString()

                if (baseSha != null) {
                    // ── PR mode: compare against base SHA ──
                    val worktreeDir = java.nio.file.Files.createTempDirectory("tramai-base-${baseSha.take(8)}-").toFile()
                    val baseCatches: List<CancellationCatchFinding>
                    var worktreeCreated = false

                    try {
                        // Create temporary git worktree at base SHA
                        val addProcess = ProcessBuilder(
                            "git", "worktree", "add",
                            worktreeDir.absolutePath, baseSha, "--detach"
                        )
                            .directory(project.rootDir)
                            .redirectErrorStream(true)
                            .start()
                        val addOutput = addProcess.inputStream.bufferedReader().readText()
                        val addExit = addProcess.waitFor()
                        if (addExit != 0) throw GradleException("Failed to create worktree at $baseSha: $addOutput")
                        worktreeCreated = true

                        // Scan base sources with same scanner + scoped module filter
                        val baseCtx = MeasurementContext.fromDirectory(worktreeDir)
                        val baseInventory = CancellationCatchInventory(baseCtx)
                        val baseAllFindings = baseInventory.inventory()
                        baseCatches = baseAllFindings.filter { it.module in scopedModules }
                    } finally {
                        if (worktreeCreated) {
                            ProcessBuilder("git", "worktree", "remove", "--force", worktreeDir.absolutePath)
                                .directory(project.rootDir)
                                .start()
                                .waitFor()
                            ProcessBuilder("git", "worktree", "prune")
                                .directory(project.rootDir)
                                .start()
                                .waitFor()
                        }
                        worktreeDir.deleteRecursively()
                    }

                    // ── Risk population matching comparison ──
                    val delta = CancellationDeltaComparator.compare(baseCatches, scopedFindings)

                    if (delta.newCriticalHigh.isNotEmpty() || delta.worsened.isNotEmpty()) {
                        throw GradleException(delta.diagnostics.joinToString("\n"))
                    }

                    println(delta.diagnostics.joinToString("\n"))
                    println("verifyCancellationSafety PASSED: no new critical/high findings or risk worsenings against base SHA.")
                } else {
                    // ── Local dev mode: auto-resolve merge base against origin/master ──
                    val resolveProcess = ProcessBuilder("git", "merge-base", "HEAD", "origin/master")
                        .directory(project.rootDir)
                        .redirectErrorStream(true)
                        .start()
                    val resolveOutput = resolveProcess.inputStream.bufferedReader().readText().trim()
                    val resolveExit = resolveProcess.waitFor()
                    if (resolveExit != 0) {
                        throw GradleException(
                            "verifyCancellationSafety requires -PtramaiCancellationBaseSha when origin/master is not available.\n" +
                            "Usage: ./gradlew verifyCancellationSafety -PtramaiCancellationBaseSha=<sha>\n" +
                            "In CI this is auto-wired. Locally, use the base branch SHA."
                        )
                    }

                    val baseShaLocal = resolveOutput
                    println("verifyCancellationSafety: auto-resolved merge base against origin/master = ${baseShaLocal.take(8)}")

                    // Scan merge base using the same worktree approach
                    val worktreeDir = java.nio.file.Files.createTempDirectory("tramai-base-${baseShaLocal.take(8)}-").toFile()
                    val localBaseCatches: List<CancellationCatchFinding>
                    var worktreeCreated = false

                    try {
                        val addProcess = ProcessBuilder(
                            "git", "worktree", "add",
                            worktreeDir.absolutePath, baseShaLocal, "--detach"
                        )
                            .directory(project.rootDir)
                            .redirectErrorStream(true)
                            .start()
                        val addOutput = addProcess.inputStream.bufferedReader().readText()
                        val addExit = addProcess.waitFor()
                        if (addExit != 0) throw GradleException("Failed to create worktree at $baseShaLocal: $addOutput")
                        worktreeCreated = true

                        val baseCtx = MeasurementContext.fromDirectory(worktreeDir)
                        val baseInventory = CancellationCatchInventory(baseCtx)
                        val baseAllFindings = baseInventory.inventory()
                        localBaseCatches = baseAllFindings.filter { it.module in scopedModules }
                    } finally {
                        if (worktreeCreated) {
                            ProcessBuilder("git", "worktree", "remove", "--force", worktreeDir.absolutePath)
                                .directory(project.rootDir)
                                .start()
                                .waitFor()
                            ProcessBuilder("git", "worktree", "prune")
                                .directory(project.rootDir)
                                .start()
                                .waitFor()
                        }
                        worktreeDir.deleteRecursively()
                    }

                    // Same risk population matching comparison
                    val delta = CancellationDeltaComparator.compare(localBaseCatches, scopedFindings)

                    if (delta.newCriticalHigh.isNotEmpty() || delta.worsened.isNotEmpty()) {
                        throw GradleException(delta.diagnostics.joinToString("\n"))
                    }

                    println(delta.diagnostics.joinToString("\n"))
                    println("verifyCancellationSafety PASSED: ${scopedFindings.size} findings in scoped modules, no new critical/high findings or risk worsenings against origin/master.")
                }
            }
        }

        project.tasks.register("verifyCriticalCoverage") {
            group = "maintainability"
            description = "Compares current critical-module coverage with the committed baseline"
            dependsOn("generateCoverageBaseline")
            doLast {
                val committed = readCommittedBaseline(project)
                val current = ReportNormalizer.readJson(
                    File(reportDir, "coverage-summary.json"),
                    CoverageData::class.java
                )
                verifyTestQualityDiagnostics(
                    project,
                    "Critical coverage",
                    CoverageBaselineVerifier(testQualityConfiguration)
                        .verify(committed.testQuality.coverage, current)
                )
            }
        }

        project.tasks.register("verifyCriticalMutationBaseline") {
            group = "maintainability"
            description = "Compares current critical mutation results with the committed baseline"
            dependsOn("generateCriticalMutationBaseline")
            doLast {
                val committed = readCommittedBaseline(project)
                val current = ReportNormalizer.readJson(
                    File(reportDir, "mutation-summary.json"),
                    MutationData::class.java
                )
                verifyTestQualityDiagnostics(
                    project,
                    "Critical mutation",
                    MutationBaselineVerifier(testQualityConfiguration, project.rootDir)
                        .verify(committed.testQuality.mutation, current)
                )
            }
        }

        project.tasks.register("verifyTestPerformanceBaseline") {
            group = "maintainability"
            description = "Compares current median test timing with the committed baseline"
            dependsOn("generateTestPerformanceBaseline")
            doLast {
                val committed = readCommittedBaseline(project)
                val current = ReportNormalizer.readJson(
                    File(reportDir, "test-performance/median.json"),
                    TestPerformanceData::class.java
                )
                verifyTestQualityDiagnostics(
                    project,
                    "Test performance",
                    TestPerformanceVerifier(testQualityConfiguration)
                        .verify(committed.testQuality.testPerformance, current)
                )
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
                val outputDirProp = project.findProperty("maintainability.outputDir")?.toString()
                val probeOutputDir = if (outputDirProp != null) {
                    project.rootDir.resolve(outputDirProp).also { it.mkdirs() }
                } else null
                val canonicalProbe = sourceRootFile?.let {
                    CanonicalGradleProbe(
                        sourceRoot = it,
                        outputDir = probeOutputDir,
                        analyzerRoot = project.rootDir
                    )
                }
                val apiOverride: ApiBaseline? = if (sourceRootFile != null) {
                    val result = canonicalProbe!!.probeApiBaseline()
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
                    val result = canonicalProbe!!.probeDependencyBaseline()
                    project.logger.lifecycle("Canonical dependency probe: ${result.records.size} records")
                    if (result.records.isEmpty()) {
                        throw GradleException("Canonical dependency probe produced no dependency records. " +
                            "Non-empty dependency baseline is required.")
                    }
                    result.records
                } else null

                val testQualityOverride = canonicalProbe?.probeTestQualityBaseline(
                    testQualityConfiguration
                )
                if (testQualityOverride != null) {
                    project.logger.lifecycle(
                        "Canonical test-quality probe: " +
                            "${testQualityOverride.coverage.criticalModules.size} coverage modules, " +
                            "${testQualityOverride.mutation.totalMutants} mutants, " +
                            "${testQualityOverride.testPerformance.totalTestCount} tests"
                    )
                }

                val baseline = canonicalGenerator.generateCompleteBaseline(
                    apiOverride = apiOverride,
                    dependencyOverride = dependencyOverride,
                    coverageOverride = testQualityOverride?.coverage,
                    mutationOverride = testQualityOverride?.mutation,
                    testPerformanceOverride = testQualityOverride?.testPerformance
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

        project.tasks.register("verifyFullMaintainabilityBaseline") {
            group = "maintainability"
            description = "Full verification including API, dependency, coverage, mutation, and timing"
            dependsOn(
                "verifyMaintainabilityBaseline",
                "verifyCriticalCoverage",
                "verifyCriticalMutationBaseline",
                "verifyTestPerformanceBaseline"
            )
            doLast {
                println("Full maintainability baseline verification complete.")
            }
        }

        // ---- Change Policy Verification ----

        project.tasks.register("verifyChangePolicy", ChangePolicyVerifierTask::class.java) {
            group = "maintainability"
            description = "Enforces change-policy rules: forbidden path combinations and deviation evidence"

            // Override base ref for CI: ./gradlew verifyChangePolicy -PchangePolicyBase=${{ github.event.pull_request.base.sha }}
            val ciBase = project.findProperty("changePolicyBase")?.toString()
            if (ciBase != null) {
                baseRef.set(ciBase)
                deviationBaseRef.set(ciBase)
            } else {
                baseRef.set("origin/master")
                deviationBaseRef.set("origin/master")
            }

            // Override change class: ./gradlew verifyChangePolicy -PchangeClass=baseline-migration
            val declaredClass = project.findProperty("changeClass")?.toString()
            if (declaredClass != null) {
                changeClass.set(declaredClass)
            }
        }

        // ---- Authoritative module manifest ----

        project.tasks.register("verifyModuleManifest") {
            group = "verification"
            description = "Verifies manifest/settings equality and publishing/BOM membership against independent Gradle model signals"
            doLast {
                val catalog = ModuleManifest.catalog(project.rootDir)
                // Independent signal 1: the actual Gradle project model (settings ↔ manifest).
                val actualProjects = project.allprojects
                    .filter { it != project && it.buildFile.exists() }
                    .map { it.path }
                    .toSet()
                // Independent signal 2: the publication set the build actually wires into
                // release tasks. In this build publishing is DERIVED from the manifest, so
                // the check is a regression lock: it fires if the derivation is replaced
                // by a literal list or the two disagree.
                val actualPublished = (project.extensions.extraProperties.properties["tramai.publishableModulePaths"] as? Collection<*>)
                    ?.map { it.toString() }?.toSet().orEmpty()
                // Independent signal 3: the BOM's ACTUAL configured constraint graph, read
                // from Gradle's model of tramai-bom's api configuration (not from a manifest
                // round-trip).
                val bomProject = project.allprojects.firstOrNull { it.name == "tramai-bom" }
                val actualBom = bomProject
                    ?.configurations
                    ?.findByName("api")
                    ?.dependencyConstraints
                    .orEmpty()
                    .mapNotNull { constraint ->
                        val name = constraint.name
                        if (name.startsWith("tramai-") || name.startsWith("examples:")) ":$name" else null
                    }
                    .toSet()
                val diagnostics = ModuleManifestVerifier.verify(
                    catalogModules = catalog.modules,
                    projectPaths = actualProjects,
                    publishedPaths = actualPublished,
                    bomPaths = actualBom,
                )
                if (diagnostics.isNotEmpty()) throw GradleException(diagnostics.joinToString("\n") { "[${it.code}] ${it.message}" })
            }
        }

        project.tasks.register("generateModuleMatrix") {
            group = "documentation"
            description = "Generates the deterministic TramAI module matrix from the manifest"
            doLast {
                val target = File(project.rootDir, "docs/reference/module-matrix.md")
                target.parentFile.mkdirs()
                target.writeText(ModuleManifest.matrix(project.rootDir))
            }
        }

        project.tasks.register("verifyModuleMatrixDrift") {
            group = "verification"
            description = "Fails when docs/reference/module-matrix.md differs from the manifest"
            doLast {
                val target = File(project.rootDir, "docs/reference/module-matrix.md")
                val expected = ModuleManifest.matrix(project.rootDir)
                if (!target.isFile || target.readText() != expected) throw GradleException(
                    "[${DiagnosticCode.GENERATED_DOCUMENT_DRIFT}] Module matrix drift: run ./gradlew generateModuleMatrix"
                )
            }
        }

        val enrollmentTest = project.tasks.register("architectureContractEnrollmentTest", Test::class.java) {
            group = "verification"
            description = "Runs provider and store enrollment architecture contracts"
            val testingProject = project.project(":tramai-testing")
            val testSourceSet = testingProject.extensions
                .getByType(JavaPluginExtension::class.java)
                .sourceSets
                .getByName("test")
            dependsOn(testingProject.tasks.named("testClasses"))
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            useJUnitPlatform()
            filter.includeTestsMatching("dev.tramai.testing.*EnrollmentArchitectureTest")
            ignoreFailures = true
            binaryResultsDirectory.set(
                testingProject.layout.buildDirectory.dir("test-results/architectureContractEnrollmentTest/binary")
            )
            reports.junitXml.outputLocation.set(
                testingProject.layout.buildDirectory.dir("test-results/architectureContractEnrollmentTest")
            )
            reports.html.outputLocation.set(
                testingProject.layout.buildDirectory.dir("reports/tests/architectureContractEnrollmentTest")
            )
        }

        project.tasks.register("verify060Architecture") {
            group = "verification"
            description = "build(quality): add unified 0.6.0 architecture gate"
            dependsOn(*architectureProbeTasks.toTypedArray())
            dependsOn(enrollmentTest)
            doLast {
                val architectureDiagnostics = ArchitectureReportAggregator.checkIds
                    .associateWith { mutableListOf<VerificationDiagnostic>() }
                val context = MeasurementContext.fromProject(project)
                val verificationReport = File(reportDir, "verification-report.json")

                collectEvidence("baseline verification", baselineCheckIds, architectureDiagnostics) {
                    // Read the fail-soft per-project dependency probes. Resolution
                    // failure reaches the gate as typed evidence (the probe tasks
                    // never abort the task graph), so the report is always written.
                    val probeFiles = project.allprojects
                        .filter { it != project && it.buildFile.exists() }
                        .sortedBy { it.path }
                        .map { sub ->
                            File(sub.layout.buildDirectory.get().asFile, "reports/maintainability/architecture-dependencies.json")
                        }
                    val evidence = readDependencyProbeEvidence(probeFiles)
                    if (evidence.failures.isNotEmpty()) {
                        val message = "Dependency evidence unavailable: ${evidence.failures.joinToString("; ")}"
                        baselineCheckIds.forEach { checkId ->
                            architectureDiagnostics.getValue(checkId) += VerificationDiagnostic.failure(
                                DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
                                message,
                            )
                        }
                    } else {
                        val resolvedDependenciesFile = File(reportDir, "resolved-dependencies.json")
                        reportDir.mkdirs()
                        ReportNormalizer.writeJson(
                            BaselineGenerator.sortResolvedDependencies(evidence.resolvedRecords),
                            resolvedDependenciesFile,
                        )
                        BaselineVerifier(BaselineGenerator(context), context, reportDir).verify()
                        routeBaselineDiagnostics(
                            readBaselineDiagnostics(verificationReport),
                            architectureDiagnostics,
                            baselineCheckIds,
                            ::baselineCheckFor,
                        )
                    }
                }

                collectEvidence("module manifest verification", setOf("module-manifest", "publishing-topology"), architectureDiagnostics) {
                    val catalog = ModuleManifest.catalog(project.rootDir)
                    val actualProjects = project.allprojects
                        .filter { it != project && it.buildFile.exists() }
                        .map { it.path }
                        .toSet()
                    val actualPublished = (project.extensions.extraProperties.properties["tramai.publishableModulePaths"] as? Collection<*>)
                        ?.map { it.toString() }?.toSet().orEmpty()
                    val bomProject = project.allprojects.firstOrNull { it.name == "tramai-bom" }
                    val actualBom = bomProject
                        ?.configurations
                        ?.findByName("api")
                        ?.dependencyConstraints
                        .orEmpty()
                        .mapNotNull { constraint ->
                            constraint.name.takeIf { it.startsWith("tramai-") || it.startsWith("examples:") }?.let { ":$it" }
                        }
                        .toSet()
                    addManifestDiagnostics(
                        ModuleManifestVerifier.verify(catalog.modules, actualProjects, actualPublished, actualBom),
                        architectureDiagnostics,
                    )
                }

                collectEvidence("enrollment contract verification", setOf("provider-contracts", "store-contracts"), architectureDiagnostics) {
                    addEnrollmentDiagnostics(
                        File(project.project(":tramai-testing").buildDir, "test-results/architectureContractEnrollmentTest"),
                        architectureDiagnostics,
                    )
                }

                val report = ArchitectureReportAggregator.aggregate(architectureDiagnostics)
                val reportFile = File(project.layout.buildDirectory.get().asFile, "reports/tramai/architecture/architecture-report.json")
                // Report is written BEFORE the terminal exception — failure must produce evidence.
                ArchitectureReportJson.write(report, reportFile, project.rootDir)
                if (report.status == ArchitectureCheckStatus.FAIL) {
                    throw GradleException("0.6.0 architecture verification FAILED: " +
                        report.checks.filter { it.status == ArchitectureCheckStatus.FAIL }.joinToString { it.id } +
                        " — see ${reportFile.path}")
                }
            }
        }

        // ---- PR Verification (primary local check gate) ----

        val verifyPr = project.tasks.register("verifyPr") {
            group = "verification"
            description = "Primary local verification gate. Runs subproject tests, build-logic tests, maintainability baseline, and change policy. Not a full CI replica — see .github/AGENTS.md for additional step commands."

            dependsOn("verifyMaintainabilityBaseline")
            dependsOn("verifyChangePolicy")
            dependsOn("verifyModuleManifest")
            dependsOn("verifyModuleMatrixDrift")

            // Include build-logic tests (included build — must use includedBuild API)
            val buildLogicTestTask = project.gradle.includedBuild("build-logic")?.task(":test")
            if (buildLogicTestTask != null) {
                dependsOn(buildLogicTestTask)
            } else {
                logger.warn("verifyPr: included build 'build-logic' not found, build-logic tests not aggregated")
            }

            doLast {
                logger.lifecycle("verifyPr completed — see individual task results above.")
            }
        }

        // ---- JUnit test-signature integrity (silently-skipped tests guard) ----
        // JUnit Jupiter discards @Test methods whose JVM return type is not void.
        // Kotlin expression-bodied tests ending in a chainable assertion compile
        // to non-void methods and are silently never discovered. This task scans
        // every test source and fails on non-Unit expression-bodied @Test fns.
        project.tasks.register("verifyJUnitTestSignatures") {
            group = "verification"
            description = "Fails if any @Test function uses an expression body whose inferred " +
                "return type is not provably Unit (JUnit silently skips non-void @Test methods)."
            doLast {
                val violations = JUnitTestSignatureVerifier.scan(project.rootDir.toPath())
                if (violations.isNotEmpty()) {
                    throw GradleException(
                        "verifyJUnitTestSignatures: ${violations.size} @Test function(s) with " +
                            "non-Unit expression bodies would be silently skipped by JUnit.\n" +
                            JUnitTestSignatureVerifier.render(violations),
                    )
                }
            }
        }
        verifyPr.configure {
            dependsOn("verifyJUnitTestSignatures")
        }

        // Wire subproject test tasks lazily — use withPlugin so they register
        // after subproject build scripts evaluate, not as an eager snapshot.
        project.subprojects.forEach { subproject ->
            subproject.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
                verifyPr.configure {
                    dependsOn(subproject.tasks.named("test"))
                }
            }
            subproject.pluginManager.withPlugin("java") {
                verifyPr.configure {
                    dependsOn(subproject.tasks.named("test"))
                }
            }
        }
    }

    private fun readCommittedBaseline(project: Project): BaselineDocument {
        val file = File(project.rootDir, "config/quality/0.6.0-baseline.json")
        if (!file.isFile) throw GradleException("Committed baseline not found: ${file.absolutePath}")
        return try {
            ReportNormalizer.readJson(file, BaselineDocument::class.java)
        } catch (e: Exception) {
            throw GradleException("Failed to read committed baseline: ${e.message}", e)
        }
    }

    private fun verifyTestQualityDiagnostics(
        project: Project,
        label: String,
        diagnostics: List<VerificationDiagnostic>
    ) {
        diagnostics.filter { it.severity == DiagnosticSeverity.WARNING }
            .forEach { project.logger.warn("WARN: ${it.message}") }
        val failures = diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }
        failures.forEach { project.logger.error("FAIL: ${it.message}") }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "$label baseline verification FAILED:\n" +
                    failures.joinToString("\n") { "  - ${it.message}" }
            )
        }
        println("$label baseline verification PASSED.")
    }

    private fun runNestedGradle(project: Project, arguments: List<String>) {
        val wrapper = File(
            project.rootDir,
            if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
        )
        val command = mutableListOf<String>()
        if (!wrapper.canExecute() && !wrapper.name.endsWith(".bat")) command += "bash"
        command += wrapper.absolutePath
        command += listOf(
            "--no-daemon",
            "--no-build-cache",
            "--no-configuration-cache",
            "--no-parallel",
            "--console=plain"
        )
        command += arguments
        val process = ProcessBuilder(command)
            .directory(project.rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException(
                "Nested Gradle execution failed with exit code $exitCode:\n$output"
            )
        }
        project.logger.lifecycle(output.trimEnd())
    }

    private fun copyTestReports(
        repositoryRoot: File,
        criticalModules: Set<String>,
        runRoot: File
    ) {
        criticalModules.sorted().forEach { module ->
            val modulePath = module.removePrefix(":").replace(":", "/")
            val sourceDir = File(repositoryRoot, "$modulePath/build/test-results/test")
            val destinationDir = File(runRoot, modulePath)
            val reports = sourceDir.listFiles { file ->
                file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
            }?.sortedBy { it.name }.orEmpty()
            if (reports.isEmpty()) {
                throw GradleException(
                    "Missing expected test report for $module at ${sourceDir.absolutePath}"
                )
            }
            destinationDir.mkdirs()
            reports.forEach { it.copyTo(File(destinationDir, it.name), overwrite = true) }
        }
    }

    private fun mutationInitScript(
        configuration: TestQualityConfiguration,
        reportRoot: File
    ): String {
        val familyModules = configuration.mutation.targetFamilies.entries
            .sortedBy { it.key }
            .joinToString(",\n") { (family, target) ->
                val modules = target.modules.sorted().joinToString(", ") {
                    "'${groovyString(it)}'"
                }
                val classes = target.targetClasses.sorted().joinToString(", ") {
                    "'${groovyString(it)}'"
                }
                val tests = target.targetTests.sorted().joinToString(", ") {
                    "'${groovyString(it)}'"
                }
                "    '${groovyString(family)}': [modules: [$modules], targetClasses: [$classes], targetTests: [$tests]]"
            }
        return """
            initscript {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
                dependencies {
                    classpath 'info.solidsoft.gradle.pitest:gradle-pitest-plugin:1.19.0'
                }
            }

            def targetFamilities = [
            $familyModules
            ]
            def selectedFamily = gradle.startParameter.projectProperties['tramaiMutationFamily']
            if (selectedFamily == null || !targetFamilities.containsKey(selectedFamily)) {
                throw new GradleException("Unknown or missing tramaiMutationFamily: " + selectedFamily)
            }
            def familyConfig = targetFamilities[selectedFamily]
            def selectedModules = familyConfig.modules as Set
            def familyTargetClasses = familyConfig.targetClasses as Set
            def familyTargetTests = familyConfig.targetTests as Set
            def mutationTasks = []
            def outputRoot = new File('${groovyString(reportRoot.absolutePath)}')

            gradle.beforeProject { measuredProject ->
                if (!(measuredProject.path in selectedModules)) return
                def pluginClass = initscript.classLoader.loadClass(
                    'info.solidsoft.gradle.pitest.PitestPlugin'
                )
                measuredProject.pluginManager.apply(pluginClass)
                measuredProject.extensions.configure('pitest') {
                    targetClasses.set(familyTargetClasses)
                    targetTests.set(familyTargetTests)
                    outputFormats.set(['XML', 'HTML'] as Set)
                    timestampedReports.set(false)
                    failWhenNoMutations.set(true)
                    threads.set(2)
                    def moduleSlug = measuredProject.path.substring(1).replace(':', '_')
                    reportDir.set(new File(outputRoot, selectedFamily + '/' + moduleSlug))
                }
                mutationTasks << measuredProject.tasks.named('pitest')
            }

            gradle.projectsEvaluated {
                rootProject.tasks.register('canonicalMutationProbe') {
                    dependsOn mutationTasks.collect { it.get() }
                }
            }
        """.trimIndent() + "\n"
    }

    private fun groovyString(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private fun readBaselineDiagnostics(reportFile: File): List<VerificationDiagnostic> {
        if (!reportFile.isFile) {
            throw GradleException("Baseline verifier did not produce ${reportFile.path}")
        }
        val report = ReportNormalizer.readJson(reportFile, Map::class.java)
        val diagnostics = report["diagnostics"] as? List<*>
            ?: throw GradleException("Baseline verification report has no diagnostics array")
        return diagnostics.map { raw ->
            val entry = raw as? Map<*, *> ?: throw GradleException("Malformed baseline diagnostic")
            VerificationDiagnostic(
                code = DiagnosticCode.valueOf(entry["code"]?.toString() ?: error("Baseline diagnostic code missing")),
                severity = DiagnosticSeverity.valueOf(entry["severity"]?.toString() ?: error("Baseline diagnostic severity missing")),
                message = entry["message"]?.toString() ?: error("Baseline diagnostic message missing"),
                modulePath = entry["modulePath"]?.toString(),
                findingId = entry["findingId"]?.toString(),
                deviationId = entry["deviationId"]?.toString(),
                baselineValue = entry["baselineValue"]?.toString(),
                currentValue = entry["currentValue"]?.toString(),
            )
        }
    }

    /**
     * Exhaustive classification of every DiagnosticCode. No else branch: adding a
     * new DiagnosticCode forces a decision at compile time — either it belongs to
     * an architecture check here, or it is explicitly excluded.
     */
    private fun baselineCheckFor(code: DiagnosticCode): String? = when (code) {
        // Module catalogue (all codes except BOM/publishing drift, which are publishing-topology)
        DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
        DiagnosticCode.MODULE_CATALOG_UNKNOWN_ENTRY,
        DiagnosticCode.MODULE_CATALOG_DUPLICATE_PATH,
        DiagnosticCode.MODULE_CATALOG_INVALID_LAYER,
        DiagnosticCode.MODULE_CATALOG_MISSING_API_STABILITY,
        DiagnosticCode.MODULE_CATALOG_EXAMPLE_PUBLISHABLE,
        DiagnosticCode.MODULE_CATALOG_DISAGREEMENT,
        DiagnosticCode.MODULE_CATALOG_INVALID_SCHEMA,
        DiagnosticCode.MODULE_CATALOG_INVALID_MATURITY,
        DiagnosticCode.MODULE_CATALOG_INVALID_PUBLISHABILITY,
        DiagnosticCode.MODULE_CATALOG_INVALID_VISIBILITY,
        DiagnosticCode.MODULE_CATALOG_INVALID_RELEASE_INCLUSION,
        DiagnosticCode.MODULE_CATALOG_INVALID_POLICY,
        DiagnosticCode.MODULE_CATALOG_BLANK_OWNER,
        DiagnosticCode.MODULE_CATALOG_BLANK_RATIONALE,
        DiagnosticCode.MODULE_CATALOG_INVALID_COMBINATION,
        -> "module-manifest"

        DiagnosticCode.MODULE_CATALOG_BOM_DRIFT,
        DiagnosticCode.MODULE_CATALOG_PUBLISHING_DRIFT,
        -> "publishing-topology"

        DiagnosticCode.FORBIDDEN_LAYER_EDGE,
        DiagnosticCode.SELF_DEPENDENCY,
        -> "dependency-boundaries"

        DiagnosticCode.NEW_DEPENDENCY_CYCLE,
        -> "dependency-cycles"

        DiagnosticCode.NEW_GLOBAL_STATE_FINDING,
        -> "global-state"

        DiagnosticCode.API_BASELINE_EMPTY,
        DiagnosticCode.API_DUMP_MISSING,
        DiagnosticCode.API_DUMP_DUPLICATE,
        DiagnosticCode.API_MODULE_UNCLASSIFIED,
        DiagnosticCode.API_VALIDATION_NOT_CONFIGURED,
        DiagnosticCode.API_COMPATIBILITY_FAILED,
        DiagnosticCode.API_HASH_CHANGED,
        DiagnosticCode.API_DUMP_NONDETERMINISTIC,
        -> "api-architecture"

        DiagnosticCode.STABLE_PROTOCOL_CONTRACT_REMOVED,
        -> "protocol-catalog"

        DiagnosticCode.NEW_CANCELLATION_FINDING,
        DiagnosticCode.CANCELLATION_RISK_WORSENED,
        -> "cancellation-safety"

        // Explicitly outside the 0.6.0 architecture gate.
        DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
        DiagnosticCode.ANALYZER_COMMIT_NOT_ANCESTOR,
        DiagnosticCode.MEASURED_TREE_MISMATCH,
        DiagnosticCode.TAG_COMMIT_MISMATCH,
        DiagnosticCode.TAG_TREE_MISMATCH,
        DiagnosticCode.DIRTY_WORKTREE,
        DiagnosticCode.DEPENDENCY_BASELINE_EMPTY,
        DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
        DiagnosticCode.DYNAMIC_DEPENDENCY_VERSION,
        DiagnosticCode.SNAPSHOT_DEPENDENCY,
        DiagnosticCode.DEPENDENCY_CONVERGENCE_FAILURE,
        DiagnosticCode.DEPENDENCY_ADDED,
        DiagnosticCode.DEPENDENCY_REMOVED,
        DiagnosticCode.DEPENDENCY_VERSION_CHANGED,
        DiagnosticCode.TEST_QUALITY_CONFIGURATION_INVALID,
        DiagnosticCode.COVERAGE_REPORT_MISSING,
        DiagnosticCode.COVERAGE_REPORT_MALFORMED,
        DiagnosticCode.COVERAGE_COUNTER_MISSING,
        DiagnosticCode.COVERAGE_PATH_LEAK,
        DiagnosticCode.COVERAGE_REGRESSION,
        DiagnosticCode.COVERAGE_FAMILY_EMPTY,
        DiagnosticCode.COVERAGE_EXCLUSION_UNDOCUMENTED,
        DiagnosticCode.MUTATION_REPORT_MISSING,
        DiagnosticCode.MUTATION_REPORT_MALFORMED,
        DiagnosticCode.MUTATION_TARGET_EMPTY,
        DiagnosticCode.MUTATION_REGRESSION,
        DiagnosticCode.MUTATION_SURVIVOR_UNCLASSIFIED,
        DiagnosticCode.MUTATION_MISSING_TEST_UNTRACKED,
        DiagnosticCode.TEST_REPORT_MISSING,
        DiagnosticCode.TEST_PERFORMANCE_REGRESSION,
        DiagnosticCode.CRITICAL_TEST_REGRESSION,
        DiagnosticCode.CRITICAL_TEST_NEWLY_SKIPPED,
        DiagnosticCode.TEST_QUALITY_STATUS_PENDING,
        DiagnosticCode.NEW_NONDETERMINISM_FINDING,
        DiagnosticCode.HOTSPOT_REGRESSION,
        DiagnosticCode.NEW_TOP_FIVE_HOTSPOT,
        DiagnosticCode.FILE_GROWTH_EXCEEDED,
        DiagnosticCode.INVALID_DEVIATION_SCOPE,
        DiagnosticCode.ORPHANED_DEVIATION,
        DiagnosticCode.EXPIRED_DEVIATION,
        DiagnosticCode.DUPLICATE_DEVIATION,
        DiagnosticCode.MALFORMED_DEVIATION,
        DiagnosticCode.DEVIATION_BASELINE_MISMATCH,
        DiagnosticCode.DEVIATION_COVERAGE_EXCEEDED,
        DiagnosticCode.GENERATED_DOCUMENT_DRIFT,
        DiagnosticCode.EMPTY_SECTION,
        -> null
    }

    private fun addManifestDiagnostics(
        diagnostics: List<VerificationDiagnostic>,
        checks: Map<String, MutableList<VerificationDiagnostic>>,
    ) {
        diagnostics.forEach { diagnostic ->
            val check = if (diagnostic.code in publishingTopologyCodes) "publishing-topology" else "module-manifest"
            checks.getValue(check) += diagnostic
        }
    }

    private fun addEnrollmentDiagnostics(
        resultsDir: File,
        checks: Map<String, MutableList<VerificationDiagnostic>>,
    ) {
        val reports = resultsDir.listFiles { file -> file.isFile && file.name.startsWith("TEST-") && file.extension == "xml" }
            ?.sortedBy { it.name }
            .orEmpty()
        if (reports.isEmpty()) {
            listOf("provider-contracts", "store-contracts").forEach { check ->
                checks.getValue(check) += VerificationDiagnostic.failure(
                    DiagnosticCode.EMPTY_SECTION,
                    "Enrollment architecture test results are missing from ${resultsDir.path}",
                )
            }
            return
        }

        val discoveredClasses = mutableSetOf<String>()
        reports.forEach { report ->
            val className = report.name.removePrefix("TEST-").removeSuffix(".xml")
            discoveredClasses += className
            val check = when {
                className == "dev.tramai.testing.ProviderTckEnrollmentArchitectureTest" -> "provider-contracts"
                className.endsWith("EnrollmentArchitectureTest") -> "store-contracts"
                else -> null
            } ?: return@forEach
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
                setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = factory.newDocumentBuilder().parse(report)
            val cases = document.getElementsByTagName("testcase")
            for (index in 0 until cases.length) {
                val testCase = cases.item(index) as org.w3c.dom.Element
                for (childIndex in 0 until testCase.childNodes.length) {
                    val child = testCase.childNodes.item(childIndex)
                    if (child.nodeName !in setOf("failure", "error")) continue
                    val failure = child as org.w3c.dom.Element
                    val message = failure.getAttribute("message").ifBlank { failure.textContent.trim() }
                    checks.getValue(check) += VerificationDiagnostic.failure(
                        DiagnosticCode.EMPTY_SECTION,
                        "$className failed: $message",
                    )
                }
            }
        }

        // Pin guard identities: deleting/renaming one enrollment class must FAIL
        // even if the others still run. Discovery by identity, not by count.
        enrollmentGuardDiagnostics(discoveredClasses).forEach { (check, diagnostics) ->
            checks.getValue(check) += diagnostics
        }
    }

    private companion object {
        val publishingTopologyCodes = setOf(
            DiagnosticCode.MODULE_CATALOG_BOM_DRIFT,
            DiagnosticCode.MODULE_CATALOG_PUBLISHING_DRIFT,
        )
        val baselineCheckIds = setOf(
            "module-manifest",
            "dependency-boundaries",
            "dependency-cycles",
            "global-state",
            "api-architecture",
            "protocol-catalog",
            "cancellation-safety",
        )
    }
}
