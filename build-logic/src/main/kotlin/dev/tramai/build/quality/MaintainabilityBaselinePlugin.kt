package dev.tramai.build.quality

import dev.tramai.build.release.VerifyPublicationMetadataTask
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.register
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
    /** Root project this plugin was applied to (set in apply()). */
    private lateinit var rootProject: Project

    /** Fail-soft consumer compile-proof producers (Epic 10.2); markers feed the
     * architecture gate as typed api-architecture evidence (a3c3). */
    private val consumerMarkerProviders = mutableListOf<TaskProvider<ConsumerSmokeCompileTask>>()

    override fun apply(project: Project) {
        rootProject = project
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
        val reportDir =
            File(
                project.layout.buildDirectory
                    .get()
                    .asFile,
                "reports/maintainability",
            )

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
                        val mainSourceSet =
                            criticalProject.extensions
                                .getByType(
                                    org.gradle.api.plugins.JavaPluginExtension::class.java,
                                ).sourceSets
                                .getByName("main")
                        classDirectories.from(
                            mainSourceSet.output.classesDirs.files.map { root ->
                                criticalProject.fileTree(root) {
                                    exclude(excludedPatterns)
                                }
                            },
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
                println(
                    "Public API baseline: ${apiBaseline.modules.size} modules, " +
                        "${apiBaseline.modules.count { it.applicable }} applicable, " +
                        "${apiBaseline.modules.count { it.sha256.isNotBlank() }} with dumps",
                )
            }
        }

        // Register per-project dependency probe tasks (Gradle 9: each task owns its config)
        val perProjectProbeTasks = mutableListOf<String>()
        val dependencyProbeTaskName = "generateResolvedDependencyBaseline"
        project.allprojects.filter { it != project && it.buildFile.exists() }.forEach { sub ->
            val probe = sub.tasks.register(dependencyProbeTaskName, GenerateResolvedDependencyBaselineTask::class.java)
            probe.configure {
                group = "maintainability"
                description = "Resolves external dependencies for ${sub.path}"
                outputFile.set(
                    sub.layout.buildDirectory.file("reports/maintainability/resolved-dependencies.json"),
                )
                val probePath = sub.path
                consumerPath.set(probePath)
                resolution.set(
                    sub.provider {
                        val probeConfigs =
                            listOf("compileClasspath", "runtimeClasspath")
                                .mapNotNull { name -> sub.configurations.findByName(name) }
                        runCatching { collectResolvedDependencies(probePath, probeConfigs) }
                            .fold(
                                onSuccess = { DependencyResolutionResult(it) },
                                onFailure = { e ->
                                    DependencyResolutionResult(
                                        records = emptyList(),
                                        failureMessage = e.message ?: e.javaClass.name,
                                    )
                                },
                            )
                    },
                )
            }
            perProjectProbeTasks.add("${sub.path}:$dependencyProbeTaskName")
        }

        // Root aggregation task depends on all per-project probes and merges their outputs.
        // Typed (Epic 9.2d-a3c1): probeFiles + expectedProbeOwners are paired,
        // index-aligned inputs built in the same deterministic (sorted) order.
        val expectedAggregateProjects =
            project.allprojects
                .filter { it != project && it.buildFile.exists() }
                .sortedBy { it.path }
        project.tasks.register<AggregateResolvedDependencyBaselineTask>("generateResolvedDependencyBaseline") {
            group = "maintainability"
            description = "Aggregates per-project resolved dependency baselines"
            dependsOn(*perProjectProbeTasks.toTypedArray())
            aggregateFile.set(project.layout.buildDirectory.file("reports/maintainability/resolved-dependencies.json"))
            expectedAggregateProjects.forEach { sub ->
                probeFiles.from(
                    sub.tasks
                        .named(dependencyProbeTaskName, GenerateResolvedDependencyBaselineTask::class.java)
                        .flatMap { it.outputFile },
                )
                expectedProbeOwners.add(sub.path)
            }
        }

        // Fail-soft per-project dependency probes for the architecture gate.
        // Unlike the tasks above they never throw on unresolved dependencies;
        // the gate reads their outputs and converts resolution failure into
        // typed fail-closed evidence, so the report is always written.
        val architectureProbeTasks = mutableListOf<String>()
        val architectureProbeProviders = mutableListOf<TaskProvider<ArchitectureDependencyProbeTask>>()
        project.allprojects.filter { it != project && it.buildFile.exists() }.forEach { sub ->
            val probe = sub.tasks.register("architectureDependencyProbe", ArchitectureDependencyProbeTask::class.java)
            probe.configure {
                group = "verification"
                description = "Resolves external dependencies for ${sub.path} (fail-soft, architecture gate)"
                outputFile.set(
                    sub.layout.buildDirectory.file("reports/maintainability/architecture-dependencies.json"),
                )
                val probePath = sub.path
                consumerPath.set(probePath)
                resolution.set(
                    sub.provider {
                        val probeConfigs =
                            listOf("compileClasspath", "runtimeClasspath")
                                .mapNotNull { name -> sub.configurations.findByName(name) }
                        runCatching { collectResolvedDependencies(probePath, probeConfigs) }
                            .fold(
                                onSuccess = { DependencyResolutionResult(it) },
                                onFailure = { e ->
                                    DependencyResolutionResult(
                                        records = emptyList(),
                                        failureMessage = e.message ?: e.javaClass.name,
                                    )
                                },
                            )
                    },
                )
            }
            architectureProbeTasks.add("${sub.path}:architectureDependencyProbe")
            architectureProbeProviders.add(probe)
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
                val outDir =
                    File(
                        project.layout.buildDirectory
                            .get()
                            .asFile,
                        "reports/maintainability",
                    )
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
                val outDir =
                    File(
                        project.layout.buildDirectory
                            .get()
                            .asFile,
                        "reports/maintainability",
                    )
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
                val outDir =
                    File(
                        project.layout.buildDirectory
                            .get()
                            .asFile,
                        "reports/maintainability",
                    )
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
                val file =
                    project.layout.buildDirectory
                        .file("reports/maintainability/resolved-dependencies.json")
                        .get()
                        .asFile
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
                val coverage =
                    CoverageCollector(
                        project.rootDir,
                        testQualityConfiguration,
                    ).collect()
                reportDir.mkdirs()
                ReportNormalizer.writeJson(coverage, File(reportDir, "coverage-summary.json"))
                println(
                    "Critical coverage baseline: ${coverage.criticalModules.size} modules, " +
                        "${"%.2f".format(coverage.overallLineCoverage)}% lines, " +
                        "${"%.2f".format(coverage.overallBranchCoverage)}% branches",
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
                    Charsets.UTF_8,
                )
                testQualityConfiguration.mutation.targetFamilies.keys.sorted().forEach { family ->
                    runNestedGradle(
                        project,
                        listOf(
                            "--init-script",
                            initScript.absolutePath,
                            "-PtramaiMutationFamily=$family",
                            "canonicalMutationProbe",
                        ),
                    )
                }
                val mutation =
                    generator.generateMutationBaseline(
                        testQualityConfiguration,
                        mutationRoot,
                    )
                ReportNormalizer.writeJson(mutation, File(reportDir, "mutation-summary.json"))
                println(
                    "Critical mutation baseline: ${mutation.totalMutants} mutants, " +
                        "${"%.2f".format(mutation.mutationScore)}% killed",
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
                val observations =
                    (1..3).flatMap { run ->
                        runNestedGradle(project, listOf("--rerun-tasks") + testTaskPaths)
                        copyTestReports(project.rootDir, criticalModules, File(runsRoot, run.toString()))
                        collector.collectMeasuredRun(
                            run = run,
                            gradleVersion = project.gradle.gradleVersion,
                            reportRoot = runsRoot,
                        )
                    }
                val performance = TestPerformanceAggregator().aggregate(observations)
                ReportNormalizer.writeJson(
                    observations,
                    File(outputRoot, "observations.json"),
                )
                ReportNormalizer.writeJson(performance, File(outputRoot, "median.json"))
                println(
                    "Test performance baseline: ${performance.totalTestCount} tests, " +
                        "${performance.totalDurationMs}ms aggregate median",
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
                "generateRuntimeProtocolCatalog",
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
                "generateCriticalMutationBaseline",
            )
            doLast {
                val baselineFile = File(project.rootDir, "config/quality/0.6.0-baseline.json")
                val generated = generator.generateFullBaseline()
                val structuralBaseline =
                    ReportNormalizer.readJson(
                        baselineFile,
                        BaselineDocument::class.java,
                    )
                val coverage =
                    ReportNormalizer.readJson(
                        File(reportDir, "coverage-summary.json"),
                        CoverageData::class.java,
                    )
                val mutation =
                    ReportNormalizer.readJson(
                        File(reportDir, "mutation-summary.json"),
                        MutationData::class.java,
                    )
                val performance =
                    ReportNormalizer.readJson(
                        File(reportDir, "test-performance/median.json"),
                        TestPerformanceData::class.java,
                    )
                generator.updateBaselineJson(
                    structuralBaseline.copy(
                        baselineIdentity = generated.baselineIdentity,
                        testQuality = TestQualityBaseline(performance, coverage, mutation),
                        generatedAt = generated.generatedAt,
                        generatedBy = "generateFullMaintainabilityBaseline",
                        environment = generated.environment,
                    ),
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

        // ---- Maintainability baseline verification (Epic 9.2d-a3c2) ----
        // apiCheck module set snapshotted once configuration has finished
        // (a3c1 discipline: model inspected while configuring task
        // properties, never lazily inside the task action — a lazy
        // provider would capture Project and walk the model at execution).
        // Registered at apply scope, NOT inside the register action: register
        // actions run at task realization (post-evaluation), where
        // projectsEvaluated is illegal; configureEach applies whenever the
        // task is realized.
        project.gradle.projectsEvaluated {
            val apiModules =
                project.allprojects
                    .filter { it.tasks.findByName("apiCheck") != null }
                    .map { it.path }
                    .toList()
            // a3c2 round 2: capture the project dependency model as a declared
            // plain-value snapshot at configuration time. Directory mode cannot
            // rediscover Gradle edges; the snapshot is the execution authority
            // for cycle / forbidden-edge / dependency-policy enforcement.
            val graphCtx = MeasurementContext.fromProject(project)
            val graph = ModuleGraphAnalyzer(graphCtx).analyze()
            project.tasks.withType(VerifyMaintainabilityBaselineTask::class.java).configureEach {
                apiValidationModules.set(apiModules)
                dependencyGraph.set(
                    DependencyGraphSnapshot(
                        production = graph.moduleDependencies,
                        test = graph.moduleDependenciesTest,
                    ),
                )
            }
            // a3c3: same configuration-time snapshots for the architecture gate
            // (declared inputs are the execution authority — no Project in the action).
            project.tasks.withType(VerifyArchitectureTask::class.java).configureEach {
                apiValidationModules.set(apiModules)
                dependencyGraph.set(
                    DependencyGraphSnapshot(
                        production = graph.moduleDependencies,
                        test = graph.moduleDependenciesTest,
                    ),
                )
                actualProjectPaths.set(
                    project.allprojects
                        .filter { it != project && it.buildFile.exists() }
                        .map { it.path },
                )
                publishedModulePaths.set(
                    ModuleManifest.publishableModulePaths(project.rootDir),
                )
                val bomProject = project.allprojects.firstOrNull { it.name == "tramai-bom" }
                bomModulePaths.set(
                    bomProject
                        ?.configurations
                        ?.findByName("api")
                        ?.dependencyConstraints
                        .orEmpty()
                        .mapNotNull { constraint ->
                            constraint.name.takeIf { it.startsWith("tramai-") || it.startsWith("examples:") }?.let { ":$it" }
                        },
                )
                enrollmentResultsDir.from(
                    project.tasks
                        .named("architectureContractEnrollmentTest", Test::class.java)
                        .map {
                            it.reports.junitXml.outputLocation
                                .get()
                        },
                )
                // Generated BCV dumps — typed apiBuild task outputs, paired
                // with their module paths so the gate consumes the EXACT
                // declared files (a3c3 P1: no conventional build/api rediscovery).
                project.allprojects
                    .filter { sub -> sub != project && sub.tasks.findByName("apiBuild") != null }
                    .filter { sub -> ApiCompatibilityEvidenceReader.committedDumpPath(sub.projectDir, sub.name).isFile }
                    .forEach { sub ->
                        generatedApiDumpOwners.add(sub.path)
                        generatedApiDumpFiles.from(sub.tasks.named("apiBuild").map { it.outputs.files })
                    }
            }
        }

        // Typed, configuration-cache-safe: declared inputs (committed baseline,
        // deviations, catalog, boundaries, aggregate resolved-dependency output,
        // measured source tree, apiCheck snapshot) are the execution authority.
        // The action builds a directory-mode MeasurementContext from the
        // declared catalog file — never Task.project.
        project.tasks.register<VerifyMaintainabilityBaselineTask>("verifyMaintainabilityBaseline") {
            group = "maintainability"
            description = "Compares current measurements against committed baseline and rejects regressions"
            dependsOn("generateResolvedDependencyBaseline")
            dependsOn("verifyRuntimeNondeterminism")
            // Only declare the committed baseline when it exists; an unset
            // property keeps @Optional semantics so the action's fail-closed
            // "Committed baseline not found" diagnostic runs (Gradle 9
            // validates set-but-missing @InputFile eagerly).
            val committedBaseline = project.layout.projectDirectory.file("config/quality/0.6.0-baseline.json")
            if (committedBaseline.asFile.isFile) {
                committedBaselineFile.set(committedBaseline)
            } else {
                committedBaselineFile.unset()
            }
            deviationsFile.set(project.layout.projectDirectory.file("config/quality/maintainability-deviations.yml"))
            moduleCatalogFile.set(project.layout.projectDirectory.file("config/quality/module-catalog.yml"))
            moduleBoundariesFile.set(project.layout.projectDirectory.file("config/quality/module-boundaries.yml"))
            settingsFile.set(project.layout.projectDirectory.file("settings.gradle.kts"))
            this.reportDir.set(project.layout.buildDirectory.dir("reports/maintainability"))
            // Typed signal from the a3c1 aggregate task — never a conventional path.
            resolvedDependenciesFile.set(
                project.tasks
                    .named("generateResolvedDependencyBaseline", AggregateResolvedDependencyBaselineTask::class.java)
                    .flatMap { it.aggregateFile },
            )
            // Measured tree: module sources/build files, settings, version
            // properties, root build script + version catalog, committed api
            // dumps, release notes. Declared so a source change invalidates the
            // gate (never a stale CC PASS). Candidates are declared WITHOUT an
            // existence filter: @InputFiles fingerprints absent paths, so a
            // source directory created later still invalidates the gate.
            val measuredDirs =
                project.allprojects
                    .filter { it != project && it.buildFile.exists() }
                    .flatMap { sub ->
                        listOf(
                            File(sub.projectDir, "src/main/kotlin"),
                            File(sub.projectDir, "src/main/java"),
                            File(sub.projectDir, "src/test/kotlin"),
                            File(sub.projectDir, "src/testFixtures/kotlin"),
                            sub.buildFile,
                        )
                    }
            sourceTree.from(project.files(measuredDirs))
            sourceTree.from(
                project.files(
                    project.rootDir.resolve("settings.gradle.kts"),
                    project.rootDir.resolve("gradle.properties"),
                    project.rootDir.resolve("build.gradle.kts"),
                    project.rootDir.resolve("gradle/libs.versions.toml"),
                ),
            )
            sourceTree.from(
                project.fileTree(project.rootDir) {
                    include("**/api/*.api", "docs/releases/0.6.0-maintainability-baseline.md")
                },
            )
        }

        // ── Epic 8.3d PR 2: nondeterminism authority contract verifier ──
        // Fail-closed semantic allowlist gate. verifyMaintainabilityBaseline
        // depends on it, so every CI path that runs the maintainability gate
        // also enforces the nondeterminism authority contract.
        //
        // Source inputs: every module's src/main/kotlin + src/main/java are
        // declared as stable file trees (kt + java includes) REGARDLESS of
        // whether the root exists today. A source root created later changes
        // the input tree and forces a rerun — the declared input universe and
        // the scanner's execution universe are the same set.
        val nonDetCtx = MeasurementContext.fromProject(project)
        val nonDetSourceFiles = project.objects.fileCollection()
        val nonDetScanSpec =
            nonDetCtx.modules.map { mod ->
                listOf("src/main/kotlin", "src/main/java").forEach { rel ->
                    val dir = File(mod.projectDir, rel)
                    nonDetSourceFiles.from(
                        project.fileTree(mapOf("dir" to dir, "include" to listOf("**/*.kt", "**/*.java"))),
                    )
                }
                mapOf("name" to mod.name, "dir" to mod.projectDir.relativeTo(project.rootDir).path)
            }
        project.tasks.register("verifyRuntimeNondeterminism", VerifyRuntimeNondeterminismTask::class.java) {
            group = "maintainability"
            description =
                "Fails on unclassified, stale, mismatched, or occurrence-drifted entries in config/quality/runtime-nondeterminism.yml"
            allowlistFile.set(project.rootDir.resolve("config/quality/runtime-nondeterminism.yml"))
            sourceFiles.from(nonDetSourceFiles)
            scanSpec.set(
                com.fasterxml.jackson.databind
                    .ObjectMapper()
                    .registerModule(
                        com.fasterxml.jackson.module.kotlin.KotlinModule
                            .Builder()
                            .build(),
                    ).writeValueAsString(nonDetScanSpec),
            )
            rootDir.set(project.rootDir.absolutePath)
            reportFile.set(project.layout.buildDirectory.file("reports/maintainability/runtime-nondeterminism-verification.json"))
        }

        project.tasks.register("verifyCancellationSafety") {
            group = "maintainability"
            description =
                "Scans all production source for broad catches in suspend-capable code and rejects " +
                "newly introduced critical/high findings and risk worsenings. Accepts " +
                "-PtramaiCancellationBaseSha for PR base SHA comparison."
            doLast {
                val scanningCtx = MeasurementContext.fromProject(project)
                val inventory = CancellationCatchInventory(scanningCtx)
                val findings = inventory.inventory()

                // Derive scope from all non-example modules
                val scopedModules =
                    scanningCtx.modules
                        .map { it.path }
                        .filterNot { it.startsWith(":examples:") }
                        .toSet()

                val scopedFindings = findings.filter { it.module in scopedModules }

                // Determine comparison mode
                val baseSha = project.findProperty("tramaiCancellationBaseSha")?.toString()

                if (baseSha != null) {
                    // ── PR mode: compare against base SHA ──
                    val worktreeDir =
                        java.nio.file.Files
                            .createTempDirectory("tramai-base-${baseSha.take(8)}-")
                            .toFile()
                    val baseCatches: List<CancellationCatchFinding>
                    var worktreeCreated = false

                    try {
                        // Create temporary git worktree at base SHA
                        val addProcess =
                            ProcessBuilder(
                                "git",
                                "worktree",
                                "add",
                                worktreeDir.absolutePath,
                                baseSha,
                                "--detach",
                            ).directory(project.rootDir)
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
                    val resolveProcess =
                        ProcessBuilder("git", "merge-base", "HEAD", "origin/master")
                            .directory(project.rootDir)
                            .redirectErrorStream(true)
                            .start()
                    val resolveOutput =
                        resolveProcess.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                    val resolveExit = resolveProcess.waitFor()
                    if (resolveExit != 0) {
                        throw GradleException(
                            "verifyCancellationSafety requires -PtramaiCancellationBaseSha when origin/master is not available.\n" +
                                "Usage: ./gradlew verifyCancellationSafety -PtramaiCancellationBaseSha=<sha>\n" +
                                "In CI this is auto-wired. Locally, use the base branch SHA.",
                        )
                    }

                    val baseShaLocal = resolveOutput
                    println("verifyCancellationSafety: auto-resolved merge base against origin/master = ${baseShaLocal.take(8)}")

                    // Scan merge base using the same worktree approach
                    val worktreeDir =
                        java.nio.file.Files
                            .createTempDirectory("tramai-base-${baseShaLocal.take(8)}-")
                            .toFile()
                    val localBaseCatches: List<CancellationCatchFinding>
                    var worktreeCreated = false

                    try {
                        val addProcess =
                            ProcessBuilder(
                                "git",
                                "worktree",
                                "add",
                                worktreeDir.absolutePath,
                                baseShaLocal,
                                "--detach",
                            ).directory(project.rootDir)
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
                    println(
                        "verifyCancellationSafety PASSED: ${scopedFindings.size} findings in scoped modules, " +
                            "no new critical/high findings or risk worsenings against origin/master.",
                    )
                }
            }
        }

        project.tasks.register("verifyCriticalCoverage") {
            group = "maintainability"
            description = "Compares current critical-module coverage with the committed baseline"
            dependsOn("generateCoverageBaseline")
            doLast {
                val committed = readCommittedBaseline(project)
                val current =
                    ReportNormalizer.readJson(
                        File(reportDir, "coverage-summary.json"),
                        CoverageData::class.java,
                    )
                verifyTestQualityDiagnostics(
                    project,
                    "Critical coverage",
                    CoverageBaselineVerifier(testQualityConfiguration)
                        .verify(committed.testQuality.coverage, current),
                )
            }
        }

        project.tasks.register("verifyCriticalMutationBaseline") {
            group = "maintainability"
            description = "Compares current critical mutation results with the committed baseline"
            dependsOn("generateCriticalMutationBaseline")
            doLast {
                val committed = readCommittedBaseline(project)
                val current =
                    ReportNormalizer.readJson(
                        File(reportDir, "mutation-summary.json"),
                        MutationData::class.java,
                    )
                verifyTestQualityDiagnostics(
                    project,
                    "Critical mutation",
                    MutationBaselineVerifier(testQualityConfiguration, project.rootDir)
                        .verify(committed.testQuality.mutation, current),
                )
            }
        }

        project.tasks.register("verifyTestPerformanceBaseline") {
            group = "maintainability"
            description = "Compares current median test timing with the committed baseline"
            dependsOn("generateTestPerformanceBaseline")
            doLast {
                val committed = readCommittedBaseline(project)
                val current =
                    ReportNormalizer.readJson(
                        File(reportDir, "test-performance/median.json"),
                        TestPerformanceData::class.java,
                    )
                verifyTestQualityDiagnostics(
                    project,
                    "Test performance",
                    TestPerformanceVerifier(testQualityConfiguration)
                        .verify(committed.testQuality.testPerformance, current),
                )
            }
        }

        // ---- Canonical Baseline Generation ----
        // Only for tagged releases. Runs from a detached v0.5.0 worktree.
        // Set maintainability.sourceRoot to the worktree path, or run from the
        // worktree itself.

        project.tasks.register("generateCanonicalMaintainabilityBaseline") {
            group = "maintainability"
            description =
                "Generates the canonical baseline from v0.5.0. Set -Pmaintainability.sourceRoot=<worktree> to scan a detached checkout."

            doLast {
                // Verify the analyzer (PR) checkout is clean before generation
                val analyzerStatus =
                    ProcessBuilder(listOf("git", "status", "--porcelain"))
                        .directory(project.rootDir)
                        .redirectErrorStream(true)
                        .start()
                val analyzerOutput = analyzerStatus.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val analyzerExitCode = analyzerStatus.waitFor()
                project.logger.lifecycle("DEBUG analyzer checkout: exit=$analyzerExitCode output='${analyzerOutput.trim()}'")
                if (analyzerExitCode != 0 || analyzerOutput.isNotBlank()) {
                    throw GradleException(
                        "Analyzer checkout must be clean before canonical generation.\n" +
                            "Commit or stash changes in ${project.rootDir} first.",
                    )
                }

                val sourceRootProp = project.findProperty("maintainability.sourceRoot")?.toString()
                val canonicalGenerator =
                    if (sourceRootProp != null) {
                        val sourceRoot = project.rootDir.resolve(sourceRootProp).normalize()
                        if (!sourceRoot.isDirectory) {
                            throw GradleException(
                                "maintainability.sourceRoot='$sourceRootProp' resolved to " +
                                    "'${sourceRoot.absolutePath}' but is not a directory",
                            )
                        }
                        BaselineGenerator.fromDirectory(sourceRoot, analyzerRoot = project.rootDir)
                    } else {
                        BaselineGenerator.fromProject(project)
                    }

                // When probing a detached worktree, use CanonicalGradleProbe for API/dependency
                // measurements. The generator handles structural/scanner data from directory mode.
                val sourceRootFile = if (sourceRootProp != null) project.rootDir.resolve(sourceRootProp).normalize() else null
                val outputDirProp = project.findProperty("maintainability.outputDir")?.toString()
                val probeOutputDir =
                    if (outputDirProp != null) {
                        project.rootDir.resolve(outputDirProp).also { it.mkdirs() }
                    } else {
                        null
                    }
                val canonicalProbe =
                    sourceRootFile?.let {
                        CanonicalGradleProbe(
                            sourceRoot = it,
                            outputDir = probeOutputDir,
                            analyzerRoot = project.rootDir,
                        )
                    }
                val apiOverride: ApiBaseline? =
                    if (sourceRootFile != null) {
                        val result = canonicalProbe!!.probeApiBaseline()
                        project.logger.lifecycle("Canonical API probe: ${result.records.size} records")
                        val stabilities = result.records.groupBy { it.stability }.mapValues { it.value.size }
                        project.logger.lifecycle("  Stability breakdown: $stabilities")
                        if (result.records.none { it.applicable && it.sha256.isNotBlank() }) {
                            throw GradleException(
                                "Canonical API probe produced no valid API hashes. " +
                                    "At least one applicable module must have a captured API dump.",
                            )
                        }
                        ApiBaseline(
                            modules = result.records,
                            aggregateHash =
                                java.security.MessageDigest
                                    .getInstance("SHA-256")
                                    .digest(ReportNormalizer.toJson(result.records).toByteArray(Charsets.UTF_8))
                                    .joinToString("") { "%02x".format(it) },
                        )
                    } else {
                        null
                    }

                val dependencyOverride: List<ResolvedDependency>? =
                    if (sourceRootFile != null) {
                        val result = canonicalProbe!!.probeDependencyBaseline()
                        project.logger.lifecycle("Canonical dependency probe: ${result.records.size} records")
                        if (result.records.isEmpty()) {
                            throw GradleException(
                                "Canonical dependency probe produced no dependency records. " +
                                    "Non-empty dependency baseline is required.",
                            )
                        }
                        result.records
                    } else {
                        null
                    }

                val testQualityOverride =
                    canonicalProbe?.probeTestQualityBaseline(
                        testQualityConfiguration,
                    )
                if (testQualityOverride != null) {
                    project.logger.lifecycle(
                        "Canonical test-quality probe: " +
                            "${testQualityOverride.coverage.criticalModules.size} coverage modules, " +
                            "${testQualityOverride.mutation.totalMutants} mutants, " +
                            "${testQualityOverride.testPerformance.totalTestCount} tests",
                    )
                }

                val baseline =
                    canonicalGenerator.generateCompleteBaseline(
                        apiOverride = apiOverride,
                        dependencyOverride = dependencyOverride,
                        coverageOverride = testQualityOverride?.coverage,
                        mutationOverride = testQualityOverride?.mutation,
                        testPerformanceOverride = testQualityOverride?.testPerformance,
                    )
                val identity = baseline.baselineIdentity

                // Provenance gates
                if (identity.measuredCommitSha != identity.baselineCommitSha) {
                    throw GradleException(
                        "Canonical baseline must be generated at ${identity.releaseTag}. " +
                            "HEAD=${identity.measuredCommitSha.take(8)}, " +
                            "tag=${identity.baselineCommitSha.take(8)}. " +
                            "Use -Pmaintainability.sourceRoot=<v0.5.0-worktree-path>",
                    )
                }

                if (!identity.workingTreeClean) {
                    throw GradleException(
                        "Canonical baseline must be generated from a clean worktree. " +
                            "Commit or stash changes first.",
                    )
                }

                if (identity.measuredSourceTreeHash.isBlank()) {
                    throw GradleException(
                        "Canonical baseline has an empty measuredSourceTreeHash. " +
                            "Ensure the worktree is clean and git is available.",
                    )
                }

                if (identity.measuredGitTreeSha.isBlank()) {
                    throw GradleException(
                        "Canonical baseline has an empty measuredGitTreeSha. " +
                            "Ensure git is available in the source root.",
                    )
                }

                // Write directly to PR branch (not the worktree)
                val prBaselineFile = File(project.rootDir, "config/quality/0.6.0-baseline.json")
                ReportNormalizer.writeJson(baseline, prBaselineFile)
                println("  Wrote canonical baseline to ${prBaselineFile.absolutePath}")
                println(
                    "Canonical maintainability baseline generated for " +
                        "${identity.releaseTag} at ${identity.measuredCommitSha.take(8)}",
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
                "verifyTestPerformanceBaseline",
                "verifyRuntimeNondeterminism",
            )
            doLast {
                println("Full maintainability baseline verification complete.")
            }
        }

        // ---- Change Policy Verification ----

        project.tasks.register("verifyChangePolicy", ChangePolicyVerifierTask::class.java) {
            group = "maintainability"
            description = "Enforces change-policy rules: forbidden path combinations and deviation evidence"
            rootDir.set(project.rootDir)

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
        // Typed (Epic 9.2d-a3c1). projectPaths/bomPaths are configuration-side
        // snapshots; publishedPaths is wired from the release task's typed model
        // via withPlugin (the maintainability plugin applies BEFORE
        // tramai.release-verification in the root build script, so the provider
        // is connected lazily — never read the root extra here).
        val verifyModuleManifest =
            project.tasks.register<VerifyModuleManifestTask>("verifyModuleManifest") {
                group = "verification"
                description = "Verifies manifest/settings equality and publishing/BOM membership against independent Gradle model signals"
                moduleCatalogFile.set(project.layout.projectDirectory.file("config/quality/module-catalog.yml"))
                projectPaths.set(ModuleTopologySnapshot.projectPaths(project))
                // Fail-closed default: without the release plugin the historical
                // implementation supplied an EMPTY publication set and produced the
                // typed MODULE_CATALOG_PUBLISHING_DRIFT diagnostic. Preserve that —
                // never die on an unset property. The withPlugin wiring below
                // overrides the convention with the release task's typed model.
                publishedPaths.convention(emptyList())
                // BOM signal read LAZILY through a provider: the java-platform model
                // is incomplete at plugin apply time (Epic 9.2d-a1 rule). Never eager,
                // never a Configuration as a task property — converted to List<String>.
                bomPaths.set(project.provider { ModuleTopologySnapshot.bomPaths(project) })
            }
        project.pluginManager.withPlugin("tramai.release-verification") {
            verifyModuleManifest.configure {
                val releaseTask =
                    project.tasks.named(
                        "verifyPublicationMetadata",
                        VerifyPublicationMetadataTask::class.java,
                    )
                // Module names -> Gradle paths (":name"), sorted, from the release
                // task's typed model — NOT the tramai.publishableModulePaths extra.
                publishedPaths.set(
                    releaseTask
                        .flatMap { it.publishableModules }
                        .map { names -> names.map { ":$it" }.sorted() },
                )
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

        project.tasks.register<ModuleMatrixDriftVerifierTask>("verifyModuleMatrixDrift") {
            group = "verification"
            description = "Fails when docs/reference/module-matrix.md differs from the manifest"
            this.rootDir.set(project.rootDir)
            moduleMatrixFile.set(project.layout.projectDirectory.file("docs/reference/module-matrix.md"))
            moduleCatalogFile.from(project.layout.projectDirectory.file("config/quality/module-catalog.yml"))
        }

        val enrollmentTest =
            project.tasks.register("architectureContractEnrollmentTest", Test::class.java) {
                group = "verification"
                description = "Runs provider and store enrollment architecture contracts"
                val testingProject = project.project(":tramai-testing")
                val testSourceSet =
                    testingProject.extensions
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
                    testingProject.layout.buildDirectory.dir("test-results/architectureContractEnrollmentTest/binary"),
                )
                reports.junitXml.outputLocation.set(
                    testingProject.layout.buildDirectory.dir("test-results/architectureContractEnrollmentTest"),
                )
                reports.html.outputLocation.set(
                    testingProject.layout.buildDirectory.dir("reports/tests/architectureContractEnrollmentTest"),
                )
            }

        project.tasks.register<VerifyArchitectureTask>("verify060Architecture") {
            group = "verification"
            description = "build(quality): add unified 0.6.0 architecture gate"
            dependsOn(*architectureProbeTasks.toTypedArray())
            dependsOn(enrollmentTest)
            // C1: regenerate every applicable BCV dump from current source so
            // Contract-1 runs on a clean workspace instead of silently skipping
            // missing build/api/*.api artifacts (fail-closed: absence → FAIL).
            val apiBuildTasks =
                project.allprojects
                    .filter { sub -> sub != project && sub.tasks.findByName("apiBuild") != null }
                    .filter { sub -> ApiCompatibilityEvidenceReader.committedDumpPath(sub.projectDir, sub.name).isFile }
                    .map { "${it.path}:apiBuild" }
            dependsOn(*apiBuildTasks.toTypedArray())

            // ---- Declared inputs (execution authority — a3 discipline) ----
            val committedBaseline = project.layout.projectDirectory.file("config/quality/0.6.0-baseline.json")
            if (committedBaseline.asFile.isFile) {
                committedBaselineFile.set(committedBaseline)
            } else {
                committedBaselineFile.unset()
            }
            deviationsFile.set(project.layout.projectDirectory.file("config/quality/maintainability-deviations.yml"))
            moduleCatalogFile.set(project.layout.projectDirectory.file("config/quality/module-catalog.yml"))
            moduleBoundariesFile.set(project.layout.projectDirectory.file("config/quality/module-boundaries.yml"))
            settingsFile.set(project.layout.projectDirectory.file("settings.gradle.kts"))
            // api-migrations.yml is optional (absent in minimal fixtures and
            // handled as empty by parseMigrations); set-but-missing would trip
            // Gradle 9's eager @InputFile validation before the action runs.
            val migrationsFile = project.layout.projectDirectory.file("config/quality/api-migrations.yml")
            if (migrationsFile.asFile.isFile) {
                apiMigrationsFile.set(migrationsFile)
            } else {
                apiMigrationsFile.unset()
            }
            baseRef.set(project.findProperty("changePolicyBase")?.toString() ?: "origin/master")
            projectVersion.set(project.providers.gradleProperty("tramaiVersion").orElse("0.5.0"))
            // Architecture gate owns its own report dir (the maintainability
            // report dir belongs to verifyMaintainabilityBaselineTask).
            this.reportDir.set(project.layout.buildDirectory.dir("reports/tramai/architecture"))
            architectureReportFile.set(
                project.layout.buildDirectory.file("reports/tramai/architecture/architecture-report.json"),
            )
            // Fail-soft probe outputs — typed task outputs, never a path walk.
            dependencyProbeFiles.from(
                architectureProbeProviders.map { probe -> probe.flatMap { it.outputFile } },
            )
            // Fail-soft consumer compile markers — typed task outputs (fixture
            // projects; registered in registerConsumerCompatibilityTask).
            consumerMarkers.from(consumerMarkerProviders.map { it.flatMap { task -> task.markerFile } })
            // Measured tree: module sources/build files, settings, version
            // properties, root build script + version catalog, committed api
            // dumps, release notes (same universe as verifyMaintainabilityBaseline).
            val measuredDirs =
                project.allprojects
                    .filter { it != project && it.buildFile.exists() }
                    .flatMap { sub ->
                        listOf(
                            File(sub.projectDir, "src/main/kotlin"),
                            File(sub.projectDir, "src/main/java"),
                            File(sub.projectDir, "src/test/kotlin"),
                            File(sub.projectDir, "src/testFixtures/kotlin"),
                            sub.buildFile,
                        )
                    }
            sourceTree.from(project.files(measuredDirs))
            sourceTree.from(
                project.files(
                    project.rootDir.resolve("settings.gradle.kts"),
                    project.rootDir.resolve("gradle.properties"),
                    project.rootDir.resolve("build.gradle.kts"),
                    project.rootDir.resolve("gradle/libs.versions.toml"),
                ),
            )
            sourceTree.from(
                project.fileTree(project.rootDir) {
                    include("**/api/*.api", "docs/releases/0.6.0-maintainability-baseline.md")
                },
            )
        }

        // ---- Consumer compile proofs (Epic 10.2): real sources, real classes ----
        // C3/C4: these are FAIL-SOFT producers. They never throw, so they can
        // never terminate the task graph before verify060Architecture's report
        // is written. Each task runs the real compiler (javac / K2JVMCompiler)
        // with ignoreExitValue=true and writes a marker file; the gate reads
        // the markers as typed api-architecture evidence (B2 fail-closed model).

        registerConsumerCompatibilityTask(
            "verifyJavaConsumerCompatibility",
            ":examples:java-consumer-smoke",
            "java",
            "java",
        )
        registerConsumerCompatibilityTask(
            "verifyKotlinConsumerCompatibility",
            ":examples:kotlin-consumer-smoke",
            "kotlin",
            "org.jetbrains.kotlin.jvm",
        )
        project.tasks.named("verify060Architecture") {
            // Depend on the collected producer providers, not hard-coded task
            // paths: in minimal fixtures (TestKit) only the producers whose
            // language plugin actually applied exist, and a missing path would
            // abort task-graph resolution before the gate's report is written.
            dependsOn(consumerMarkerProviders)
        }

        // Module documentation contract gate (Epic 11.2b3) — wired into the 0.6.0
        // architecture gate (verifyPr wiring follows its registration below).
        // Registered by a separate plugin that minimal TestKit fixtures do not
        // apply; guard so a missing task cannot abort task-graph resolution
        // before the gate's fail-closed report is written.
        if (project.tasks.findByName("verifyModuleDocContract") != null) {
            project.tasks.named("verify060Architecture") {
                dependsOn("verifyModuleDocContract")
            }
        }

        // ---- PR Verification (primary local check gate) ----

        val verifyPr =
            project.tasks.register("verifyPr") {
                group = "verification"
                description =
                    "Primary local verification gate. Runs subproject tests, build-logic tests, " +
                    "maintainability baseline, and change policy. Not a full CI replica — " +
                    "see .github/AGENTS.md for additional step commands."

                dependsOn("verifyMaintainabilityBaseline")
                dependsOn("verifyChangePolicy")
                dependsOn("verifyModuleManifest")
                dependsOn("verifyModuleMatrixDrift")
                dependsOn("verifyModuleDocContract")

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
        project.tasks.register<JUnitTestSignatureVerifierTask>("verifyJUnitTestSignatures") {
            group = "verification"
            description = "Fails if any @Test function uses an expression body whose inferred " +
                "return type is not provably Unit (JUnit silently skips non-void @Test methods)."
            this.scanRoot.set(project.rootDir)
            // FileTree base is rootDir; exclude task-output dirs (build/,
            // api/) or Gradle 9 flags the input as overlapping a task output.
            // The scanner itself only inspects src/test|src/testFixtures .kt,
            // so excluding outputs changes no semantics.
            testSources.from(
                project.fileTree(project.rootDir) {
                    include("**/src/test/**/*.kt", "**/src/testFixtures/**/*.kt")
                    exclude("**/build/**", "**/api/**")
                },
            )
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
        diagnostics: List<VerificationDiagnostic>,
    ) {
        diagnostics
            .filter { it.severity == DiagnosticSeverity.WARNING }
            .forEach { project.logger.warn("WARN: ${it.message}") }
        val failures = diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }
        failures.forEach { project.logger.error("FAIL: ${it.message}") }
        if (failures.isNotEmpty()) {
            throw GradleException(
                "$label baseline verification FAILED:\n" +
                    failures.joinToString("\n") { "  - ${it.message}" },
            )
        }
        println("$label baseline verification PASSED.")
    }

    private fun runNestedGradle(
        project: Project,
        arguments: List<String>,
    ) {
        val wrapper =
            File(
                project.rootDir,
                if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew",
            )
        val command = mutableListOf<String>()
        if (!wrapper.canExecute() && !wrapper.name.endsWith(".bat")) command += "bash"
        command += wrapper.absolutePath
        command +=
            listOf(
                "--no-daemon",
                "--no-build-cache",
                "--no-configuration-cache",
                "--no-parallel",
                "--console=plain",
            )
        command += arguments
        val process =
            ProcessBuilder(command)
                .directory(project.rootDir)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException(
                "Nested Gradle execution failed with exit code $exitCode:\n$output",
            )
        }
        project.logger.lifecycle(output.trimEnd())
    }

    private fun copyTestReports(
        repositoryRoot: File,
        criticalModules: Set<String>,
        runRoot: File,
    ) {
        criticalModules.sorted().forEach { module ->
            val modulePath = module.removePrefix(":").replace(":", "/")
            val sourceDir = File(repositoryRoot, "$modulePath/build/test-results/test")
            val destinationDir = File(runRoot, modulePath)
            val reports =
                sourceDir
                    .listFiles { file ->
                        file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
                    }?.sortedBy { it.name }
                    .orEmpty()
            if (reports.isEmpty()) {
                throw GradleException(
                    "Missing expected test report for $module at ${sourceDir.absolutePath}",
                )
            }
            destinationDir.mkdirs()
            reports.forEach { it.copyTo(File(destinationDir, it.name), overwrite = true) }
        }
    }

    private fun mutationInitScript(
        configuration: TestQualityConfiguration,
        reportRoot: File,
    ): String {
        val familyModules =
            configuration.mutation.targetFamilies.entries
                .sortedBy { it.key }
                .joinToString(",\n") { (family, target) ->
                    val modules =
                        target.modules.sorted().joinToString(", ") {
                            "'${groovyString(it)}'"
                        }
                    val classes =
                        target.targetClasses.sorted().joinToString(", ") {
                            "'${groovyString(it)}'"
                        }
                    val tests =
                        target.targetTests.sorted().joinToString(", ") {
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

    private fun groovyString(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    /**
     * Registers a consumer-compatibility PRODUCER task (Epic 10.2, C3/C4).
     *
     * Unlike the original throwing verify task, this task NEVER throws: it
     * invokes the real compiler (javac via toolchain for java, K2JVMCompiler
     * via kotlinCompilerClasspath for kotlin) with ignoreExitValue=true, then
     * writes a marker file. The verify060Architecture gate depends on these
     * producers (they cannot abort the graph) and reads the markers as typed
     * api-architecture evidence — preserving the B2 fail-closed contract that
     * the report is always written before the terminal GradleException.
     *
     * Marker shape (build/reports/maintainability/consumer-<lang>.json):
     *   { "sources": N, "classes": M, "exitCode": E, "ok": bool }
     */
    private fun registerConsumerCompatibilityTask(
        taskName: String,
        modulePath: String,
        extension: String,
        languagePluginId: String,
    ) {
        rootProject.subprojects.forEach { fixture ->
            if (fixture.path != modulePath) return@forEach
            fixture.pluginManager.withPlugin(languagePluginId) {
                val markerFile = fixture.layout.buildDirectory.file("reports/maintainability/consumer-$extension.json")
                val classesDir = fixture.layout.buildDirectory.dir("reports/maintainability/consumer-$extension-classes")

                val task =
                    fixture.tasks.register<ConsumerSmokeCompileTask>(taskName) {
                        group = "verification"
                        description =
                            "Compiles the $extension consumer smoke fixture against the stable API"

                        dependsOn(":tramai-core:jar")

                        this.extension.set(extension)
                        this.workDir.set(project.rootDir)
                        val toolchains = fixture.extensions.getByType(JavaToolchainService::class.java)
                        if (extension == "java") {
                            this.javacExecutable.set(
                                toolchains
                                    .compilerFor { languageVersion.set(JavaLanguageVersion.of(21)) }
                                    .map { it.executablePath.toString() },
                            )
                        } else {
                            this.javaExecutable.set(
                                toolchains
                                    .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
                                    .map { it.executablePath.toString() },
                            )
                        }
                        this.classesDir.set(classesDir)
                        this.markerFile.set(markerFile)
                        val sourceDir = if (extension == "kotlin") "kotlin" else "java"
                        val sourceExt = if (extension == "kotlin") "kt" else "java"
                        val sourceGlob = "**/src/main/$sourceDir/**/*.$sourceExt"
                        sources.from(
                            fixture.fileTree(fixture.projectDir) {
                                include(sourceGlob)
                            },
                        )
                        compileClasspath.from(fixture.configurations.getByName("compileClasspath"))
                        if (extension == "kotlin") {
                            kotlinCompilerClasspath.from(fixture.configurations.getByName("kotlinCompilerClasspath"))
                        }
                    }
                consumerMarkerProviders.add(task)
            }
        }
    }
}
