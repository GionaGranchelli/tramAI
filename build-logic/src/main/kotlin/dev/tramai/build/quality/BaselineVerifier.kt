package dev.tramai.build.quality

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * Compares the currently generated baseline against the committed baseline.
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

        // 2. Parse deviations
        val deviationResult = deviationParser.parse()
        warnings.addAll(deviationResult.errors.filter { !it.contains("expired", ignoreCase = true) })
        val expiredWarnings = deviationResult.errors.filter { it.contains("expired", ignoreCase = true) }
        if (expiredWarnings.isNotEmpty()) {
            warnings.addAll(expiredWarnings)
        }

        // 3. Verify baseline identity
        verifyBaselineIdentity(committed, failures)

        // 4. Generate current measurements (to temp dir, not committed)
        val tempGenerator = BaselineGenerator(
            rootProject = generator.rootProject,
            outputDir = reportDir,
            writeRepositoryArtifacts = false
        )
        val current = try {
            tempGenerator.generateFullBaseline()
        } catch (e: Exception) {
            failures.add("Failed to generate current baseline: ${e.message}")
            return VerificationReport(false, failures, warnings, accepted)
        }

        // 5. Compare each dimension
        verifyCancellationCatches(committed, current, deviationResult.deviations, failures, accepted)
        verifyGlobalState(committed, current, deviationResult.deviations, failures, accepted)
        verifyNondeterminism(committed, current, deviationResult.deviations, failures, accepted)
        verifyDependencyCycles(committed, current, deviationResult.deviations, failures, accepted)
        verifyProtocolCatalog(committed, current, failures, warnings)
        verifyStructuralHotspots(committed, current, failures, warnings)

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
        if (id.baselineCommitSha.isNotBlank() && id.measuredCommitSha.isNotBlank() &&
            id.baselineCommitSha != id.measuredCommitSha
        ) {
            failures.add(
                "Baseline mismatch: measuredCommitSha (${id.measuredCommitSha.take(8)}) " +
                    "differs from baselineCommitSha (${id.baselineCommitSha.take(8)}). " +
                    "Regenerate baseline from tag ${id.releaseTag} or accept with deviation."
            )
        }
    }

    private fun verifyCancellationCatches(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        failures: MutableList<String>,
        accepted: MutableList<String>
    ) {
        val committedCount = committed.runtimeSafety.cancellationCatches.count { it.risk == "critical" }
        val currentCount = current.runtimeSafety.cancellationCatches.count { it.risk == "critical" }

        if (currentCount > committedCount) {
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "cancellationCriticalCount", "tramai-*", currentCount
            )
            if (deviation != null) {
                accepted.add("Cancellation critical catches increased from $committedCount to $currentCount — accepted by ${deviation.id}")
            } else {
                failures.add(
                    "Cancellation critical catch count regressed: $committedCount → $currentCount. " +
                        "Add a deviation or fix the regression."
                )
            }
        }
    }

    private fun verifyGlobalState(
        committed: BaselineDocument,
        current: BaselineDocument,
        deviations: List<DeviationParser.DeviationEntry>,
        failures: MutableList<String>,
        accepted: MutableList<String>
    ) {
        val committedCount = committed.runtimeSafety.globalState.size
        val currentCount = current.runtimeSafety.globalState.size

        if (currentCount > committedCount) {
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "globalMutableState", "*", currentCount
            )
            if (deviation != null) {
                accepted.add("Global mutable state increased from $committedCount to $currentCount — accepted by ${deviation.id}")
            } else {
                failures.add(
                    "Global mutable state count regressed: $committedCount → $currentCount. " +
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
        val committedCount = committed.runtimeSafety.nondeterminism.size
        val currentCount = current.runtimeSafety.nondeterminism.size

        if (currentCount > committedCount) {
            val deviation = deviationParser.findCoveringDeviation(
                deviations, "nondeterminismSources", "*", currentCount
            )
            if (deviation != null) {
                accepted.add("Nondeterminism sources increased from $committedCount to $currentCount — accepted by ${deviation.id}")
            } else {
                failures.add(
                    "Nondeterminism source count regressed: $committedCount → $currentCount. " +
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
        val committedEntries = committed.protocolCatalog.entries.associateBy { it.name }
        val currentEntries = current.protocolCatalog.entries.associateBy { it.name }

        // Stable contract entries must not be removed
        for ((name, entry) in committedEntries) {
            if (entry.stability == "stable-contract" && name !in currentEntries) {
                failures.add("Stable protocol contract removed: $name (${entry.category})")
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
        failures: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val committedTop = committed.structural.structuralHotspots.largestProductionFiles.take(5).map { it.path }.toSet()
        val currentTop = current.structural.structuralHotspots.largestProductionFiles.take(5).map { it.path }.toSet()

        val newInTop = currentTop - committedTop
        if (newInTop.isNotEmpty()) {
            warnings.add("New files entered top-5 production hotspots: ${newInTop.joinToString(", ")}")
        }

        // Check if any existing hotspot got worse
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

    companion object {
        fun verify(project: Project): VerificationReport {
            val generator = BaselineGenerator(project)
            val reportDir = File(project.layout.buildDirectory.get().asFile, "reports/maintainability")
            return BaselineVerifier(generator, project.rootDir, reportDir).verify()
        }
    }
}
