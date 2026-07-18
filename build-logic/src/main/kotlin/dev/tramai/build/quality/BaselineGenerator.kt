package dev.tramai.build.quality

import org.gradle.api.Project
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.io.File
import java.time.Instant

/**
 * Orchestrates all baseline generation steps and writes the unified baseline JSON.
 */
class BaselineGenerator(private val rootProject: Project) {

    private val baselineGitIdentity: BaselineGitIdentity by lazy {
        resolveBaselineGitIdentity()
    }

    private val outputDir: File
        get() = File(rootProject.buildDir, "reports/maintainability")

    fun generateFullBaseline(): BaselineDocument {
        val identity = generateIdentity()
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

    fun generateIdentity(): BaselineIdentity {
        return BaselineIdentity(
            repository = "GionaGranchelli/tramAI",
            releaseTag = "v0.5.0",
            commitSha = baselineGitIdentity.commitSha,
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
        ReportNormalizer.writeJson(graph, File(outputDir, "module-dependencies.json"))

        // Write DOT
        File(outputDir, "module-dependencies.dot").writeText(graphAnalyzer.generateDot(graph))

        // Write Mermaid to docs
        val mermaid = graphAnalyzer.generateMermaid(graph)
        val docsDir = File(rootProject.rootDir, "docs/architecture")
        docsDir.mkdirs()
        val mdFile = File(docsDir, "module-dependency-graph.md")
        mdFile.writeText(generateModuleDependencyGraphMarkdown(graph, mermaid))

        return StructuralBaseline(
            modules = graph.modules,
            dependencyGraph = DependencyGraphData(
                modules = graph.modules.map { it.name },
                edges = graph.edges,
                cycles = graph.cycles
            )
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
        val constructorParameters = mutableListOf<StructuralHotspot>()

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
                constructorParameters.addAll(scanConstructorParameters(proj.name, file, path))
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
            largestClasses = emptyList(),
            mostFunctions = emptyList(),
            longestFunctions = emptyList(),
            highestCyclomaticComplexity = emptyList(),
            highestCognitiveComplexity = emptyList(),
            mostConstructorParameters = constructorParameters
                .sortedWith(compareByDescending<StructuralHotspot> { it.value }.thenBy { it.path }.thenBy { it.declaration })
                .take(20),
            mostFunctionParameters = emptyList(),
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
            "description" to "Starter catalog of runtime protocol identifiers discovered in production Kotlin sources.",
            "entries" to catalog.entries
        )
        ReportNormalizer.writeJson(
            catalogDocument,
            File(rootProject.rootDir, "config/quality/runtime-protocol-catalog.json")
        )
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

    private fun resolveBaselineGitIdentity(): BaselineGitIdentity {
        val commitSha = runGit("rev-parse", BASELINE_TAG) ?: FALLBACK_BASELINE_SHA
        val commitTimestamp = runGit("log", "-1", "--format=%aI", BASELINE_TAG)
            ?: FALLBACK_BASELINE_TIMESTAMP
        return BaselineGitIdentity(commitSha, commitTimestamp)
    }

    private fun runGit(vararg arguments: String): String? {
        return try {
            val process = ProcessBuilder(listOf("git") + arguments)
                .directory(rootProject.rootDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
            if (process.waitFor() == 0) output.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } else null
        } catch (_: Exception) {
            null
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
        appendLine("> **Baseline:** `$BASELINE_TAG` (`${baselineGitIdentity.commitSha}`)")
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
        graph.edges
            .sortedWith(compareBy<DependencyEdge> { it.from }.thenBy { it.to }.thenBy { it.scope })
            .forEach { edge ->
                appendLine("| `${edge.from}` | `${edge.to}` | ${edge.scope} |")
            }
        appendLine()
        appendLine("## Known Cycles")
        appendLine()
        if (graph.cycles.isEmpty()) {
            appendLine("No dependency cycles were detected.")
        } else {
            graph.cycles.sortedBy { it.joinToString(" -> ") }.forEach { cycle ->
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

    private data class BaselineGitIdentity(
        val commitSha: String,
        val commitTimestamp: String
    )

    private companion object {
        const val BASELINE_TAG = "v0.5.0"
        const val FALLBACK_BASELINE_SHA = "494bc6856bae046d3e6f6c3611f4c8d7eb14b955"
        const val FALLBACK_BASELINE_TIMESTAMP = "2026-07-18T18:55:10+02:00"
    }
}
