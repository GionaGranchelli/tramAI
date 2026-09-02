package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Generates the TramAI 0.6.0 maintainability baseline.
 *
 * Two modes:
 * - **Normal (fromProject):** scans the active Gradle project.
 * - **Canonical (fromDirectory):** scans a detached v0.5.0 worktree via
 *   `generateCanonicalMaintainabilityBaseline`, producing an immutable
 *   reference baseline with verified provenance.
 */
class BaselineGenerator(
    private val ctx: MeasurementContext,
    outputDir: File = File(
        (ctx.gradleProject?.layout?.buildDirectory?.get()?.asFile ?: File(ctx.rootDir, "build")),
        "reports/maintainability"
    ),
    private val writeRepositoryArtifacts: Boolean = true
) {
    /** Convenience: the Gradle project, if available. */
    val rootProject: Project? get() = ctx.gradleProject

    /** Directory where reports are written. */
    private val reportOutputDir: File = outputDir

    /** When set, git commands for analyzer identity resolve from this directory (the PR branch). */
    private var analyzerRootDir: File? = null

    private val baselineGitIdentity: BaselineGitIdentity by lazy {
        resolveBaselineGitIdentity()
    }

    fun generateFullBaseline(): BaselineDocument {
        val identity = generateIdentity()
        if (identity.baselineCommitSha != identity.measuredCommitSha) {
            ctx.gradleProject?.logger?.warn(
                "WARNING: maintainability baseline is measured at ${identity.measuredCommitSha} " +
                    "but identifies release tag ${identity.releaseTag} at ${identity.baselineCommitSha}"
            )
        }
        return BaselineDocument(
            baselineIdentity = identity,
            generatedAt = deterministicGeneratedAt(identity.commitTimestamp),
            generatedBy = "generateMaintainabilityBaseline",
            environment = EnvironmentInfo(
                os = System.getProperty("os.name"),
                javaVersion = Runtime.version().feature().toString()
            )
        )
    }

    fun generateCompleteBaseline(
        apiOverride: ApiBaseline? = null,
        dependencyOverride: List<ResolvedDependency>? = null,
        coverageOverride: CoverageData? = null,
        mutationOverride: MutationData? = null,
        testPerformanceOverride: TestPerformanceData? = null
    ): BaselineDocument {
        val baseline = generateFullBaseline()
        val graphAnalyzer = ModuleGraphAnalyzer(ctx)
        val sourceMetricsAnalyzer = SourceMetricsAnalyzer(ctx)
        val cancellationInventory = CancellationCatchInventory(ctx)
        val globalStateInventory = GlobalStateInventory(ctx)
        val nondeterminismInventory = NondeterminismInventory(ctx)

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
                resolvedDependencies = dependencyOverride ?: generateResolvedDependencyGraph()
            ),
            protocolCatalog = generateRuntimeProtocolCatalog(),
            api = apiOverride ?: generateApiBaseline(),
            testQuality = TestQualityBaseline(
                testPerformance = testPerformanceOverride ?: generateTestPerformance(),
                coverage = coverageOverride ?: CoverageData(),
                mutation = mutationOverride ?: MutationData()
            )
        )
    }

    fun generateIdentity(): BaselineIdentity {
        val workingTreeClean = ctx.isWorkingTreeClean()
        val sourceTreeHash = if (workingTreeClean) computeSourceTreeHash() else ""
        val gitTreeSha = if (workingTreeClean) computeGitTreeSha() else ""
        val analyzerSha = computeAnalyzerCommitSha()
        val version = readVersionFromProps() ?: "0.5.0"
        return BaselineIdentity(
            repository = "GionaGranchelli/tramAI",
            releaseTag = "v0.5.0",
            commitSha = baselineGitIdentity.baselineCommitSha,
            baselineCommitSha = baselineGitIdentity.baselineCommitSha,
            measuredCommitSha = baselineGitIdentity.measuredCommitSha,
            measuredGitTreeSha = gitTreeSha,
            workingTreeClean = workingTreeClean,
            measuredSourceTreeHash = sourceTreeHash,
            analyzerCommitSha = analyzerSha,
            analyzerSchemaVersion = "1",
            commitTimestamp = baselineGitIdentity.commitTimestamp,
            tramaiVersion = version,
            toolchain = ToolchainInfo(
                gradle = ctx.gradleProject?.gradle?.gradleVersion ?: "unknown",
                kotlin = "2.3.0",
                jvmTarget = "21",
                ciJdk = "21"
            )
        )
    }

    private fun computeAnalyzerCommitSha(): String {
        // The analyzer runs from wherever Gradle was invoked (the PR branch).
        // Resolve HEAD of the project root — this is the commit containing MeasurementContext + scanners.
        return try {
            val project = ctx.gradleProject
            if (project != null) {
                val process = ProcessBuilder(listOf("git", "rev-parse", "HEAD"))
                    .directory(project.rootDir)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                    .lineSequence().firstOrNull() ?: ""
            } else {
                // Canonical mode: try the analyzerRootDir if set
                analyzerRootDir?.let { rootDir ->
                    val process = ProcessBuilder(listOf("git", "rev-parse", "HEAD"))
                        .directory(rootDir)
                        .redirectErrorStream(true)
                        .start()
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                        .lineSequence().firstOrNull() ?: ""
                } ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun readVersionFromProps(): String? {
        val propsFile = File(ctx.rootDir, "gradle.properties")
        if (!propsFile.isFile) return null
        return propsFile.readLines()
            .firstOrNull { it.trimStart().startsWith("tramaiVersion=") }
            ?.substringAfter("=")?.trim()
    }

    fun generateModuleDependencyGraph(graphAnalyzer: ModuleGraphAnalyzer): StructuralBaseline {
        val graph = graphAnalyzer.analyze()
        reportOutputDir.mkdirs()

        ReportNormalizer.writeJson(graph.moduleDependencies, File(reportOutputDir, "module-dependencies.json"))
        ReportNormalizer.writeJson(graph.moduleDependenciesTest, File(reportOutputDir, "module-dependencies-test.json"))
        File(reportOutputDir, "module-dependencies.dot").writeText(graphAnalyzer.generateDot(graph))

        if (writeRepositoryArtifacts) {
            val mermaid = graphAnalyzer.generateMermaid(graph)
            val docsDir = File(ctx.rootDir, "docs/architecture")
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

        ReportNormalizer.writeJson(result, File(reportOutputDir, "source-metrics.json"))
        return result
    }

    fun generateStructuralHotspots(): StructuralHotspots {
        val productionFileSizes = mutableListOf<StructuralHotspot>()
        val testFileSizes = mutableListOf<StructuralHotspot>()
        val buildFileSizes = mutableListOf<StructuralHotspot>()
        val classSizes = mutableListOf<StructuralHotspot>()
        val functionsPerClass = mutableListOf<StructuralHotspot>()
        val constructorParameters = mutableListOf<StructuralHotspot>()
        val functionParameters = mutableListOf<StructuralHotspot>()

        // Root build file
        val rootBuildFile = File(ctx.rootDir, "build.gradle.kts")
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

        // Settings and other root files
        for (rootFile in listOf("settings.gradle.kts", "gradle/libs.versions.toml")) {
            val f = File(ctx.rootDir, rootFile)
            if (f.isFile) {
                buildFileSizes.add(
                    StructuralHotspot(
                        module = ":",
                        path = rootFile,
                        declaration = rootFile.removeSuffix(".kts").removeSuffix(".toml"),
                        metric = "fileSize",
                        value = ReportNormalizer.countNonBlankLines(f)
                    )
                )
            }
        }

        for (mod in ctx.modules) {
            mod.sourceDirs.forEach { srcDir ->
                collectKotlinFiles(srcDir).forEach { file ->
                    val path = ReportNormalizer.repoRelativePath(file, ctx.rootDir)
                    productionFileSizes.add(
                        StructuralHotspot(
                            module = mod.name,
                            path = path,
                            declaration = file.nameWithoutExtension,
                            metric = "fileSize",
                            value = ReportNormalizer.countNonBlankLines(file)
                        )
                    )
                    val declarationMetrics = scanDeclarationMetrics(mod.name, file, path)
                    classSizes.addAll(declarationMetrics.classSizes)
                    functionsPerClass.addAll(declarationMetrics.functionsPerClass)
                    constructorParameters.addAll(scanConstructorParameters(mod.name, file, path))
                    functionParameters.addAll(scanFunctionParameters(mod.name, file, path))
                }
            }

            mod.testSourceDirs.forEach { testDir ->
                collectKotlinFiles(testDir).forEach { file ->
                    testFileSizes.add(
                        StructuralHotspot(
                            module = mod.name,
                            path = ReportNormalizer.repoRelativePath(file, ctx.rootDir),
                            declaration = file.nameWithoutExtension,
                            metric = "fileSize",
                            value = ReportNormalizer.countNonBlankLines(file)
                        )
                    )
                }
            }

            // Module build files
            if (mod.buildFile.isFile && mod.buildFile != rootBuildFile) {
                buildFileSizes.add(
                    StructuralHotspot(
                        module = mod.name,
                        path = ReportNormalizer.repoRelativePath(mod.buildFile, ctx.rootDir),
                        declaration = "${mod.name} build",
                        metric = "fileSize",
                        value = ReportNormalizer.countNonBlankLines(mod.buildFile)
                    )
                )
            }
        }

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

        ReportNormalizer.writeJson(result, File(reportOutputDir, "structural-hotspots.json"))
        return result
    }

    fun generateRuntimeProtocolCatalog(): ProtocolCatalog {
        val entries = mutableListOf<ProtocolEntry>()
        val namedProtocolRegex = Regex(
            """(?:const\s+val|val)\s+([A-Z][A-Z0-9_]*)\s*(?::[^=]+)?=\s*"((?:tramai\.)[^"]+)""""
        )
        val namedArgumentRegex = Regex(
            """\b(name|eventName|metricName|spanName|attributeKey)\s*=\s*"(tramai\.[A-Za-z0-9_.:-]+)""""
        )
        val exceptionConstantRegex = Regex(
            """(?:const\s+val|val)\s+([A-Z][A-Z0-9_]*(?:MESSAGE|REASON|CODE)[A-Z0-9_]*)\s*(?::[^=]+)?=\s*"([^"]+)""""
        )

        for (mod in ctx.modules) {
            mod.sourceDirs.forEach { srcDir ->
                collectKotlinFiles(srcDir).forEach { file ->
                    val source = ReportNormalizer.repoRelativePath(file, ctx.rootDir)
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
                        val value = match.groupValues[2]
                        entries.add(
                            ProtocolEntry(
                                category = classifyProtocolEntry(match.groupValues[1], value),
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
                File(ctx.rootDir, "config/quality/runtime-protocol-catalog.json")
            )
        }
        ReportNormalizer.writeJson(catalog, File(reportOutputDir, "runtime-protocol-catalog.json"))
        return catalog
    }

    fun generateApiBaseline(probedRecords: List<ApiDumpRecord>? = null): ApiBaseline {
        val probedByModule = probedRecords.orEmpty().associateBy { it.module }
        val records = ctx.modules
            .sortedBy { it.path }
            .map { module ->
                val stability = module.apiStability
                val relativeModuleDir = ReportNormalizer.repoRelativePath(module.projectDir, ctx.rootDir)
                val dumpPath = "$relativeModuleDir/api/${module.name}.api"
                val hasProductionSources = module.sourceDirs.any { sourceDir ->
                    sourceDir.isDirectory && sourceDir.walkTopDown().any {
                        it.isFile && (it.extension == "kt" || it.extension == "java")
                    }
                }
                val exclusionReason = when {
                    stability == "excluded" -> "module has apiStability 'excluded'"
                    !hasProductionSources -> "module has no Kotlin or Java production sources"
                    else -> null
                }
                val applicable = exclusionReason == null
                val probed = probedByModule[module.path]
                val dumpFile = File(ctx.rootDir, dumpPath)
                val hash = when {
                    !applicable -> ""
                    probed != null -> probed.sha256
                    dumpFile.isFile -> sha256(dumpFile.readBytes())
                    else -> ""
                }

                ApiDumpRecord(
                    module = module.path,
                    stability = stability,
                    applicable = applicable,
                    dumpPath = dumpPath,
                    sha256 = hash,
                    exclusionReason = exclusionReason
                )
            }
        val aggregateHash = sha256(ReportNormalizer.toJson(records).toByteArray(Charsets.UTF_8))
        val result = ApiBaseline(modules = records, aggregateHash = aggregateHash)
        ReportNormalizer.writeJson(records, File(reportOutputDir, "public-api-dumps.json"))
        return result
    }

    fun generateTestPerformance(): TestPerformanceData {
        val observationsFile = File(reportOutputDir, "test-performance-observations.json")
        if (!observationsFile.isFile) return TestPerformanceData()
        val observations = try {
            ReportNormalizer.readJson(
                observationsFile,
                Array<TestPerformanceObservation>::class.java
            ).toList()
        } catch (e: Exception) {
            throw GradleException("Failed to read test-performance observations: ${e.message}", e)
        }
        return TestPerformanceAggregator().aggregate(observations).also {
            ReportNormalizer.writeJson(it, File(reportOutputDir, "test-performance.json"))
        }
    }

    fun generateCoverageBaseline(
        configuration: TestQualityConfiguration,
        reportRoot: File? = null
    ): CoverageData =
        CoverageCollector(ctx.rootDir, configuration).collect(reportRoot).also {
            ReportNormalizer.writeJson(it, File(reportOutputDir, "coverage.json"))
        }

    fun generateMutationBaseline(
        configuration: TestQualityConfiguration,
        reportRoot: File? = null
    ): MutationData {
        val parser = MutationReportParser()
        val reports = configuration.mutation.targetFamilies.flatMap { (family, target) ->
            target.modules.map { module ->
                val moduleSlug = module.removePrefix(":").replace(":", "_")
                val candidates = if (reportRoot != null) {
                    // P2 (10.3c1 review): mutation summary input is XML-only
                    // too — HTML cannot carry identity v2 fields.
                    listOf(
                        File(reportRoot, "$family/$moduleSlug/mutations.xml")
                    )
                } else {
                    val moduleDir = File(ctx.rootDir, module.removePrefix(":").replace(":", "/"))
                    listOf(
                        File(moduleDir, "build/reports/pitest/$family/mutations.xml"),
                        File(moduleDir, "build/reports/pitest/mutations.xml")
                    )
                }
                val report = candidates.firstOrNull { it.isFile }
                    ?: throw GradleException(
                        "No mutation report for configured target $family/$module; expected one of " +
                            candidates.joinToString()
                    )
                parser.parse(module, family, report)
            }
        }
        return MutationBaselineVerifier.aggregate(
            reports = reports,
            analyzerVersion = "pitest",
            measuredCommit = try {
                ctx.runGit("rev-parse", "HEAD")
            } catch (_: Exception) {
                ""
            }
        ).also {
            ReportNormalizer.writeJson(it, File(reportOutputDir, "mutation.json"))
        }
    }

    fun updateBaselineJson(baseline: BaselineDocument) {
        val baselineFile = File(ctx.rootDir, "config/quality/0.6.0-baseline.json")
        ReportNormalizer.writeJson(baseline, baselineFile)
    }

    fun generateResolvedDependencyGraph(): List<ResolvedDependency> {
        // First try to read from the file generated by GenerateResolvedDependencyBaselineTask
        if (ctx.gradleProject != null) {
            val generatedFile = File(
                ctx.gradleProject.layout.buildDirectory.get().asFile,
                "reports/maintainability/resolved-dependencies.json"
            )
            if (generatedFile.isFile) {
                try {
                    val records = ReportNormalizer.readJson(generatedFile, Array<ResolvedDependency>::class.java).toList()
                    return sortResolvedDependencies(records)
                } catch (e: Exception) {
                    throw GradleException("Failed to read pre-generated dependency file: ${e.message}", e)
                }
            } else {
                throw GradleException(
                    "Dependency baseline file not found at ${generatedFile.absolutePath}. " +
                    "Run generateResolvedDependencyBaseline first."
                )
            }
        }
        // Directory mode fallback
        System.err.println("[WARN] Resolved dependency graph unavailable in directory mode")
        return emptyList()
    }

    // ─── Private helpers ───

    private fun collectKotlinFiles(sourceDir: File): List<File> {
        if (!sourceDir.exists()) return emptyList()
        return sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.absolutePath }
            .toList()
    }

    private fun scanDeclarationMetrics(
        module: String, file: File, relativePath: String
    ): DeclarationMetrics {
        val content = file.readText()
        val classSizes = mutableListOf<StructuralHotspot>()
        val functionsPerClass = mutableListOf<StructuralHotspot>()

        // Simple regex-based class/function counting
        val classRegex = Regex("""^\s*(?:data\s+)?(?:sealed\s+)?(?:abstract\s+)?(?:open\s+)?class\s+(\w+)""", RegexOption.MULTILINE)
        val funRegex = Regex("""^\s*(?:suspend\s+)?(?:override\s+)?fun\s+(\w+)""", RegexOption.MULTILINE)

        val classMatches = classRegex.findAll(content).toList()
        val funMatches = funRegex.findAll(content).toList()

        for (classMatch in classMatches) {
            val className = classMatch.groupValues[1]
            val classStart = classMatch.range.first
            val nextClassStart = classMatches.firstOrNull { it.range.first > classStart }?.range?.first ?: content.length
            val classBody = content.substring(classStart, nextClassStart)
            val funCount = funRegex.findAll(classBody).count()

            classSizes.add(
                StructuralHotspot(
                    module = module, path = relativePath, declaration = className,
                    metric = "classSize", value = classBody.count { it == '\n' }
                )
            )
            functionsPerClass.add(
                StructuralHotspot(
                    module = module, path = relativePath, declaration = className,
                    metric = "functionCount", value = funCount
                )
            )
        }

        return DeclarationMetrics(classSizes, functionsPerClass)
    }

    private fun scanConstructorParameters(
        module: String, file: File, relativePath: String
    ): List<StructuralHotspot> {
        val content = file.readText()
        val results = mutableListOf<StructuralHotspot>()
        val constructorRegex = Regex(
            """^\s*(?:data\s+)?(?:sealed\s+)?(?:abstract\s+)?(?:open\s+)?class\s+(\w+)\s*\(([^)]*)\)""",
            RegexOption.MULTILINE
        )
        constructorRegex.findAll(content).forEach { match ->
            val className = match.groupValues[1]
            val params = match.groupValues[2]
            val paramCount = if (params.isBlank()) 0 else params.split(",").size
            if (paramCount > 0) {
                results.add(
                    StructuralHotspot(
                        module = module, path = relativePath, declaration = className,
                        metric = "constructorParameterCount", value = paramCount
                    )
                )
            }
        }
        return results
    }

    private fun scanFunctionParameters(
        module: String, file: File, relativePath: String
    ): List<StructuralHotspot> {
        val content = file.readText()
        val results = mutableListOf<StructuralHotspot>()
        val funRegex = Regex(
            """^\s*(?:suspend\s+)?(?:override\s+)?fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(([^)]*)\)""",
            RegexOption.MULTILINE
        )
        funRegex.findAll(content).forEach { match ->
            val funName = match.groupValues[1]
            val params = match.groupValues[2]
            val paramCount = if (params.isBlank()) 0 else params.split(",").size
            if (paramCount > 2) {
                results.add(
                    StructuralHotspot(
                        module = module, path = relativePath, declaration = funName,
                        metric = "functionParameterCount", value = paramCount
                    )
                )
            }
        }
        return results
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

    private fun computeSourceTreeHash(): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            for (mod in ctx.modules) {
                for (srcDir in mod.sourceDirs + mod.testSourceDirs + mod.testFixtureDirs) {
                    if (srcDir.isDirectory) {
                        srcDir.walkTopDown()
                            .filter { it.isFile && it.extension in listOf("kt", "java") }
                            .sortedBy { it.absolutePath }
                            .forEach {
                                digest.update(it.absolutePath.removePrefix(ctx.rootDir.absolutePath).toByteArray())
                                digest.update(it.readBytes())
                            }
                    }
                }
                if (mod.buildFile.isFile) {
                    digest.update(mod.buildFile.absolutePath.removePrefix(ctx.rootDir.absolutePath).toByteArray())
                    digest.update(mod.buildFile.readBytes())
                }
            }
            for (rootFile in listOf("build.gradle.kts", "settings.gradle.kts", "gradle/libs.versions.toml")) {
                val f = File(ctx.rootDir, rootFile)
                if (f.isFile) {
                    digest.update(rootFile.toByteArray())
                    digest.update(f.readBytes())
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    private fun computeGitTreeSha(): String {
        return try {
            ctx.runGit("rev-parse", "HEAD^{tree}")
        } catch (_: Exception) {
            ""
        }
    }

    private fun resolveBaselineGitIdentity(): BaselineGitIdentity {
        val baselineCommitSha = ctx.runGit("rev-parse", "$BASELINE_TAG^{commit}")
        val measuredCommitSha = ctx.runGit("rev-parse", "HEAD")
        val commitTimestamp = ctx.runGit("log", "-1", "--format=%aI", "$BASELINE_TAG^{commit}")
        return BaselineGitIdentity(baselineCommitSha, measuredCommitSha, commitTimestamp)
    }

    private fun deterministicGeneratedAt(commitTimestamp: String): String {
        return System.getenv("SOURCE_DATE_EPOCH")
            ?.toLongOrNull()
            ?.let { Instant.ofEpochSecond(it).toString() }
            ?: commitTimestamp
    }

    private fun generateModuleDependencyGraphMarkdown(
        graph: ModuleGraphAnalyzer.GraphResult, mermaid: String
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class BaselineGitIdentity(
        val baselineCommitSha: String,
        val measuredCommitSha: String,
        val commitTimestamp: String
    )

    private data class DeclarationMetrics(
        val classSizes: List<StructuralHotspot>,
        val functionsPerClass: List<StructuralHotspot>
    )

    companion object {
        const val BASELINE_TAG = "v0.5.0"

        fun sortResolvedDependencies(records: List<ResolvedDependency>): List<ResolvedDependency> =
            records.sortedWith(
                compareBy<ResolvedDependency> { it.consumers.joinToString(",") }
                    .thenBy { it.configuration }
                    .thenBy { it.group }
                    .thenBy { it.artifact }
                    .thenBy { it.selectedVersion }
                    .thenBy { it.dependencyPath.joinToString(" -> ") }
            )

        /** Create from a Gradle project (normal/CI mode). */
        fun fromProject(project: Project): BaselineGenerator {
            val ctx = MeasurementContext.fromProject(project)
            return BaselineGenerator(ctx)
        }

        /** Create from a detached worktree directory (canonical mode). */
        fun fromDirectory(rootDir: File, outputDir: File? = null, analyzerRoot: File? = null): BaselineGenerator {
            val catalogRoot = analyzerRoot ?: rootDir
            val ctx = MeasurementContext.fromDirectory(rootDir, catalogRoot)
            val gen = BaselineGenerator(
                ctx = ctx,
                outputDir = outputDir ?: File(rootDir, "build/reports/maintainability"),
                writeRepositoryArtifacts = false
            )
            gen.analyzerRootDir = analyzerRoot
            return gen
        }
    }
}
