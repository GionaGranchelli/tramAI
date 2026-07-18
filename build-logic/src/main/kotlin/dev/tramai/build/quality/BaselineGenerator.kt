package dev.tramai.build.quality

import org.gradle.api.Project
import org.gradle.api.GradleException
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.io.File
import java.time.Instant
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Orchestrates all baseline generation steps and writes the unified baseline JSON.
 */
class BaselineGenerator(
    val rootProject: Project,
    private val outputDir: File = File(rootProject.layout.buildDirectory.get().asFile, "reports/maintainability"),
    private val writeRepositoryArtifacts: Boolean = true
) {

    private val baselineGitIdentity: BaselineGitIdentity by lazy {
        resolveBaselineGitIdentity()
    }

    fun generateFullBaseline(): BaselineDocument {
        val identity = generateIdentity()
        if (identity.baselineCommitSha != identity.measuredCommitSha) {
            rootProject.logger.warn(
                "WARNING: maintainability baseline is measured at ${identity.measuredCommitSha} " +
                    "but identifies release tag ${identity.releaseTag} at ${identity.baselineCommitSha}"
            )
        }
        val baseline = BaselineDocument(
            baselineIdentity = identity,
            generatedAt = deterministicGeneratedAt(identity.commitTimestamp),
            generatedBy = "generateMaintainabilityBaseline",
            environment = EnvironmentInfo(
                os = System.getProperty("os.name"),
                javaVersion = Runtime.version().feature().toString()
            )
        )

        return baseline
    }

    /**
     * Generates the complete baseline with all measurements populated.
     * Used by the verifier for comparison. Unlike generateFullBaseline(),
     * this populates structural, safety, protocol, API, dependency, and test-quality sections.
     */
    fun generateCompleteBaseline(): BaselineDocument {
        val baseline = generateFullBaseline()
        val graphAnalyzer = ModuleGraphAnalyzer(rootProject)
        val sourceMetricsAnalyzer = SourceMetricsAnalyzer(rootProject)
        val cancellationInventory = CancellationCatchInventory(rootProject)
        val globalStateInventory = GlobalStateInventory(rootProject)
        val nondeterminismInventory = NondeterminismInventory(rootProject)

        val structural = generateModuleDependencyGraph(graphAnalyzer)
        val productionCatches = cancellationInventory.inventory().filter { !it.file.contains("/test/") }
        val testCatches = cancellationInventory.inventory().filter { it.file.contains("/test/") }

        return baseline.copy(
            structural = structural.copy(
                sourceMetrics = generateSourceMetrics(sourceMetricsAnalyzer.analyze()),
                structuralHotspots = generateStructuralHotspots()
            ),
            runtimeSafety = RuntimeSafetyBaseline(
                cancellationCatches = productionCatches,
                testCancellationCatches = testCatches,
                globalState = globalStateInventory.inventory(),
                nondeterminism = nondeterminismInventory.inventory()
            ),
            dependencies = baseline.dependencies.copy(
                resolvedDependencies = generateResolvedDependencyGraph()
            ),
            protocolCatalog = generateRuntimeProtocolCatalog(),
            api = generateApiBaseline(),
            testQuality = baseline.testQuality.copy(
                testPerformance = generateTestPerformance()
            )
        )
    }

    fun generateIdentity(): BaselineIdentity {
        val workingTreeClean = isWorkingTreeClean()
        val sourceTreeHash = if (workingTreeClean) computeSourceTreeHash() else ""
        return BaselineIdentity(
            repository = "GionaGranchelli/tramAI",
            releaseTag = "v0.5.0",
            commitSha = baselineGitIdentity.baselineCommitSha,
            baselineCommitSha = baselineGitIdentity.baselineCommitSha,
            measuredCommitSha = baselineGitIdentity.measuredCommitSha,
            workingTreeClean = workingTreeClean,
            measuredSourceTreeHash = sourceTreeHash,
            commitTimestamp = baselineGitIdentity.commitTimestamp,
            tramaiVersion = rootProject.findProperty("tramaiVersion")?.toString() ?: "0.5.0",
            toolchain = ToolchainInfo(
                gradle = rootProject.gradle.gradleVersion,
                kotlin = "2.3.0",
                jvmTarget = "21",
                ciJdk = "21"
            )
        )
    }

    fun generateModuleDependencyGraph(graphAnalyzer: ModuleGraphAnalyzer): StructuralBaseline {
        val graph = graphAnalyzer.analyze()
        outputDir.mkdirs()

        // Write JSON
        ReportNormalizer.writeJson(graph.moduleDependencies, File(outputDir, "module-dependencies.json"))
        ReportNormalizer.writeJson(graph.moduleDependenciesTest, File(outputDir, "module-dependencies-test.json"))

        // Write DOT
        File(outputDir, "module-dependencies.dot").writeText(graphAnalyzer.generateDot(graph))

        // Write Mermaid to docs
        if (writeRepositoryArtifacts) {
            val mermaid = graphAnalyzer.generateMermaid(graph)
            val docsDir = File(rootProject.rootDir, "docs/architecture")
            docsDir.mkdirs()
            val mdFile = File(docsDir, "module-dependency-graph.md")
            mdFile.writeText(generateModuleDependencyGraphMarkdown(graph, mermaid))
        }

        return StructuralBaseline(
            modules = graph.modules,
            moduleDependencies = graph.moduleDependencies,
            moduleDependenciesTest = graph.moduleDependenciesTest
        )
    }

    fun generateSourceMetrics(metrics: Map<String, ModuleSourceMetrics>): SourceMetricsData {
        var totalProdFiles = 0
        var totalProdLines = 0
        var totalTestFiles = 0
        var totalTestLines = 0

        for ((_, m) in metrics) {
            totalProdFiles += m.production.files
            totalProdLines += m.production.codeLines
            totalTestFiles += m.test.files
            totalTestLines += m.test.codeLines
        }

        val result = SourceMetricsData(
            byModule = metrics,
            totals = SourceTotals(totalProdFiles, totalProdLines, totalTestFiles, totalTestLines)
        )

        ReportNormalizer.writeJson(result, File(outputDir, "source-metrics.json"))
        return result
    }

    fun generateStructuralHotspots(): StructuralHotspots {
        val projects = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }
        val productionFileSizes = mutableListOf<StructuralHotspot>()
        val testFileSizes = mutableListOf<StructuralHotspot>()
        val buildFileSizes = mutableListOf<StructuralHotspot>()
        val classSizes = mutableListOf<StructuralHotspot>()
        val functionsPerClass = mutableListOf<StructuralHotspot>()
        val constructorParameters = mutableListOf<StructuralHotspot>()
        val functionParameters = mutableListOf<StructuralHotspot>()

        val rootBuildFile = File(rootProject.rootDir, "build.gradle.kts")
        if (rootBuildFile.isFile) {
            buildFileSizes.add(
                StructuralHotspot(
                    module = ":",
                    path = "build.gradle.kts",
                    declaration = "root build",
                    metric = "fileSize",
                    value = ReportNormalizer.countNonBlankLines(rootBuildFile)
                )
            )
        }

        for (proj in projects) {
            collectKotlinFiles(File(proj.projectDir, "src/main/kotlin")).forEach { file ->
                val path = ReportNormalizer.repoRelativePath(file, rootProject.rootDir)
                productionFileSizes.add(
                    StructuralHotspot(
                        module = proj.name,
                        path = path,
                        declaration = file.nameWithoutExtension,
                        metric = "fileSize",
                        value = ReportNormalizer.countNonBlankLines(file)
                    )
                )
                val declarationMetrics = scanDeclarationMetrics(proj.name, file, path)
                classSizes.addAll(declarationMetrics.classSizes)
                functionsPerClass.addAll(declarationMetrics.functionsPerClass)
                constructorParameters.addAll(scanConstructorParameters(proj.name, file, path))
                functionParameters.addAll(scanFunctionParameters(proj.name, file, path))
            }

            collectKotlinFiles(File(proj.projectDir, "src/test/kotlin")).forEach { file ->
                testFileSizes.add(
                    StructuralHotspot(
                        module = proj.name,
                        path = ReportNormalizer.repoRelativePath(file, rootProject.rootDir),
                        declaration = file.nameWithoutExtension,
                        metric = "fileSize",
                        value = ReportNormalizer.countNonBlankLines(file)
                    )
                )
            }
        }

        // TODO(Phase 0): Populate declaration size and function/complexity rankings with
        // pinned Kotlin-aware static analysis (Detekt) instead of regex heuristics.
        // TODO(Phase 0): Populate fan-in/fan-out rankings from JVM dependency analysis
        // (ArchUnit or equivalent) once the bytecode analysis pipeline is available.
        val result = StructuralHotspots(
            largestProductionFiles = productionFileSizes
                .sortedWith(compareByDescending<StructuralHotspot> { it.value }.thenBy { it.path })
                .take(20),
            largestTestFiles = testFileSizes
                .sortedWith(compareByDescending<StructuralHotspot> { it.value }.thenBy { it.path })
                .take(20),
            largestBuildFiles = buildFileSizes
                .sortedWith(compareByDescending<StructuralHotspot> { it.value }.thenBy { it.path })
                .take(20),
            largestClasses = classSizes
                .sortedWith(compareByDescending<StructuralHotspot> { it.value }.thenBy { it.path }.thenBy { it.declaration })
                .take(20),
            mostFunctions = functionsPerClass
                .sortedWith(compareByDescending<StructuralHotspot> { it.value }.thenBy { it.path }.thenBy { it.declaration })
                .take(20),
            longestFunctions = emptyList(),
            highestCyclomaticComplexity = emptyList(),
            highestCognitiveComplexity = emptyList(),
            mostConstructorParameters = constructorParameters
                .sortedWith(compareByDescending<StructuralHotspot> { it.value }.thenBy { it.path }.thenBy { it.declaration })
                .take(20),
            mostFunctionParameters = functionParameters
                .sortedWith(compareByDescending<StructuralHotspot> { it.value }.thenBy { it.path }.thenBy { it.declaration })
                .take(20),
            highestFanOut = emptyList(),
            highestFanIn = emptyList()
        )

        ReportNormalizer.writeJson(result, File(outputDir, "structural-hotspots.json"))
        return result
    }

    fun generateRuntimeProtocolCatalog(): ProtocolCatalog {
        val entries = mutableListOf<ProtocolEntry>()
        val projects = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }
        val namedProtocolRegex = Regex(
            """(?:const\s+val|val)\s+([A-Z][A-Z0-9_]*)\s*(?::[^=]+)?=\s*"((?:tramai\.)[^"]+)""""
        )
        val namedArgumentRegex = Regex(
            """\b(name|eventName|metricName|spanName|attributeKey)\s*=\s*"(tramai\.[A-Za-z0-9_.:-]+)""""
        )
        val exceptionConstantRegex = Regex(
            """(?:const\s+val|val)\s+([A-Z][A-Z0-9_]*(?:MESSAGE|REASON|CODE)[A-Z0-9_]*)\s*(?::[^=]+)?=\s*"([^"]+)""""
        )

        for (project in projects) {
            collectKotlinFiles(File(project.projectDir, "src/main/kotlin")).forEach { file ->
                val source = ReportNormalizer.repoRelativePath(file, rootProject.rootDir)
                val content = file.readText(Charsets.UTF_8)

                namedProtocolRegex.findAll(content).forEach { match ->
                    val symbol = match.groupValues[1]
                    val value = match.groupValues[2]
                    entries.add(
                        ProtocolEntry(
                            category = classifyProtocolEntry(symbol, value),
                            name = symbol,
                            value = value,
                            source = source
                        )
                    )
                }

                namedArgumentRegex.findAll(content).forEach { match ->
                    val argument = match.groupValues[1]
                    val value = match.groupValues[2]
                    entries.add(
                        ProtocolEntry(
                            category = classifyProtocolEntry(argument, value),
                            name = value,
                            value = value,
                            source = source
                        )
                    )
                }

                exceptionConstantRegex.findAll(content).forEach { match ->
                    val symbol = match.groupValues[1]
                    val value = match.groupValues[2]
                    entries.add(
                        ProtocolEntry(
                            category = if (symbol.contains("REASON") || symbol.contains("CODE")) {
                                "reason-code"
                            } else {
                                "exception-message"
                            },
                            name = symbol,
                            value = value,
                            source = source
                        )
                    )
                }
            }
        }

        val catalog = ProtocolCatalog(
            entries = entries
                .distinctBy { listOf(it.category, it.name, it.value, it.source) }
                .sortedWith(
                    compareBy<ProtocolEntry> { it.category }
                        .thenBy { it.value }
                        .thenBy { it.source }
                        .thenBy { it.name }
                )
        )
        val catalogDocument = linkedMapOf(
            "schemaVersion" to "1",
            "description" to "Starter catalog — ${catalog.entries.size} entries identified",
            "entries" to catalog.entries
        )
        if (writeRepositoryArtifacts) {
            ReportNormalizer.writeJson(
                catalogDocument,
                File(rootProject.rootDir, "config/quality/runtime-protocol-catalog.json")
            )
        }
        ReportNormalizer.writeJson(catalog, File(outputDir, "runtime-protocol-catalog.json"))
        return catalog
    }

    fun generateCancellationCatchInventory(inventory: List<CancellationCatchFinding>): RuntimeSafetyBaseline {
        ReportNormalizer.writeJson(
            mapOf("findings" to inventory),
            File(outputDir, "cancellation-safety.json")
        )
        return RuntimeSafetyBaseline(cancellationCatches = inventory)
    }

    fun generateGlobalStateInventory(inventory: List<GlobalStateFinding>): RuntimeSafetyBaseline {
        ReportNormalizer.writeJson(
            mapOf("findings" to inventory),
            File(outputDir, "global-state.json")
        )
        return RuntimeSafetyBaseline(globalState = inventory)
    }

    fun generateNondeterminismInventory(inventory: List<NondeterminismFinding>): RuntimeSafetyBaseline {
        ReportNormalizer.writeJson(
            mapOf("findings" to inventory),
            File(outputDir, "nondeterminism.json")
        )
        return RuntimeSafetyBaseline(nondeterminism = inventory)
    }

    fun generateApiBaseline(): ApiBaseline {
        val dumps = rootProject.rootDir.walkTopDown()
            .filter { file ->
                file.isFile &&
                    file.extension == "api" &&
                    file.parentFile?.name == "api" &&
                    !file.toPath().any { it.toString() == "build" }
            }
            .associate { file ->
                ReportNormalizer.repoRelativePath(file, rootProject.rootDir) to sha256(file.readBytes())
            }
            .toSortedMap()
        val aggregateHash = sha256(
            dumps.entries.joinToString("\n") { (path, hash) -> "$path=$hash" }.toByteArray(Charsets.UTF_8)
        )
        val result = ApiBaseline(publicApiDumps = dumps, apiCheckHash = aggregateHash)
        ReportNormalizer.writeJson(result, File(outputDir, "public-api-dumps.json"))
        return result
    }

    fun generateTestPerformance(): TestPerformanceData {
        val modulePerformance = linkedMapOf<String, ModuleTestPerformance>()
        val classTimings = mutableListOf<TestTiming>()
        val testTimings = mutableListOf<TestTiming>()

        rootProject.allprojects
            .filter { it != rootProject && it.buildFile.exists() }
            .sortedBy { it.path }
            .forEach { project ->
                val resultDir = File(project.layout.buildDirectory.get().asFile, "test-results/test")
                val xmlFiles = resultDir.listFiles { file ->
                    file.isFile && file.name.startsWith("TEST-") && file.extension == "xml"
                }?.sortedBy { it.name }.orEmpty()
                if (xmlFiles.isEmpty()) return@forEach

                var durationMs = 0L
                var tests = 0
                var skipped = 0
                var failures = 0

                xmlFiles.forEach { xmlFile ->
                    val document = newSecureDocumentBuilderFactory().newDocumentBuilder().parse(xmlFile)
                    val suite = document.documentElement
                    val suiteDuration = secondsToMillis(suite.getAttribute("time"))
                    val suiteTests = suite.getAttribute("tests").toIntOrNull() ?: 0
                    val suiteSkipped = suite.getAttribute("skipped").toIntOrNull() ?: 0
                    val suiteFailures = (suite.getAttribute("failures").toIntOrNull() ?: 0) +
                        (suite.getAttribute("errors").toIntOrNull() ?: 0)
                    val className = suite.getAttribute("name").ifBlank {
                        xmlFile.name.removePrefix("TEST-").removeSuffix(".xml")
                    }

                    durationMs += suiteDuration
                    tests += suiteTests
                    skipped += suiteSkipped
                    failures += suiteFailures
                    classTimings.add(
                        TestTiming(
                            module = project.path,
                            className = className,
                            testName = "<class>",
                            durationMs = suiteDuration
                        )
                    )

                    val cases = suite.getElementsByTagName("testcase")
                    for (index in 0 until cases.length) {
                        val case = cases.item(index) as? Element ?: continue
                        testTimings.add(
                            TestTiming(
                                module = project.path,
                                className = case.getAttribute("classname").ifBlank { className },
                                testName = case.getAttribute("name").ifBlank { "<unknown>" },
                                durationMs = secondsToMillis(case.getAttribute("time"))
                            )
                        )
                    }
                }

                modulePerformance[project.path] = ModuleTestPerformance(
                    module = project.path,
                    totalDurationMs = durationMs,
                    testCount = tests,
                    skippedCount = skipped,
                    failureCount = failures
                )
            }

        val result = TestPerformanceData(
            byModule = modulePerformance,
            slowestClasses = classTimings
                .sortedWith(compareByDescending<TestTiming> { it.durationMs }.thenBy { it.module }.thenBy { it.className })
                .take(10),
            slowestTests = testTimings
                .sortedWith(
                    compareByDescending<TestTiming> { it.durationMs }
                        .thenBy { it.module }
                        .thenBy { it.className }
                        .thenBy { it.testName }
                )
                .take(10),
            totalDurationMs = modulePerformance.values.sumOf { it.totalDurationMs },
            totalTestCount = modulePerformance.values.sumOf { it.testCount }
        )
        ReportNormalizer.writeJson(result, File(outputDir, "test-performance.json"))
        return result
    }

    fun updateBaselineJson(baseline: BaselineDocument) {
        val baselineFile = File(rootProject.rootDir, "config/quality/0.6.0-baseline.json")
        ReportNormalizer.writeJson(baseline, baselineFile)
    }

    fun generateResolvedDependencyGraph(): List<ResolvedDependency> {
        val resolved = mutableListOf<ResolvedDependency>()
        val projects = rootProject.allprojects.filter { it != rootProject && it.buildFile.exists() }

        for (proj in projects) {
            val configs = listOf("runtimeClasspath", "compileClasspath")
            for (configName in configs) {
                try {
                    val config = proj.configurations.findByName(configName) ?: continue
                    if (!config.isCanBeResolved) continue

                    val resolutionResult = config.incoming.resolutionResult
                    resolutionResult.allComponents.forEach { component ->
                        val id = component.id
                        if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                            val depResult = component as? ResolvedComponentResult
                            val selectionReason = component.selectionReason?.toString() ?: "unknown"
                            val path = (component as? ResolvedComponentResult)?.let { result ->
                                result.dependents.map { dep ->
                                    val depId = dep.from.id
                                    if (depId is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                                        "${depId.group}:${depId.module}:${depId.version}"
                                    } else {
                                        dep.from.id.displayName
                                    }
                                }
                            } ?: emptyList()

                            resolved.add(
                                ResolvedDependency(
                                    group = id.group,
                                    artifact = id.module,
                                    selectedVersion = id.version,
                                    requestedVersion = (depResult?.dependents?.firstOrNull() as? ResolvedDependencyResult)?.requested?.let {
                                        if (it is org.gradle.api.artifacts.component.ModuleComponentSelector) it.version
                                        else it.toString()
                                    },
                                    direct = depResult?.dependents?.any { dep ->
                                        dep.from.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier
                                    } ?: false,
                                    configuration = configName,
                                    selectionReason = selectionReason,
                                    dependencyPath = path.map { "$it" },
                                    consumers = listOf(proj.name)
                                )
                            )
                        }
                    }
                } catch (_: Exception) {
                    // Configuration may not be resolvable
                }
            }
        }

        val deduped = resolved.distinctBy { "${it.group}:${it.artifact}:${it.selectedVersion}:${it.configuration}" }
        ReportNormalizer.writeJson(
            mapOf("dependencies" to deduped),
            File(outputDir, "resolved-dependencies.json")
        )
        return deduped
    }

    private fun secondsToMillis(value: String): Long =
        value.toBigDecimalOrNull()?.multiply(1000.toBigDecimal())?.toLong() ?: 0L

    private fun newSecureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun resolveBaselineGitIdentity(): BaselineGitIdentity {
        val baselineCommitSha = runGit("rev-parse", BASELINE_TAG)
        val measuredCommitSha = runGit("rev-parse", "HEAD")
        val commitTimestamp = runGit("log", "-1", "--format=%aI", BASELINE_TAG)
        return BaselineGitIdentity(baselineCommitSha, measuredCommitSha, commitTimestamp)
    }

    private fun runGit(vararg arguments: String): String {
        try {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(rootProject.rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            if (process.waitFor() != 0 || output.isBlank()) {
                throw GradleException(
                    "Unable to resolve maintainability baseline Git identity with " +
                        "'git ${arguments.joinToString(" ")}': ${output.ifBlank { "no output" }}"
                )
            }
            return output.lineSequence().first()
        } catch (exception: GradleException) {
            throw exception
        } catch (exception: Exception) {
            throw GradleException(
                "Unable to run Git while resolving maintainability baseline identity: ${exception.message}",
                exception
            )
        }
    }

    private fun deterministicGeneratedAt(commitTimestamp: String): String {
        return System.getenv("SOURCE_DATE_EPOCH")
            ?.toLongOrNull()
            ?.let { Instant.ofEpochSecond(it).toString() }
            ?: commitTimestamp
    }

    private fun generateModuleDependencyGraphMarkdown(
        graph: ModuleGraphAnalyzer.GraphResult,
        mermaid: String
    ): String = buildString {
        appendLine("# TramAI 0.6.0 — Module Dependency Graph")
        appendLine()
        appendLine("> **Baseline:** `$BASELINE_TAG` (`${baselineGitIdentity.baselineCommitSha}`)")
        appendLine("> **Source:** `build/reports/maintainability/module-dependencies.json`")
        appendLine("> **Schema version:** 1")
        appendLine()
        appendLine("This document is generated as a complete unit by `generateModuleDependencyGraph`.")
        appendLine()
        appendLine("## Module Inventory")
        appendLine()
        appendLine("| Module | Gradle path | Layer | Publishable |")
        appendLine("|---|---|---|---:|")
        graph.modules.sortedBy { it.name }.forEach { module ->
            appendLine("| `${module.name}` | `${module.path}` | ${module.layer} | ${if (module.publishable) "yes" else "no"} |")
        }
        appendLine()
        appendLine("## Dependency Graph")
        appendLine()
        appendLine("```mermaid")
        append(mermaid.trimEnd())
        appendLine()
        appendLine("```")
        appendLine()
        appendLine("## Dependency Edges")
        appendLine()
        appendLine("| From | To | Scope |")
        appendLine("|---|---|---|")
        graph.moduleDependencies.edges
            .sortedWith(compareBy<DependencyEdge> { it.from }.thenBy { it.to }.thenBy { it.scope })
            .forEach { edge ->
                appendLine("| `${edge.from}` | `${edge.to}` | ${edge.scope} |")
            }
        appendLine()
        appendLine("## Known Cycles")
        appendLine()
        if (graph.moduleDependencies.cycles.isEmpty()) {
            appendLine("No dependency cycles were detected.")
        } else {
            graph.moduleDependencies.cycles.sortedBy { it.joinToString(" -> ") }.forEach { cycle ->
                appendLine("- `${cycle.joinToString(" -> ")}`")
            }
        }
        appendLine()
        appendLine("## Verification")
        appendLine()
        appendLine("Run `./gradlew verifyModuleDependencyGraph` to check the current graph.")
    }

    private fun collectKotlinFiles(sourceDir: File): List<File> {
        if (!sourceDir.exists()) return emptyList()
        return sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.absolutePath }
            .toList()
    }

    private fun scanDeclarationMetrics(
        module: String,
        file: File,
        path: String
    ): DeclarationMetrics {
        val content = file.readText(Charsets.UTF_8)
        val classSizes = mutableListOf<StructuralHotspot>()
        val functionsPerClass = mutableListOf<StructuralHotspot>()
        val declarationRegex = Regex(
            """\b(?:(?:data|sealed|open|abstract|value|enum|annotation)\s+)?(class|object)\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        val functionRegex = Regex("""\bfun\s+(?:<[^>{}]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Za-z_][A-Za-z0-9_]*)\s*\(""")

        declarationRegex.findAll(content).forEach { declaration ->
            val openingBrace = content.indexOf('{', declaration.range.last + 1)
            if (openingBrace < 0) return@forEach
            val closingBrace = findMatchingBrace(content, openingBrace)
            if (closingBrace <= openingBrace) return@forEach
            val body = content.substring(openingBrace + 1, closingBrace)
            val declarationName = declaration.groupValues[2]
            classSizes.add(
                StructuralHotspot(
                    module = module,
                    path = path,
                    declaration = declarationName,
                    metric = "classSize",
                    value = body.lineSequence().count { it.isNotBlank() }
                )
            )
            functionsPerClass.add(
                StructuralHotspot(
                    module = module,
                    path = path,
                    declaration = declarationName,
                    metric = "functionCount",
                    value = functionRegex.findAll(body).count()
                )
            )
        }
        return DeclarationMetrics(classSizes, functionsPerClass)
    }

    private fun scanFunctionParameters(
        module: String,
        file: File,
        path: String
    ): List<StructuralHotspot> {
        val content = file.readText(Charsets.UTF_8)
        val functionRegex = Regex(
            """\bfun\s+(?:<[^>{}]+>\s*)?(?:[A-Za-z_][A-Za-z0-9_]*\.)*([A-Za-z_][A-Za-z0-9_]*)\s*\("""
        )
        return functionRegex.findAll(content).map { match ->
            StructuralHotspot(
                module = module,
                path = path,
                declaration = match.groupValues[1],
                metric = "functionParameterCount",
                value = countParameters(content, match.range.last)
            )
        }.toList()
    }

    private fun findMatchingBrace(content: String, openingBrace: Int): Int {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in openingBrace until content.length) {
            val char = content[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                continue
            }
            when (char) {
                '"', '\'' -> quote = char
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun scanConstructorParameters(
        module: String,
        file: File,
        path: String
    ): List<StructuralHotspot> {
        val content = file.readText(Charsets.UTF_8)
        val findings = mutableListOf<StructuralHotspot>()
        val recordedOpenParentheses = mutableSetOf<Int>()
        val classRegex = Regex(
            """\b(?:data\s+|sealed\s+|open\s+|abstract\s+|value\s+|enum\s+|annotation\s+)?class\s+([A-Za-z_][A-Za-z0-9_]*)"""
        )
        val classMatches = classRegex.findAll(content).toList()

        classMatches.forEach { classMatch ->
            val className = classMatch.groupValues[1]
            val headerStart = classMatch.range.last + 1
            val headerEnd = content.indexOf('{', headerStart).let { if (it >= 0) it else content.length }
            val header = content.substring(headerStart, headerEnd)
            val explicitConstructor = Regex("""\bconstructor\s*\(""").find(header)
            val openParenthesis = if (explicitConstructor != null) {
                headerStart + explicitConstructor.range.last
            } else {
                header.indexOf('(')
                    .takeIf { it >= 0 }
                    ?.let { headerStart + it }
            }

            if (openParenthesis != null) {
                recordedOpenParentheses.add(openParenthesis)
                findings.add(
                    StructuralHotspot(
                        module = module,
                        path = path,
                        declaration = className,
                        metric = "constructorParameterCount",
                        value = countParameters(content, openParenthesis)
                    )
                )
            }
        }

        Regex("""\bconstructor\s*\(""").findAll(content).forEach { constructorMatch ->
            val openParenthesis = constructorMatch.range.last
            if (openParenthesis in recordedOpenParentheses) return@forEach
            val enclosingClass = classMatches.lastOrNull { it.range.first < constructorMatch.range.first }
                ?.groupValues
                ?.get(1)
                ?: file.nameWithoutExtension
            findings.add(
                StructuralHotspot(
                    module = module,
                    path = path,
                    declaration = "$enclosingClass.constructor",
                    metric = "constructorParameterCount",
                    value = countParameters(content, openParenthesis)
                )
            )
        }

        return findings
    }

    private fun countParameters(content: String, openParenthesis: Int): Int {
        var index = openParenthesis + 1
        var nesting = 0
        var parameterCount = 0
        var currentParameterHasContent = false
        var quote: Char? = null
        var escaped = false

        while (index < content.length) {
            val char = content[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                index++
                continue
            }

            when (char) {
                '"', '\'' -> quote = char
                '(', '[', '{', '<' -> nesting++
                ')' -> {
                    if (nesting == 0) {
                        return parameterCount + if (currentParameterHasContent) 1 else 0
                    }
                    nesting--
                }
                ']', '}', '>' -> if (nesting > 0) nesting--
                ',' -> if (nesting == 0 && currentParameterHasContent) {
                    parameterCount++
                    currentParameterHasContent = false
                }
                else -> if (!char.isWhitespace()) currentParameterHasContent = true
            }
            index++
        }
        return 0
    }

    private fun classifyProtocolEntry(symbol: String, value: String): String {
        val normalized = symbol.uppercase()
        return when {
            "ATTR" in normalized || "ATTRIBUTE" in normalized -> "attribute-key"
            "METRIC" in normalized -> "metric-name"
            "SPAN" in normalized -> "span-name"
            "EVENT" in normalized || ".event." in value -> "event-name"
            "REASON" in normalized || "CODE" in normalized -> "reason-code"
            else -> "runtime-protocol"
        }
    }

    private fun isWorkingTreeClean(): Boolean {
        return try {
            val process = ProcessBuilder(listOf("git", "status", "--porcelain"))
                .directory(rootProject.rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            process.waitFor() == 0 && output.isBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun computeSourceTreeHash(): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val sourceDirs = listOf("src/main/kotlin", "src/test/kotlin", "gradle/libs.versions.toml", "build.gradle.kts", "settings.gradle.kts")
            for (dir in sourceDirs) {
                val file = File(rootProject.rootDir, dir)
                if (file.isFile) {
                    digest.update(file.readBytes())
                } else if (file.isDirectory) {
                    file.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.absolutePath }.forEach {
                        digest.update(it.absolutePath.removePrefix(rootProject.rootDir.absolutePath).toByteArray())
                        digest.update(it.readBytes())
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private data class BaselineGitIdentity(
        val baselineCommitSha: String,
        val measuredCommitSha: String,
        val commitTimestamp: String
    )

    private data class DeclarationMetrics(
        val classSizes: List<StructuralHotspot>,
        val functionsPerClass: List<StructuralHotspot>
    )

    private companion object {
        const val BASELINE_TAG = "v0.5.0"
    }
}
