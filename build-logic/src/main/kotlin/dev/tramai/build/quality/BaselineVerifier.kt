package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * Compares the currently generated baseline against the committed baseline.
 * Uses finding-level identity comparison, not aggregate counts.
 * Throws GradleException on regressions not covered by deviations.
 */
class BaselineVerifier(
    private val generator: BaselineGenerator,
    private val ctx: MeasurementContext,
    private val reportDir: File
) {
    private val deviationParser = DeviationParser(ctx.rootDir)

    fun verify(): VerificationReport {
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val accepted = mutableListOf<String>()

        // 1. Load committed baseline
        val committedFile = File(ctx.rootDir, "config/quality/0.6.0-baseline.json")
        if (!committedFile.isFile) {
            failures.add("Committed baseline not found: ${committedFile.absolutePath}")
            return VerificationReport(false, failures, warnings, accepted)
        }

        val committed: BaselineDocument = try {
            ReportNormalizer.readJson(committedFile, BaselineDocument::class.java)
        } catch (e: Exception) {
            failures.add("Failed to read committed baseline: ${e.message}")
            return VerificationReport(false, failures, warnings, accepted)
        }

        // 2. Parse deviations — classify errors
        val deviationResult = deviationParser.parse()
        val fatalErrors = deviationResult.errors.filter { e ->
            e.contains("not found", ignoreCase = true) ||
                e.contains("malformed", ignoreCase = true) ||
                e.contains("Duplicate", ignoreCase = true) ||
                e.contains("blank", ignoreCase = true) ||
                e.contains("placeholder", ignoreCase = true) ||
                e.contains("Missing", ignoreCase = true) ||
                e.contains("Invalid", ignoreCase = true) ||
                e.startsWith("Failed to parse")
        }
        val nonFatalWarnings = deviationResult.errors.filter { it !in fatalErrors }

        failures.addAll(fatalErrors)
        warnings.addAll(nonFatalWarnings)

        // Check for orphaned deviations
        val orphaned = findOrphanedDeviations(deviationResult.deviations, committed)
        warnings.addAll(orphaned)

        // 3. Verify baseline identity (including independent tree hash)
        verifyBaselineIdentity(committed, failures)

        // 4. Generate current measurements
        val currentCtx = MeasurementContext.fromProject(ctx.gradleProject!!)
        val tempGenerator = BaselineGenerator(
            ctx = currentCtx,
            outputDir = reportDir,
            writeRepositoryArtifacts = false
        )
        val current: BaselineDocument = try {
            tempGenerator.generateCompleteBaseline()
        } catch (e: Exception) {
            failures.add("Failed to generate current baseline: ${e.message}")
            return VerificationReport(false, failures, warnings, accepted)
        }

        // 5. Verify mandatory sections are not empty
        verifyMandatorySections(current, failures, warnings)

        // 6. Compare each dimension with finding-level identity
        verifyCancellationCatches(committed, current, deviationResult.deviations, failures, accepted)
        verifyGlobalState(committed, current, deviationResult.deviations, failures, accepted)
        verifyNondeterminism(committed, current, deviationResult.deviations, failures, accepted)
        verifyDependencyCycles(committed, current, deviationResult.deviations, failures, accepted)
        verifyProtocolCatalog(committed, current, failures, warnings)
        verifyStructuralHotspots(committed, current, deviationResult.deviations, failures, warnings)

        val passed = failures.isEmpty()
        return VerificationReport(passed, failures, warnings, accepted)
    }

    private fun verifyBaselineIdentity(committed: BaselineDocument, failures: MutableList<String>) {
        val id = committed.baselineIdentity
        if (id.baselineCommitSha.isBlank()) {
            failures.add("Committed baseline has empty baselineCommitSha")
        }
        if (id.tramaiVersion == "unspecified" || id.tramaiVersion.isBlank()) {
            failures.add("Committed baseline has invalid tramaiVersion: '${id.tramaiVersion}'")
        }
        // Validate version matches the tagged release
        val propsFile = File(ctx.rootDir, "gradle.properties")
        val propsVersion = if (propsFile.isFile) {
            propsFile.readLines().firstOrNull { it.trimStart().startsWith("tramaiVersion=") }
                ?.substringAfter("=")?.trim()
        } else null
        if (propsVersion != null && id.tramaiVersion != propsVersion) {
            failures.add(
                "Committed baseline version '${id.tramaiVersion}' does not match gradle.properties tramaiVersion '$propsVersion'"
            )
        }
        if (!id.workingTreeClean) {
            failures.add("Committed baseline was generated from a dirty worktree — regenerate from a clean checkout")
        }
        if (id.measuredSourceTreeHash.isBlank()) {
            failures.add("Committed baseline has empty measuredSourceTreeHash")
        }
        if (id.measuredGitTreeSha.isBlank()) {
            failures.add("Committed baseline has empty measuredGitTreeSha — regenerate with canonical task")
        }
        if (id.baselineCommitSha.isNotBlank() && id.measuredCommitSha.isNotBlank() &&
            id.baselineCommitSha != id.measuredCommitSha
        ) {
            failures.add(
                "Baseline provenance mismatch: measuredCommitSha (${id.measuredCommitSha.take(8)}) " +
                    "differs from baselineCommitSha (${id.baselineCommitSha.take(8)}). " +
                    "Regenerate the baseline from tag ${id.releaseTag}."
            )
        }
        // Independently verify the git tree SHA: recompute from the committed commit
        if (id.measuredGitTreeSha.isNotBlank() && id.measuredCommitSha.isNotBlank()) {
            val recomputedTreeSha = resolveRefOrFail(
                "${id.measuredCommitSha}^{tree}", "measuredCommitSha tree", failures
            ) ?: return
            if (recomputedTreeSha != id.measuredGitTreeSha) {
                failures.add(
                    "measuredGitTreeSha (${id.measuredGitTreeSha.take(8)}) does not match " +
                        "independently computed tree SHA (${recomputedTreeSha.take(8)}) for commit ${id.measuredCommitSha.take(8)}"
                )
            }
        }

        // Verify the release tag resolves to the baseline commit: v0.5.0^{commit} == baselineCommitSha
        if (id.baselineCommitSha.isNotBlank()) {
            val tagCommit = resolveRefOrFail(
                "${id.releaseTag}^{commit}", "release tag ${id.releaseTag}", failures
            ) ?: return
            if (tagCommit != id.baselineCommitSha) {
                failures.add(
                    "Release tag ${id.releaseTag} resolves to ${tagCommit.take(8)}, " +
                        "but baseline claims ${id.baselineCommitSha.take(8)}"
                )
            }
            // Verify the tag tree: v0.5.0^{tree} == measuredGitTreeSha
            if (id.measuredGitTreeSha.isNotBlank()) {
                val tagTree = resolveRefOrFail(
                    "${id.releaseTag}^{tree}", "release tag ${id.releaseTag} tree", failures
                ) ?: return
                if (tagTree != id.measuredGitTreeSha) {
                    failures.add(
                        "Release tag ${id.releaseTag} tree is ${tagTree.take(8)}, " +
                            "but baseline records ${id.measuredGitTreeSha.take(8)}"
                    )
                }
            }
        }

        // Verify analyzer identity: commit must exist and be reachable from HEAD
        if (id.analyzerCommitSha.isNotBlank()) {
            // Check commit exists
            val analyzerExists = resolveRefOrFail(
                "${id.analyzerCommitSha}^{commit}", "analyzerCommitSha", failures
            )
            if (analyzerExists == null) return
            // Check analyzer is an ancestor of HEAD
            val isAncestor = try {
                val process = ProcessBuilder(
                    listOf("git", "merge-base", "--is-ancestor", id.analyzerCommitSha, "HEAD")
                ).directory(ctx.rootDir).redirectErrorStream(true).start()
                process.waitFor() == 0
            } catch (_: Exception) {
                false
            }
            if (!isAncestor) {
                failures.add(
                    "analyzerCommitSha (${id.analyzerCommitSha.take(8)}) is not an ancestor of HEAD — " +
                        "the recorded analyzer may not contain the implementation that generated this baseline"
                )
            }
        } else {
            failures.add("Committed baseline has empty analyzerCommitSha — regenerate with canonical task")
        }
    }

    private fun resolveRefOrFail(
        ref: String, label: String, failures: MutableList<String>
    ): String? {
        return try {
            val result = ctx.runGit("rev-parse", ref)
            if (result.isBlank()) {
                failures.add("Unable to resolve $label ($ref): empty result — is the tag/commit available?")
                null
            } else result
        } catch (e: Exception) {
            failures.add("Unable to resolve $label ($ref): ${e.message}")
            null
        }
    }

    private fun verifyMandatorySections(
        current: BaselineDocument,
        failures: MutableList<String>,
        warnings: MutableList<String>
    ) {
        if (current.structural.moduleDependencies.modules.isEmpty()) {
            failures.add("Current baseline has empty module list — measurements not populated")
        }
        if (current.runtimeSafety.cancellationCatches.isEmpty() &&
            current.runtimeSafety.testCancellationCatches.isEmpty()
        ) {
            failures.add("Current baseline has empty cancellation catch inventory — scanner may be broken")
        }
        if (current.structural.sourceMetrics.byModule.isEmpty()) {
            failures.add("Current baseline has empty source metrics")
        }
        if (current.protocolCatalog.entries.isEmpty()) {
            failures.add("Current baseline has empty protocol catalog")
        }
        if (current.api.publicApiDumps.isEmpty()) {
            failures.add("Current baseline has empty API dumps — run './gradlew apiDump' for publishable modules")
        }
        if (current.dependencies.resolvedDependencies.isEmpty()) {
            warnings.add("Current baseline has empty resolved dependencies — dependency resolution may be broken")
        }
    }

    // ─── Finding-level identity comparison ───

    private fun cancellationFindingId(f: CancellationCatchFinding): String =
        "${f.module}::${f.file}::${f.function}::${f.catchType}"

    private fun globalStateFindingId(f: GlobalStateFinding): String =
        "${f.module}::${f.file}::${f.declaration}::${f.kind}"

    private fun nondeterminismFindingId(f: NondeterminismFinding): String =
        "${f.module}::${f.file}::${f.line}::${f.source}::${f.classification}"

    private fun verifyCancellationCatches(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        failures: MutableList<String>,
        accepted: MutableList<String>
    ) {
        val committedIds = committed.runtimeSafety.cancellationCatches.map { cancellationFindingId(it) }.toSet()
        val currentIds = current.runtimeSafety.cancellationCatches.map { cancellationFindingId(it) }.toSet()

        val added = currentIds - committedIds
        val removed = committedIds - currentIds

        // New critical findings: fail unless covered by deviation
        val addedCritical = current.runtimeSafety.cancellationCatches
            .filter { it.risk == "critical" && cancellationFindingId(it) in added }

        if (addedCritical.isNotEmpty()) {
            val currentCriticalCount = current.runtimeSafety.cancellationCatches.count { it.risk == "critical" }
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "cancellationCriticalCount", "tramai-*", currentCriticalCount
            )
            if (deviation != null) {
                accepted.add("${addedCritical.size} new critical catches — accepted by ${deviation.id}")
            } else {
                failures.add(
                    "${addedCritical.size} new critical cancellation catch(es) detected:\n" +
                        addedCritical.joinToString("\n") { "  - ${it.module}/${it.file}:${it.function} (${it.catchType})" } +
                        "\nAdd a deviation or fix the regression."
                )
            }
        }

        // Risk worsening: same identity but higher risk
        val committedByRisk = committed.runtimeSafety.cancellationCatches
            .associate { cancellationFindingId(it) to it.risk }
        val worsenedRisks = mutableListOf<String>()
        for (currentFinding in current.runtimeSafety.cancellationCatches) {
            val id = cancellationFindingId(currentFinding)
            val committedRisk = committedByRisk[id]
            if (committedRisk != null) {
                val riskOrder = listOf("accepted", "low", "medium", "high", "critical")
                val committedIdx = riskOrder.indexOf(committedRisk)
                val currentIdx = riskOrder.indexOf(currentFinding.risk)
                if (currentIdx > committedIdx) {
                    worsenedRisks.add(
                        "${currentFinding.module}/${currentFinding.file}:" +
                            "${currentFinding.function} (${committedRisk} → ${currentFinding.risk})"
                    )
                }
            }
        }
        if (worsenedRisks.isNotEmpty()) {
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "cancellationRiskWorsening", "tramai-*", worsenedRisks.size
            )
            if (deviation != null) {
                accepted.add("${worsenedRisks.size} risk worsenings — accepted by ${deviation.id}")
            } else {
                worsenedRisks.forEach {
                    failures.add("Cancellation catch risk worsened: $it")
                }
            }
        }

        if (removed.isNotEmpty()) {
            accepted.add("${removed.size} cancellation catch(es) resolved")
        }
    }

    private fun verifyGlobalState(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        failures: MutableList<String>,
        accepted: MutableList<String>
    ) {
        val committedIds = committed.runtimeSafety.globalState.map { globalStateFindingId(it) }.toSet()
        val currentIds = current.runtimeSafety.globalState.map { globalStateFindingId(it) }.toSet()

        val added = currentIds - committedIds
        if (added.isNotEmpty()) {
            val currentCount = current.runtimeSafety.globalState.size
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "globalMutableState", "*", currentCount
            )
            if (deviation != null) {
                accepted.add("${added.size} new global state instance(s) — accepted by ${deviation.id}")
            } else {
                failures.add(
                    "${added.size} new global mutable state instance(s) detected. " +
                        "Add a deviation or fix the regression."
                )
            }
        }
    }

    private fun verifyNondeterminism(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        failures: MutableList<String>,
        accepted: MutableList<String>
    ) {
        val committedIds = committed.runtimeSafety.nondeterminism.map { nondeterminismFindingId(it) }.toSet()
        val currentIds = current.runtimeSafety.nondeterminism.map { nondeterminismFindingId(it) }.toSet()

        val added = currentIds - committedIds
        if (added.isNotEmpty()) {
            val currentCount = current.runtimeSafety.nondeterminism.size
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "nondeterminismSources", "*", currentCount
            )
            if (deviation != null) {
                accepted.add("${added.size} new nondeterminism source(s) — accepted by ${deviation.id}")
            } else {
                failures.add(
                    "${added.size} new nondeterminism source(s) detected. " +
                        "Add a deviation or fix the regression."
                )
            }
        }
    }

    private fun verifyDependencyCycles(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        failures: MutableList<String>,
        accepted: MutableList<String>
    ) {
        val committedCycles = committed.structural.moduleDependencies.cycles.map { it.sorted().joinToString("→") }.toSet()
        val currentCycles = current.structural.moduleDependencies.cycles.map { it.sorted().joinToString("→") }.toSet()

        val newCycles = currentCycles - committedCycles
        if (newCycles.isNotEmpty()) {
            for (cycle in newCycles) {
                failures.add("New dependency cycle detected: $cycle")
            }
        }

        val removedCycles = committedCycles - currentCycles
        if (removedCycles.isNotEmpty()) {
            accepted.add("Dependency cycles resolved: ${removedCycles.joinToString(", ")}")
        }
    }

    private fun verifyProtocolCatalog(
        committed: BaselineDocument,
        current: BaselineDocument,
        failures: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val committedByKey = committed.protocolCatalog.entries.associateBy {
            "${it.category}::${it.name}::${it.value}::${it.source}"
        }
        val currentByKey = current.protocolCatalog.entries.associateBy {
            "${it.category}::${it.name}::${it.value}::${it.source}"
        }

        for ((key, entry) in committedByKey) {
            if (entry.stability == "stable-contract" && key !in currentByKey) {
                failures.add("Stable protocol contract removed: ${entry.name} (${entry.category})")
            }
        }

        val committedUnclassified = committed.protocolCatalog.entries.count { it.stability == "unclassified" }
        val currentUnclassified = current.protocolCatalog.entries.count { it.stability == "unclassified" }
        if (currentUnclassified > committedUnclassified) {
            warnings.add("Unclassified protocol entries increased: $committedUnclassified → $currentUnclassified")
        }
    }

    private fun verifyStructuralHotspots(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        failures: MutableList<String>,
        warnings: MutableList<String>
    ) {
        for (dev in deviations) {
            when (dev.metric) {
                "constructorParameterCount" -> {
                    // Match by module and declaration, not just global max
                    val matchedHotspots = current.structural.structuralHotspots.mostConstructorParameters
                        .filter { hotspot ->
                            val scopeModule = dev.scope.removePrefix("\"").removeSuffix("\"").split(":").firstOrNull() ?: ""
                            hotspot.module == scopeModule || dev.scope == "*"
                        }
                    for (hotspot in matchedHotspots) {
                        if (hotspot.value > dev.allowed) {
                            failures.add(
                                "${dev.id}: ${hotspot.module}/${hotspot.declaration} constructor has ${hotspot.value} parameters, " +
                                    "exceeds allowed ${dev.allowed} (scope: ${dev.scope})"
                            )
                        }
                    }
                }
                "fileSize" -> {
                    val allFiles = current.structural.structuralHotspots.largestProductionFiles +
                        current.structural.structuralHotspots.largestBuildFiles
                    for (hotspot in allFiles) {
                        val scopeFile = dev.scope.removePrefix("\"").removeSuffix("\"").split(":").lastOrNull() ?: ""
                        if (hotspot.path.contains(scopeFile) && hotspot.value > dev.allowed) {
                            failures.add(
                                "${dev.id}: file ${hotspot.path} is ${hotspot.value} lines, " +
                                    "exceeds allowed ${dev.allowed} (scope: ${dev.scope})"
                            )
                        }
                    }
                }
            }
        }

        val committedTop = committed.structural.structuralHotspots.largestProductionFiles.take(5).map { it.path }.toSet()
        val currentTop = current.structural.structuralHotspots.largestProductionFiles.take(5).map { it.path }.toSet()

        val newInTop = currentTop - committedTop
        if (newInTop.isNotEmpty()) {
            warnings.add("New files entered top-5 production hotspots: ${newInTop.joinToString(", ")}")
        }

        val committedByPath = committed.structural.structuralHotspots.largestProductionFiles.associateBy { it.path }
        for (currentHotspot in current.structural.structuralHotspots.largestProductionFiles) {
            val committedHotspot = committedByPath[currentHotspot.path]
            if (committedHotspot != null && currentHotspot.value > committedHotspot.value * 1.2) {
                warnings.add(
                    "${currentHotspot.path} grew >20%: ${committedHotspot.value} → ${currentHotspot.value} lines"
                )
            }
        }
    }

    private fun findOrphanedDeviations(
        deviations: List<DeviationParser.DeviationEntry>,
        committed: BaselineDocument
    ): List<String> {
        val warnings = mutableListOf<String>()
        val allFilePaths = committed.structural.structuralHotspots.largestProductionFiles.map { it.path }.toSet() +
            committed.structural.structuralHotspots.largestBuildFiles.map { it.path }.toSet()

        for (dev in deviations) {
            val scope = dev.scope.removePrefix("\"").removeSuffix("\"")
            if (dev.metric == "fileSize" || dev.metric == "constructorParameterCount") {
                val scopeFile = scope.split(":").lastOrNull()?.trim() ?: continue
                val found = allFilePaths.any { it.contains(scopeFile) }
                if (!found && scopeFile != "*" && scopeFile != "tramai-*") {
                    warnings.add("${dev.id}: deviation scope '$scope' references file not in current hotspots — orphaned?")
                }
            }
        }
        return warnings
    }

    companion object {
        fun verify(project: Project): VerificationReport {
            val ctx = MeasurementContext.fromProject(project)
            val generator = BaselineGenerator(ctx)
            val reportDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")
            return BaselineVerifier(generator, ctx, reportDir).verify()
        }
    }
}
