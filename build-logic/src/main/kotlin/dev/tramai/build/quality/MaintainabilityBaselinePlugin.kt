package dev.tramai.build.quality

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.Action
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.tasks.JacocoReport
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

        // ---- PR Verification (primary local check gate) ----

        project.tasks.register("verifyPr") {
            group = "verification"
            description = "Primary local verification gate. Runs subproject tests, build-logic tests, maintainability baseline, and change policy. Not a full CI replica — see .github/AGENTS.md for additional step commands."

            // Aggregate all subproject test tasks
            val subprojectTestTasks = project.subprojects.flatMap { sub ->
                sub.tasks.matching { it.name == "test" }.toList()
            }
            dependsOn(subprojectTestTasks)
            dependsOn("verifyMaintainabilityBaseline")
            dependsOn("verifyChangePolicy")

            // Include build-logic tests (included build)
            dependsOn(":build-logic:test")

            doLast {
                logger.lifecycle("verifyPr completed — see individual task results above.")
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
}
