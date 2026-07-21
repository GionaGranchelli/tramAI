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
        // Delete old report before starting
        val reportFile = File(reportDir, "verification-report.json")
        reportFile.delete()

        val diagnostics = mutableListOf<VerificationDiagnostic>()
        try {
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
            diagnostics.addAll(catalogResult.errors)

            // 4. Parse boundary rules
            val boundaryResult = moduleBoundaries.parse()
            diagnostics.addAll(boundaryResult.errors)

            // 5. Verify baseline identity
            verifyBaselineIdentity(committed, diagnostics)

            // 6. Generate current measurements (needed before catalogue validation)
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

            // 7. Verify module catalogue against CURRENT projects (not committed)
            verifyModuleCatalog(current, catalogResult, diagnostics)

            // 8. Verify mandatory sections
            verifyMandatorySections(current, diagnostics)

            // 9. Restore API dump gate
            if (current.api.publicApiDumps.isEmpty()) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.EMPTY_SECTION,
                    "Current baseline has empty API dumps — API collection may be broken"))
            }

            // 10. Compare dimensions with FindingIdentity
            verifyCancellationCatches(committed, current, deviationResult.deviations, diagnostics)
            verifyGlobalState(committed, current, deviationResult.deviations, diagnostics)
            verifyNondeterminism(committed, current, deviationResult.deviations, diagnostics)
            verifyDependencyCycles(committed, current, deviationResult.deviations, diagnostics)
            verifyProtocolCatalog(committed, current, diagnostics)
            verifyStructuralHotspots(committed, current, deviationResult.deviations, diagnostics)
            verifyForbiddenEdges(committed, current, diagnostics)
            verifyDocumentDrift(committed, diagnostics)
        } finally {
            // Always write report, even on partial failures
            writeVerificationReport(diagnostics)
        }

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

        // Tag resolves correctly (only validate non-blank identity fields)
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
        current: BaselineDocument,
        catalogResult: ModuleCatalog.CatalogResult,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val projectPaths = current.structural.moduleDependencies.modules.toList()
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

        // Verify catalogue classifications match current baseline
        for (mod in current.structural.modules) {
            val catalogEntry = catalogModules[mod.path] ?: continue
            if (catalogEntry.layer != mod.layer) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_DISAGREEMENT,
                    "Module '${mod.path}': catalogue declares layer '${catalogEntry.layer}' but " +
                        "current baseline has '${mod.layer}'"))
            }

            val expectedPublished = catalogEntry.publishability == "published"
            if (expectedPublished != mod.publishable) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.MODULE_CATALOG_DISAGREEMENT,
                    "Module '${mod.path}': catalogue declares publishability '${catalogEntry.publishability}' " +
                        "but current baseline has '${if (mod.publishable) "published" else "not published"}'"))
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

    // ─── Multiset comparison utilities ───

    /**
     * Count findings by identity key, detecting multiple occurrences.
     */
    private fun countById(findings: List<FindingIdentity>): Map<String, Int> =
        findings.groupBy { it.toIdentityKey() }.mapValues { it.value.size }

    // ─── Finding-level comparison with FindingIdentity ───

    private fun verifyCancellationCatches(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val committedIds = committed.runtimeSafety.cancellationCatches.map {
            FindingIdentity.fromCancellationCatch(it).toIdentityKey()
        }
        val currentIds = current.runtimeSafety.cancellationCatches.map {
            FindingIdentity.fromCancellationCatch(it).toIdentityKey()
        }

        // Multiset comparison: compare counts, not just set membership
        val committedCounts = committedIds.groupBy { it }.mapValues { it.value.size }
        val currentCounts = currentIds.groupBy { it }.mapValues { it.value.size }

        val added = mutableSetOf<String>()
        for ((key, count) in currentCounts) {
            val oldCount = committedCounts[key] ?: 0
            if (count > oldCount) {
                added.add(key)
            }
        }
        val addedCritical = current.runtimeSafety.cancellationCatches
            .filter { it.risk == "critical" && FindingIdentity.fromCancellationCatch(it).toIdentityKey() in added }

        if (addedCritical.isNotEmpty()) {
            // Evaluate per deviation scope: each deviation's budget applies
            // to ALL findings matching its scope, not per-module.
            val deviationsByScope = deviations
                .filter { it.metric == "cancellationCriticalCount" }
                .sortedByDescending { it.allowed }

            var remainingUncovered = addedCritical.toMutableList()

            for (dev in deviationsByScope) {
                val parsedScope = deviationParser.parseScope(dev.scope)
                if (parsedScope == null) continue

                // Count ALL current critical catches matching this deviation's scope
                val matchingCurrent = current.runtimeSafety.cancellationCatches.count { f ->
                    val fm = if (f.module.startsWith(":")) f.module else ":${f.module}"
                    f.risk == "critical" && parsedScope.covers(
                        DeviationParser.FindingScope(fm, null, null)
                    )
                }

                // Count committed critical catches matching this deviation's scope
                val matchingCommitted = committed.runtimeSafety.cancellationCatches.count { f ->
                    val fm = if (f.module.startsWith(":")) f.module else ":${f.module}"
                    f.risk == "critical" && parsedScope.covers(
                        DeviationParser.FindingScope(fm, null, null)
                    )
                }

                if (matchingCurrent <= dev.allowed) {
                    // This deviation covers all matching findings
                    val covered = remainingUncovered.filter { f ->
                        val fm = if (f.module.startsWith(":")) f.module else ":${f.module}"
                        parsedScope.covers(DeviationParser.FindingScope(fm, null, null))
                    }
                    if (covered.isNotEmpty()) {
                        diagnostics.add(VerificationDiagnostic.accepted(
                            DiagnosticCode.NEW_CANCELLATION_FINDING,
                            "${covered.size} new critical catches — accepted by ${dev.id} (${matchingCurrent} total in scope ≤ ${dev.allowed})",
                            deviationId = dev.id))
                        remainingUncovered.removeAll(covered.toSet())
                    }
                }
            }

            if (remainingUncovered.isNotEmpty()) {
                val byModule = remainingUncovered.groupBy { f ->
                    if (f.module.startsWith(":")) f.module else ":${f.module}"
                }
                for ((module, findings) in byModule) {
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.NEW_CANCELLATION_FINDING,
                        "${findings.size} new critical cancellation catch(es) in $module — no covering deviation",
                        modulePath = module))
                }
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
            // Evaluate per deviation scope: each deviation's budget applies
            // to ALL findings matching its scope.
            val riskDeviations = deviations.filter { it.metric == "cancellationRiskWorsening" }
                .sortedByDescending { it.allowed }

            var remainingWorsened = worsenedRisks.toMutableList()

            for (dev in riskDeviations) {
                val parsedScope = deviationParser.parseScope(dev.scope)
                if (parsedScope == null) continue

                val matchingWorsened = worsenedRisks.count { f ->
                    val fm = if (f.module.startsWith(":")) f.module else ":${f.module}"
                    parsedScope.covers(DeviationParser.FindingScope(fm, null, null))
                }

                if (matchingWorsened <= dev.allowed) {
                    val covered = remainingWorsened.filter { f ->
                        val fm = if (f.module.startsWith(":")) f.module else ":${f.module}"
                        parsedScope.covers(DeviationParser.FindingScope(fm, null, null))
                    }
                    if (covered.isNotEmpty()) {
                        diagnostics.add(VerificationDiagnostic.accepted(
                            DiagnosticCode.CANCELLATION_RISK_WORSENED,
                            "${covered.size} risk worsenings — accepted by ${dev.id} (${matchingWorsened} total in scope ≤ ${dev.allowed})",
                            deviationId = dev.id))
                        remainingWorsened.removeAll(covered.toSet())
                    }
                }
            }

            if (remainingWorsened.isNotEmpty()) {
                remainingWorsened.forEach { f ->
                    diagnostics.add(VerificationDiagnostic.failure(
                        DiagnosticCode.CANCELLATION_RISK_WORSENED,
                        "Cancellation catch risk worsened: ${f.module}/${f.file}:${f.function} — no covering deviation",
                        modulePath = f.module))
                }
            }
        }

        val removed = committedIds.toSet() - currentIds.toSet()
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
        }
        val currentIds = current.runtimeSafety.globalState.map {
            FindingIdentity.fromGlobalState(it).toIdentityKey()
        }

        // Multiset comparison
        val committedCounts = committedIds.groupBy { it }.mapValues { it.value.size }
        val currentCounts = currentIds.groupBy { it }.mapValues { it.value.size }

        val added = mutableSetOf<String>()
        for ((key, count) in currentCounts) {
            val oldCount = committedCounts[key] ?: 0
            if (count > oldCount) {
                added.add(key)
            }
        }
        if (added.isNotEmpty()) {
            // Check per-module against deviations
            val remainingAdded = mutableSetOf<String>()
            for (id in added) {
                val finding = current.runtimeSafety.globalState.find {
                    FindingIdentity.fromGlobalState(it).toIdentityKey() == id
                } ?: continue
                val module = if (finding.module.startsWith(":")) finding.module else ":${finding.module}"

                // Count occurrences of this finding type in this module
                val moduleCount = current.runtimeSafety.globalState.count {
                    val m = if (it.module.startsWith(":")) it.module else ":${it.module}"
                    m == module
                }

                val deviation = deviationParser.findCoveringDeviation(
                    deviations, "globalMutableState",
                    DeviationParser.FindingScope(modulePath = module, null, null),
                    moduleCount
                )
                if (deviation == null) {
                    remainingAdded.add(id)
                } else {
                    diagnostics.add(VerificationDiagnostic.accepted(
                        DiagnosticCode.NEW_GLOBAL_STATE_FINDING,
                        "New global state '${id}' in $module — accepted by ${deviation.id}",
                        deviation.id))
                }
            }

            if (remainingAdded.isNotEmpty()) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.NEW_GLOBAL_STATE_FINDING,
                    "${remainingAdded.size} new global mutable state instance(s) detected without covering deviation"))
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
        }
        val currentIds = current.runtimeSafety.nondeterminism.map {
            FindingIdentity.fromNondeterminism(it).toIdentityKey()
        }

        // Multiset comparison using count deltas
        val committedCounts = committedIds.groupBy { it }.mapValues { it.value.size }
        val currentCounts = currentIds.groupBy { it }.mapValues { it.value.size }

        val addedCounts = currentCounts.mapNotNull { (key, currentCount) ->
            val delta = currentCount - (committedCounts[key] ?: 0)
            if (delta > 0) key to delta else null
        }
        if (addedCounts.isNotEmpty()) {
            val totalAdded = addedCounts.sumOf { it.second }
            val currentCount = current.runtimeSafety.nondeterminism.size
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "nondeterminismSources",
                DeviationParser.FindingScope(modulePath = null, null, null),
                currentCount
            )
            if (deviation != null) {
                diagnostics.add(VerificationDiagnostic.accepted(
                    DiagnosticCode.NEW_NONDETERMINISM_FINDING,
                    "$totalAdded new nondeterminism occurrence(s) — accepted by ${deviation.id}",
                    deviation.id))
            } else {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.NEW_NONDETERMINISM_FINDING,
                    "$totalAdded new nondeterminism occurrence(s) detected"))
            }
        }
    }

    private fun verifyDependencyCycles(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        val committedCycles = committed.structural.moduleDependencies.cycles.map { it.sorted().joinToString("\u2192") }.toSet()
        val currentCycles = current.structural.moduleDependencies.cycles.map { it.sorted().joinToString("\u2192") }.toSet()

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
                "Unclassified protocol entries increased: $committedUnclassified \u2192 $currentUnclassified"))
        }
    }

    private fun verifyStructuralHotspots(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        // Validate deviation baselines against committed measurements
        validateDeviationBaselines(committed, deviations, diagnostics)

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
                    "${currentHotspot.path} grew >20%: ${committedHotspot.value} \u2192 ${currentHotspot.value} lines"))
            }
        }

        // Orphaned deviation detection using typed covers()
        for (dev in deviations) {
            val parsedScope = deviationParser.parseScope(dev.scope) ?: continue
            if (dev.metric != "fileSize" && dev.metric != "constructorParameterCount") continue
            if (parsedScope.filePath == null && !parsedScope.isWildcard) continue

            val allHotspots = current.structural.structuralHotspots.largestProductionFiles +
                current.structural.structuralHotspots.largestBuildFiles +
                current.structural.structuralHotspots.mostConstructorParameters +
                current.structural.structuralHotspots.mostFunctionParameters

            val matched = allHotspots.any { h ->
                val findingScope = DeviationParser.FindingScope(
                    modulePath = if (h.module.startsWith(":")) h.module else ":${h.module}",
                    repositoryPath = h.path,
                    declaration = h.declaration.ifBlank { null }
                )
                parsedScope.covers(findingScope)
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
        val findingScope = DeviationParser.FindingScope(
            modulePath = if (hotspot.module.startsWith(":")) hotspot.module else ":${hotspot.module}",
            repositoryPath = hotspot.path,
            declaration = hotspot.declaration.ifBlank { null }
        )
        return scope.covers(findingScope)
    }

    /**
     * Validate that each deviation's recorded baseline value matches the
     * committed canonical measurement for that metric.
     * Emits DEVIATION_BASELINE_MISMATCH when they differ.
     */
    private fun validateDeviationBaselines(
        committed: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        for (dev in deviations) {
            val actualBaseline = computeCommittedBaseline(committed, dev.metric, dev.scope)
            if (actualBaseline != null && actualBaseline != dev.baseline) {
                diagnostics.add(VerificationDiagnostic.failure(
                    DiagnosticCode.DEVIATION_BASELINE_MISMATCH,
                    "${dev.id}: recorded baseline ${dev.baseline} does not match canonical measurement $actualBaseline for metric '${dev.metric}' at scope '${dev.scope}'",
                    deviationId = dev.id,
                    baselineValue = dev.baseline.toString(),
                    currentValue = actualBaseline.toString()))
            }
        }
    }

    /**
     * Compute the committed baseline value for a given metric and scope.
     * Filters findings to those matching the deviation's scope for accuracy.
     * Returns null when the metric is not directly comparable (e.g. fileSize
     * varies per file, not globally).
     */
    private fun computeCommittedBaseline(
        committed: BaselineDocument,
        metric: String,
        scope: String
    ): Int? {
        val parsedScope = deviationParser.parseScope(scope) ?: return null

        fun matchesScope(modulePath: String): Boolean {
            val norm = if (modulePath.startsWith(":")) modulePath else ":$modulePath"
            return parsedScope.covers(DeviationParser.FindingScope(norm, null, null))
        }

        return when (metric) {
            "cancellationCriticalCount" ->
                committed.runtimeSafety.cancellationCatches.count {
                    it.risk == "critical" && matchesScope(it.module)
                }
            "cancellationRiskWorsening" -> null
            "globalMutableState" ->
                committed.runtimeSafety.globalState.count {
                    matchesScope(it.module)
                }
            "nondeterminismSources" ->
                committed.runtimeSafety.nondeterminism.count {
                    matchesScope(it.module)
                }
            "constructorParameterCount" -> null // per-hotspot
            "fileSize" -> null // per-file
            else -> null
        }
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
        reportDir.mkdirs()

        val executionSha = try {
            ctx.runGit("rev-parse", "HEAD")
        } catch (_: Exception) { "unknown" }

        val report = mapOf(
            "schemaVersion" to "1",
            "description" to "TramAI 0.6.0 maintainability verification report",
            "executionHeadSha" to executionSha,
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
