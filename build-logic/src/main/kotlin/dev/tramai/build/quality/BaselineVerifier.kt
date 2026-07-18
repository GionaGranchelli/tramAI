package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.security.MessageDigest

/**
 * Compares the currently generated baseline against the committed baseline.
 * Uses finding-level identity comparison, not aggregate counts.
 * Throws GradleException on regressions not covered by deviations.
 */
class BaselineVerifier(
    private val generator: BaselineGenerator,
    private val rootDir: File,
    private val reportDir: File
) {
    private val deviationParser = DeviationParser(rootDir)

    fun verify(): VerificationReport {
        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val accepted = mutableListOf<String>()

        // 1. Load committed baseline
        val committedFile = File(rootDir, "config/quality/0.6.0-baseline.json")
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
                e.contains("Invalid", ignoreCase = true)
        }
        val nonFatalWarnings = deviationResult.errors.filter { it !in fatalErrors }

        failures.addAll(fatalErrors)
        warnings.addAll(nonFatalWarnings)

        // Check for orphaned deviations
        val orphaned = findOrphanedDeviations(deviationResult.deviations, committed)
        warnings.addAll(orphaned)

        // 3. Verify baseline identity
        verifyBaselineIdentity(committed, failures)

        // 4. Generate current measurements using the COMPLETE baseline method
        val tempGenerator = BaselineGenerator(
            rootProject = generator.rootProject,
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
        val taggedVersion = generator.rootProject.findProperty("tramaiVersion")?.toString()
        if (taggedVersion != null && taggedVersion != "unspecified" && id.tramaiVersion != taggedVersion) {
            failures.add(
                "Committed baseline version '${id.tramaiVersion}' does not match gradle.properties tramaiVersion '$taggedVersion'"
            )
        }
        if (!id.workingTreeClean) {
            failures.add("Committed baseline was generated from a dirty worktree — regenerate from a clean checkout")
        }
        if (id.measuredSourceTreeHash.isBlank()) {
            failures.add("Committed baseline has empty measuredSourceTreeHash")
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
            failures.add("Current baseline has empty resolved dependencies — dependency resolution may be broken")
        }
    }

    // ─── Finding-level identity comparison ───

    private fun cancellationFindingId(f: CancellationCatchFinding): String =
        "${f.module}::${f.file}::${f.function}::${f.catchType}"

    private fun globalStateFindingId(f: GlobalStateFinding): String =
        "${f.module}::${f.file}::${f.declaration}::${f.kind}"

    private fun nondeterminismFindingId(f: NondeterminismFinding): String =
        "${f.module}::${f.file}::${f.source}::${f.classification}"

    private fun verifyCancellationCatches(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        failures: MutableList<String>,
        accepted: MutableList<String>
    ) {
        // Only compare production catches
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
        // Key by category+name+value+source for stable identity
        val committedByKey = committed.protocolCatalog.entries.associateBy {
            "${it.category}::${it.name}::${it.value}::${it.source}"
        }
        val currentByKey = current.protocolCatalog.entries.associateBy {
            "${it.category}::${it.name}::${it.value}::${it.source}"
        }

        // Stable contract entries must not be removed
        for ((key, entry) in committedByKey) {
            if (entry.stability == "stable-contract" && key !in currentByKey) {
                failures.add("Stable protocol contract removed: ${entry.name} (${entry.category})")
            }
        }

        // Unclassified entries must not increase
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
        // Check accepted deviation thresholds
        for (dev in deviations) {
            when (dev.metric) {
                "constructorParameterCount" -> {
                    val currentMax = current.structural.structuralHotspots.mostConstructorParameters
                        .firstOrNull()?.value ?: 0
                    if (currentMax > dev.allowed) {
                        failures.add(
                            "${dev.id}: constructor parameter count ${currentMax} exceeds allowed ${dev.allowed} " +
                                "(scope: ${dev.scope})"
                        )
                    }
                }
                "fileSize" -> {
                    // Check all hotspots matching the deviation scope
                    val allFiles = current.structural.structuralHotspots.largestProductionFiles +
                        current.structural.structuralHotspots.largestBuildFiles
                    for (hotspot in allFiles) {
                        if (hotspot.path.contains(dev.scope.removePrefix("\"").removeSuffix("\"").split(":").lastOrNull() ?: "") &&
                            hotspot.value > dev.allowed
                        ) {
                            failures.add(
                                "${dev.id}: file ${hotspot.path} is ${hotspot.value} lines, " +
                                    "exceeds allowed ${dev.allowed} (scope: ${dev.scope})"
                            )
                        }
                    }
                }
            }
        }

        // Warn on new entries in top-5 or >20% growth
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
            val generator = BaselineGenerator(project)
            val reportDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")
            return BaselineVerifier(generator, project.rootDir, reportDir).verify()
        }
    }
}
