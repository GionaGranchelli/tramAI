package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * Compares the currently generated baseline against the committed baseline.
 * Uses stable FindingIdentity, typed VerificationDiagnostic, ModuleCatalog,
 * and ModuleBoundaries for all checks.
 */
class BaselineVerifier(
    private val generator: BaselineGenerator,
    private val ctx: MeasurementContext,
    private val reportDir: File
) {
    private val deviationParser = DeviationParser(ctx.rootDir)
    private val moduleCatalog = ModuleCatalog(ctx.rootDir)
    private val moduleBoundaries = ModuleBoundaries(ctx.rootDir)

    fun verify(): VerificationReport {
        val diagnostics = mutableListOf<VerificationDiagnostic>()

        // 1. Load committed baseline
        val committedFile = File(ctx.rootDir, "config/quality/0.6.0-baseline.json")
        if (!committedFile.isFile) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION,
                "Committed baseline not found: ${committedFile.absolutePath}"))
            return diagnosticsToReport(diagnostics)
        }

        val committed: BaselineDocument = try {
            ReportNormalizer.readJson(committedFile, BaselineDocument::class.java)
        } catch (e: Exception) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION,
                "Failed to read committed baseline: ${e.message}"))
            return diagnosticsToReport(diagnostics)
        }

        // 2. Parse deviations with typed diagnostics
        val deviationResult = deviationParser.parse()
        diagnostics.addAll(deviationResult.diagnostics)

        // 3. Parse module catalog
        val catalogResult = moduleCatalog.parse()
        diagnostics.addAll(catalogResult.errors.map {
            VerificationDiagnostic.failure(DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY, it)
        })

        // 4. Load boundary rules
        val boundaryResult = moduleBoundaries.parse()
        diagnostics.addAll(boundaryResult.errors.map {
            VerificationDiagnostic.failure(DiagnosticCode.FORBIDDEN_LAYER_EDGE, it)
        })

        // 5. Parse boundaries once for efficient checks
        ModuleBoundaries.loadOnce(moduleBoundaries)

        // 6. Verify baseline identity
        verifyBaselineIdentity(committed, diagnostics)

        // 7. Verify module catalogue covers all projects
        verifyModuleCatalog(committed, catalogResult, diagnostics)

        // 8. Generate current measurements
        val currentGradle = ctx.gradleProject
        if (currentGradle == null) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION,
                "Current baseline generation requires Gradle project mode"))
            return diagnosticsToReport(diagnostics)
        }

        val currentCtx = MeasurementContext.fromProject(currentGradle)
        val tempGenerator = BaselineGenerator(
            ctx = currentCtx,
            outputDir = reportDir,
            writeRepositoryArtifacts = false
        )

        val current: BaselineDocument = try {
            tempGenerator.generateCompleteBaseline()
        } catch (e: Exception) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION,
                "Failed to generate current baseline: ${e.message}"))
            return diagnosticsToReport(diagnostics)
        }

        // 9. Verify mandatory sections
        verifyMandatorySections(current, diagnostics)

        // 10. Compare dimensions with FindingIdentity
        verifyCancellationCatches(committed, current, deviationResult.deviations, diagnostics)
        verifyGlobalState(committed, current, deviationResult.deviations, diagnostics)
        verifyNondeterminism(committed, current, deviationResult.deviations, diagnostics)
        verifyDependencyCycles(committed, current, deviationResult.deviations, diagnostics)
        verifyProtocolCatalog(committed, current, diagnostics)
        verifyStructuralHotspots(committed, current, deviationResult.deviations, diagnostics)
        verifyForbiddenEdges(committed, current, diagnostics)
        verifyDocumentDrift(committed, diagnostics)

        // 11. Write typed verification report
        writeVerificationReport(diagnostics)

        return diagnosticsToReport(diagnostics)
    }

    // ─── Identity verification ───

    private fun verifyBaselineIdentity(
        committed: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val id = committed.baselineIdentity

        if (id.baselineCommitSha.isBlank()) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                "Committed baseline has empty baselineCommitSha"))
        }
        if (id.tramaiVersion == "unspecified" || id.tramaiVersion.isBlank()) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                "Committed baseline has invalid tramaiVersion: '${id.tramaiVersion}'"))
        }

        // Version match
        val propsFile = File(ctx.rootDir, "gradle.properties")
        val propsVersion = if (propsFile.isFile) {
            propsFile.readLines().firstOrNull { it.trimStart().startsWith("tramaiVersion=") }
                ?.substringAfter("=")?.trim()
        } else null
        if (propsVersion != null && id.tramaiVersion != propsVersion) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                "Committed baseline version '${id.tramaiVersion}' does not match gradle.properties '$propsVersion'"))
        }

        if (!id.workingTreeClean) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.DIRTY_WORKTREE,
                "Committed baseline was generated from a dirty worktree"))
        }
        if (id.measuredSourceTreeHash.isBlank()) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                "Committed baseline has empty measuredSourceTreeHash"))
        }
        if (id.measuredGitTreeSha.isBlank()) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                "Committed baseline has empty measuredGitTreeSha"))
        }
        if (id.analyzerCommitSha.isBlank()) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                "Committed baseline has empty analyzerCommitSha"))
        }

        // Tag/commit/tree verification with typed diagnostics
        if (id.baselineCommitSha.isNotBlank() && id.measuredCommitSha.isNotBlank() &&
            id.baselineCommitSha != id.measuredCommitSha
        ) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.TAG_COMMIT_MISMATCH,
                "Baseline provenance mismatch: measuredCommitSha (${id.measuredCommitSha.take(8)}) " +
                    "differs from baselineCommitSha (${id.baselineCommitSha.take(8)})"))
        }

        // Independent tree SHA verification
        if (id.measuredGitTreeSha.isNotBlank() && id.measuredCommitSha.isNotBlank()) {
            val recomputedTreeSha = resolveRefOrFail("${id.measuredCommitSha}^{tree}", diagnostics)
            if (recomputedTreeSha != null && recomputedTreeSha != id.measuredGitTreeSha) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MEASURED_TREE_MISMATCH,
                    "measuredGitTreeSha (${id.measuredGitTreeSha.take(8)}) does not match " +
                        "independently computed tree SHA (${recomputedTreeSha.take(8)})"))
            }
        }

        // Tag resolves correctly
        if (id.baselineCommitSha.isNotBlank()) {
            val tagCommit = resolveRefOrFail("${id.releaseTag}^{commit}", diagnostics)
            if (tagCommit != null && tagCommit != id.baselineCommitSha) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.TAG_COMMIT_MISMATCH,
                    "Release tag ${id.releaseTag} resolves to ${tagCommit.take(8)}, " +
                        "but baseline claims ${id.baselineCommitSha.take(8)}"))
            }
            if (id.measuredGitTreeSha.isNotBlank()) {
                val tagTree = resolveRefOrFail("${id.releaseTag}^{tree}", diagnostics)
                if (tagTree != null && tagTree != id.measuredGitTreeSha) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.TAG_TREE_MISMATCH,
                        "Release tag ${id.releaseTag} tree is ${tagTree.take(8)}, " +
                            "but baseline records ${id.measuredGitTreeSha.take(8)}"))
                }
            }
        }

        // Analyzer ancestor check
        if (id.analyzerCommitSha.isNotBlank()) {
            val analyzerExists = resolveRefOrFail("${id.analyzerCommitSha}^{commit}", diagnostics)
            if (analyzerExists != null) {
                val isAncestor = try {
                    val process = ProcessBuilder(
                        listOf("git", "merge-base", "--is-ancestor", id.analyzerCommitSha, "HEAD")
                    ).directory(ctx.rootDir).redirectErrorStream(true).start()
                    process.waitFor() == 0
                } catch (_: Exception) { false }

                if (!isAncestor) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.ANALYZER_COMMIT_NOT_ANCESTOR,
                        "analyzerCommitSha (${id.analyzerCommitSha.take(8)}) is not an ancestor of HEAD"))
                }
            }
        }
    }

    private fun resolveRefOrFail(ref: String, diagnostics: MutableList<VerificationDiagnostic>): String? {
        return try {
            val result = ctx.runGit("rev-parse", ref)
            if (result.isBlank()) null else result
        } catch (e: Exception) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.BASELINE_IDENTITY_MISMATCH,
                "Unable to resolve $ref: ${e.message}"))
            null
        }
    }

    // ─── Module catalogue verification ───

    private fun verifyModuleCatalog(
        committed: BaselineDocument,
        catalogResult: ModuleCatalog.CatalogResult,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val projectPaths = committed.structural.moduleDependencies.modules.toList()
        val catalogModules = catalogResult.modules

        // Missing modules in catalogue
        for (projPath in projectPaths) {
            if (projPath !in catalogModules) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_MISSING_ENTRY,
                    "Gradle project '$projPath' has no module-catalog entry"))
            }
        }

        // Unknown modules in catalogue
        for (catPath in catalogModules.keys) {
            if (catPath !in projectPaths) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_UNKNOWN_ENTRY,
                    "Module-catalog entry '$catPath' does not exist as a Gradle project"))
            }
        }

        // Verify catalogue classifications match baseline
        for (mod in committed.structural.modules) {
            val catalogEntry = catalogModules[mod.path] ?: continue
            if (catalogEntry.layer != mod.layer) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_DISAGREEMENT,
                    "Module '${mod.path}': catalogue declares layer '${catalogEntry.layer}' but " +
                        "baseline has '${mod.layer}'"))
            }

            val expectedPublished = catalogEntry.publishability == "published"
            if (expectedPublished != mod.publishable) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_DISAGREEMENT,
                    "Module '${mod.path}': catalogue declares publishability '${catalogEntry.publishability}' " +
                        "but baseline has '${if (mod.publishable) "published" else "not published"}'"))
            }
        }
    }

    // ─── Mandatory sections ───

    private fun verifyMandatorySections(
        current: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        if (current.structural.moduleDependencies.modules.isEmpty()) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION, "Current baseline has empty module list"))
        }
        if (current.runtimeSafety.cancellationCatches.isEmpty() &&
            current.runtimeSafety.testCancellationCatches.isEmpty()
        ) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION, "Current baseline has empty cancellation catch inventory"))
        }
        if (current.structural.sourceMetrics.byModule.isEmpty()) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION, "Current baseline has empty source metrics"))
        }
        if (current.protocolCatalog.entries.isEmpty()) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION, "Current baseline has empty protocol catalog"))
        }
        if (current.dependencies.resolvedDependencies.isEmpty()) {
            diagnostics.add(VerificationDiagnostic.warning(
                DiagnosticCode.EMPTY_SECTION, "Current baseline has empty resolved dependencies"))
        }
    }

    // ─── Finding-level comparison with FindingIdentity ───

    private fun verifyCancellationCatches(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val committedIds = committed.runtimeSafety.cancellationCatches.map {
            FindingIdentity.fromCancellationCatch(it).toIdentityKey()
        }.toSet()
        val currentIds = current.runtimeSafety.cancellationCatches.map {
            FindingIdentity.fromCancellationCatch(it).toIdentityKey()
        }.toSet()

        val added = currentIds - committedIds
        val addedCritical = current.runtimeSafety.cancellationCatches
            .filter { it.risk == "critical" && FindingIdentity.fromCancellationCatch(it).toIdentityKey() in added }

        if (addedCritical.isNotEmpty()) {
            val currentCriticalCount = current.runtimeSafety.cancellationCatches.count { it.risk == "critical" }
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "cancellationCriticalCount", "tramai-*", currentCriticalCount
            )
            if (deviation != null) {
                diagnostics.add(VerificationDiagnostic.accepted(
                    DiagnosticCode.NEW_CANCELLATION_FINDING,
                    "${addedCritical.size} new critical catches — accepted by ${deviation.id}",
                    deviation.id))
            } else {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.NEW_CANCELLATION_FINDING,
                    "${addedCritical.size} new critical cancellation catch(es) detected",
                    modulePath = addedCritical.firstOrNull()?.module))
            }
        }

        // Risk worsening with typed diagnostic
        val committedByRisk = committed.runtimeSafety.cancellationCatches
            .associate { FindingIdentity.fromCancellationCatch(it).toIdentityKey() to it.risk }
        val worsenedRisks = current.runtimeSafety.cancellationCatches.filter { f ->
            val id = FindingIdentity.fromCancellationCatch(f).toIdentityKey()
            val committedRisk = committedByRisk[id]
            committedRisk != null && riskOrder(committedRisk) < riskOrder(f.risk)
        }

        if (worsenedRisks.isNotEmpty()) {
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "cancellationRiskWorsening", "tramai-*", worsenedRisks.size
            )
            if (deviation != null) {
                diagnostics.add(VerificationDiagnostic.accepted(
                    DiagnosticCode.CANCELLATION_RISK_WORSENED,
                    "${worsenedRisks.size} risk worsenings — accepted by ${deviation.id}",
                    deviation.id))
            } else {
                worsenedRisks.forEach { f ->
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.CANCELLATION_RISK_WORSENED,
                        "Cancellation catch risk worsened: ${f.module}/${f.file}:${f.function}",
                        modulePath = f.module))
                }
            }
        }

        val removed = committedIds - currentIds
        if (removed.isNotEmpty()) {
            diagnostics.add(VerificationDiagnostic.improvement(
                DiagnosticCode.NEW_CANCELLATION_FINDING,
                "${removed.size} cancellation catch(es) resolved"))
        }
    }

    private fun verifyGlobalState(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val committedIds = committed.runtimeSafety.globalState.map {
            FindingIdentity.fromGlobalState(it).toIdentityKey()
        }.toSet()
        val currentIds = current.runtimeSafety.globalState.map {
            FindingIdentity.fromGlobalState(it).toIdentityKey()
        }.toSet()

        val added = currentIds - committedIds
        if (added.isNotEmpty()) {
            val currentCount = current.runtimeSafety.globalState.size
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "globalMutableState", "*", currentCount
            )
            if (deviation != null) {
                diagnostics.add(VerificationDiagnostic.accepted(
                    DiagnosticCode.NEW_GLOBAL_STATE_FINDING,
                    "${added.size} new global state instance(s) — accepted by ${deviation.id}",
                    deviation.id))
            } else {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.NEW_GLOBAL_STATE_FINDING,
                    "${added.size} new global mutable state instance(s) detected"))
            }
        }
    }

    private fun verifyNondeterminism(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val committedIds = committed.runtimeSafety.nondeterminism.map {
            FindingIdentity.fromNondeterminism(it).toIdentityKey()
        }.toSet()
        val currentIds = current.runtimeSafety.nondeterminism.map {
            FindingIdentity.fromNondeterminism(it).toIdentityKey()
        }.toSet()

        val added = currentIds - committedIds
        if (added.isNotEmpty()) {
            val currentCount = current.runtimeSafety.nondeterminism.size
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "nondeterminismSources", "*", currentCount
            )
            if (deviation != null) {
                diagnostics.add(VerificationDiagnostic.accepted(
                    DiagnosticCode.NEW_NONDETERMINISM_FINDING,
                    "${added.size} new nondeterminism source(s) — accepted by ${deviation.id}",
                    deviation.id))
            } else {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.NEW_NONDETERMINISM_FINDING,
                    "${added.size} new nondeterminism source(s) detected"))
            }
        }
    }

    private fun verifyDependencyCycles(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val committedCycles = committed.structural.moduleDependencies.cycles.map { it.sorted().joinToString("→") }.toSet()
        val currentCycles = current.structural.moduleDependencies.cycles.map { it.sorted().joinToString("→") }.toSet()

        val newCycles = currentCycles - committedCycles
        for (cycle in newCycles) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.NEW_DEPENDENCY_CYCLE,
                "New dependency cycle detected: $cycle"))
        }

        val removedCycles = committedCycles - currentCycles
        if (removedCycles.isNotEmpty()) {
            diagnostics.add(VerificationDiagnostic.improvement(
                DiagnosticCode.NEW_DEPENDENCY_CYCLE,
                "Dependency cycles resolved: ${removedCycles.joinToString(", ")}"))
        }
    }

    private fun verifyForbiddenEdges(
        committed: BaselineDocument,
        current: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        // Check edges from current Gradle mode (which has dependency resolution)
        for (edge in current.structural.moduleDependencies.edges) {
            val result = moduleBoundaries.checkEdge(edge.from, edge.to, moduleCatalog)
            if (result != null) {
                diagnostics.add(result)
            }
        }
    }

    private fun verifyProtocolCatalog(
        committed: BaselineDocument,
        current: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val committedByKey = committed.protocolCatalog.entries.associateBy {
            "${it.category}::${it.name}::${it.value}::${it.source}"
        }
        val currentByKey = current.protocolCatalog.entries.associateBy {
            "${it.category}::${it.name}::${it.value}::${it.source}"
        }

        for ((key, entry) in committedByKey) {
            if (entry.stability == "stable-contract" && key !in currentByKey) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.STABLE_PROTOCOL_CONTRACT_REMOVED,
                    "Stable protocol contract removed: ${entry.name} (${entry.category})"))
            }
        }

        val committedUnclassified = committed.protocolCatalog.entries.count { it.stability == "unclassified" }
        val currentUnclassified = current.protocolCatalog.entries.count { it.stability == "unclassified" }
        if (currentUnclassified > committedUnclassified) {
            diagnostics.add(VerificationDiagnostic.warning(
                DiagnosticCode.STABLE_PROTOCOL_CONTRACT_REMOVED,
                "Unclassified protocol entries increased: $committedUnclassified → $currentUnclassified"))
        }
    }

    private fun verifyStructuralHotspots(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        for (dev in deviations) {
            val parsedScope = deviationParser.parseScope(dev.scope) ?: continue

            when (dev.metric) {
                "constructorParameterCount" -> {
                    val matchedHotspots = current.structural.structuralHotspots.mostConstructorParameters
                        .filter { h -> scopeMatchesHotspot(parsedScope, h) }

                    for (hotspot in matchedHotspots) {
                        if (hotspot.value > dev.allowed) {
                            diagnostics.add(VerificationDiagnostic.failure(
                                DiagnosticCode.HOTSPOT_REGRESSION,
                                "${dev.id}: ${hotspot.module}/${hotspot.declaration} constructor has ${hotspot.value} " +
                                    "parameters, exceeds allowed ${dev.allowed}",
                                modulePath = hotspot.module,
                                deviationId = dev.id,
                                baselineValue = dev.baseline.toString(),
                                currentValue = hotspot.value.toString()))
                        }
                    }
                }
                "fileSize" -> {
                    val allFiles = current.structural.structuralHotspots.largestProductionFiles +
                        current.structural.structuralHotspots.largestBuildFiles
                    val matchedHotspots = allFiles.filter { h -> scopeMatchesHotspot(parsedScope, h) }

                    for (hotspot in matchedHotspots) {
                        if (hotspot.value > dev.allowed) {
                            diagnostics.add(VerificationDiagnostic.failure(
                                DiagnosticCode.HOTSPOT_REGRESSION,
                                "${dev.id}: file ${hotspot.path} is ${hotspot.value} lines, " +
                                    "exceeds allowed ${dev.allowed}",
                                modulePath = hotspot.module,
                                deviationId = dev.id,
                                baselineValue = dev.baseline.toString(),
                                currentValue = hotspot.value.toString()))
                        }
                    }
                }
            }
        }

        // Top-5 change detection
        val committedTop = committed.structural.structuralHotspots.largestProductionFiles.take(5).map { it.path }.toSet()
        val currentTop = current.structural.structuralHotspots.largestProductionFiles.take(5).map { it.path }.toSet()
        val newInTop = currentTop - committedTop
        if (newInTop.isNotEmpty()) {
            diagnostics.add(VerificationDiagnostic.warning(
                DiagnosticCode.NEW_TOP_FIVE_HOTSPOT,
                "New files entered top-5 production hotspots: ${newInTop.joinToString(", ")}"))
        }

        // 20% growth detection
        val committedByPath = committed.structural.structuralHotspots.largestProductionFiles.associateBy { it.path }
        for (currentHotspot in current.structural.structuralHotspots.largestProductionFiles) {
            val committedHotspot = committedByPath[currentHotspot.path]
            if (committedHotspot != null && currentHotspot.value > committedHotspot.value * 1.2) {
                diagnostics.add(VerificationDiagnostic.warning(
                    DiagnosticCode.FILE_GROWTH_EXCEEDED,
                    "${currentHotspot.path} grew >20%: ${committedHotspot.value} → ${currentHotspot.value} lines"))
            }
        }

        // Orphaned deviation detection using exact scope matching
        for (dev in deviations) {
            val parsedScope = deviationParser.parseScope(dev.scope) ?: continue
            if (dev.metric != "fileSize" && dev.metric != "constructorParameterCount") continue
            // Module-only scopes (no file path) cannot be orphaned — they apply to the module
            if (parsedScope.filePath == null && !parsedScope.isWildcard) continue

            val allHotspots = current.structural.structuralHotspots.largestProductionFiles +
                current.structural.structuralHotspots.largestBuildFiles +
                current.structural.structuralHotspots.mostConstructorParameters +
                current.structural.structuralHotspots.mostFunctionParameters

            val matched = allHotspots.any { h ->
                scopeMatchesHotspot(parsedScope, h)
            }

            if (!matched && !parsedScope.isWildcard) {
                diagnostics.add(VerificationDiagnostic.warning(
                    DiagnosticCode.ORPHANED_DEVIATION,
                    "${dev.id}: deviation scope '${dev.scope}' does not match any current hotspot — orphaned?"))
            }
        }
    }

    /** Check whether a structural hotspot matches a parsed deviation scope. */
    private fun scopeMatchesHotspot(scope: DeviationParser.DeviationScope, hotspot: StructuralHotspot): Boolean {
        if (scope.isWildcard && scope.modulePath != null) {
            // Wildcard prefix: `:tramai-` matches `:tramai-engine`, `:tramai-core`, etc.
            val prefix = scope.modulePath!!
            val hotspotModule = if (hotspot.module.startsWith(":")) hotspot.module else ":$hotspot.module"
            return hotspotModule.startsWith(prefix)
        }

        if (scope.filePath != null) {
            return hotspot.path.contains(scope.filePath!!) &&
                (scope.declaration == null || hotspot.declaration == scope.declaration)
        }

        if (scope.modulePath != null) {
            return (":$hotspot.module" == scope.modulePath || ":$hotspot.module".startsWith(scope.modulePath!!))
        }

        return false
    }

    private fun verifyDocumentDrift(
        committed: BaselineDocument,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val releaseNotesFile = File(ctx.rootDir, "docs/releases/0.6.0-maintainability-baseline.md")
        if (!releaseNotesFile.isFile) return

        val content = releaseNotesFile.readText()

        // Verify baseline SHA in release notes matches baseline JSON
        val canonicalSha = committed.baselineIdentity.baselineCommitSha
        if (canonicalSha.isNotBlank() && canonicalSha !in content) {
            diagnostics.add(VerificationDiagnostic.failure(
                DiagnosticCode.GENERATED_DOCUMENT_DRIFT,
                "Release notes do not reference baseline commit SHA $canonicalSha"))
        }

        // Verify module count
        val moduleCount = committed.structural.modules.size
        val moduleCountRefs = Regex("""(\d+)\s*modules?""", RegexOption.IGNORE_CASE).findAll(content)
        if (moduleCountRefs.any { it.groupValues[1].toIntOrNull() != moduleCount }) {
            diagnostics.add(VerificationDiagnostic.warning(
                DiagnosticCode.GENERATED_DOCUMENT_DRIFT,
                "Release notes module count may differ from canonical baseline ($moduleCount)"))
        }
    }

    // ─── Output ───

    private fun writeVerificationReport(diagnostics: List<VerificationDiagnostic>) {
        val report = mapOf(
            "schemaVersion" to "1",
            "description" to "TramAI 0.6.0 maintainability verification report",
            "passed" to diagnostics.none { it.severity == DiagnosticSeverity.FAILURE },
            "diagnostics" to diagnostics.map { diag ->
                mapOf(
                    "code" to diag.code.name,
                    "severity" to diag.severity.name,
                    "message" to diag.message,
                    "modulePath" to diag.modulePath,
                    "findingId" to diag.findingId,
                    "deviationId" to diag.deviationId,
                    "baselineValue" to diag.baselineValue,
                    "currentValue" to diag.currentValue
                ).filter { it.value != null }
            }
        )
        ReportNormalizer.writeJson(report, File(reportDir, "verification-report.json"))
    }

    private fun diagnosticsToReport(diagnostics: List<VerificationDiagnostic>): VerificationReport {
        val failures = diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }.map { "${it.code}: ${it.message}" }
        val warnings = diagnostics.filter { it.severity == DiagnosticSeverity.WARNING }.map { "${it.code}: ${it.message}" }
        val accepted = diagnostics.filter { it.severity == DiagnosticSeverity.ACCEPTED }.map { "${it.code}: ${it.message}" }
        return VerificationReport(failures.isEmpty(), failures, warnings, accepted)
    }

    companion object {
        private fun riskOrder(risk: String): Int = when (risk) {
            "accepted" -> 1; "low" -> 2; "medium" -> 3; "high" -> 4; "critical" -> 5; else -> 0
        }
    }
}
