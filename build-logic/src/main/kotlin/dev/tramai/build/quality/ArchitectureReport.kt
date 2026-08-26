package dev.tramai.build.quality

import java.io.File

enum class ArchitectureCheckStatus { PASS, FAIL }

data class ArchitectureCheckResult(
    val id: String,
    val status: ArchitectureCheckStatus,
    val diagnostics: List<VerificationDiagnostic>,
)

data class ArchitectureVerificationSummary(
    val checks: Int,
    val passed: Int,
    val failed: Int,
)

data class ArchitectureVerificationReport(
    val schemaVersion: String = "1",
    val status: ArchitectureCheckStatus,
    val checks: List<ArchitectureCheckResult>,
    val summary: ArchitectureVerificationSummary,
)

object ArchitectureReportAggregator {
    val checkIds: Set<String> = setOf(
        "module-manifest",
        "publishing-topology",
        "dependency-boundaries",
        "dependency-cycles",
        "global-state",
        "api-architecture",
        "protocol-catalog",
        "cancellation-safety",
        "provider-contracts",
        "store-contracts",
    )

    fun aggregate(checkDiagnostics: Map<String, List<VerificationDiagnostic>>): ArchitectureVerificationReport {
        require(checkDiagnostics.keys == checkIds) {
            "Architecture aggregator contract: expected exactly ${checkIds.size} stable check ids " +
                "${checkIds.sorted()}, but received ${checkDiagnostics.keys.sorted()}"
        }
        val checks = checkDiagnostics
            .map { (id, diagnostics) ->
                val sortedDiagnostics = diagnostics.sortedWith(
                    compareBy<VerificationDiagnostic> { it.code.name }
                        .thenBy { it.severity.name }
                        .thenBy { it.message }
                        .thenBy { it.modulePath.orEmpty() }
                        .thenBy { it.findingId.orEmpty() }
                        .thenBy { it.deviationId.orEmpty() }
                )
                ArchitectureCheckResult(
                    id = id,
                    status = if (sortedDiagnostics.any { it.severity == DiagnosticSeverity.FAILURE }) {
                        ArchitectureCheckStatus.FAIL
                    } else {
                        ArchitectureCheckStatus.PASS
                    },
                    diagnostics = sortedDiagnostics,
                )
            }
            .sortedBy { it.id }
        val failed = checks.count { it.status == ArchitectureCheckStatus.FAIL }
        return ArchitectureVerificationReport(
            status = if (failed == 0) ArchitectureCheckStatus.PASS else ArchitectureCheckStatus.FAIL,
            checks = checks,
            summary = ArchitectureVerificationSummary(
                checks = checks.size,
                passed = checks.size - failed,
                failed = failed,
            ),
        )
    }
}

object ArchitectureReportJson {
    fun write(report: ArchitectureVerificationReport, outputFile: File, repoRoot: File) {
        ReportNormalizer.writeJson(toJsonValue(report, repoRoot), outputFile)
    }

    fun toJson(report: ArchitectureVerificationReport, repoRoot: File): String =
        ReportNormalizer.toJson(toJsonValue(report, repoRoot))

    private fun toJsonValue(report: ArchitectureVerificationReport, repoRoot: File): Map<String, Any> = mapOf(
        "schemaVersion" to report.schemaVersion,
        "status" to report.status.name,
        "checks" to report.checks.map { check ->
            mapOf(
                "id" to check.id,
                "status" to check.status.name,
                "diagnostics" to check.diagnostics.map { diagnosticJson(it, repoRoot) },
            )
        },
        "summary" to mapOf(
            "checks" to report.summary.checks,
            "passed" to report.summary.passed,
            "failed" to report.summary.failed,
        ),
    )

    private fun diagnosticJson(diagnostic: VerificationDiagnostic, repoRoot: File): Map<String, String> = buildMap {
        put("code", diagnostic.code.name)
        put("severity", diagnostic.severity.name)
        put("message", sanitizePaths(diagnostic.message, repoRoot))
        diagnostic.modulePath?.let { put("modulePath", it) }
        diagnostic.findingId?.let { put("findingId", sanitizePaths(it, repoRoot)) }
        diagnostic.deviationId?.let { put("deviationId", it) }
        diagnostic.baselineValue?.let { put("baselineValue", sanitizePaths(it, repoRoot)) }
        diagnostic.currentValue?.let { put("currentValue", sanitizePaths(it, repoRoot)) }
    }

    /** Replaces the repository checkout root with a portable placeholder so failure
     *  reports are path-independent across machines. */
    private fun sanitizePaths(value: String, repoRoot: File): String {
        val root = repoRoot.absolutePath
        return value.replace(root, "<repo-root>")
            .replace(root.replace(File.separatorChar, '/'), "<repo-root>")
            .replace(root.replace('/', File.separatorChar), "<repo-root>")
    }
}

/**
 * Reads per-project fail-soft dependency probe outputs for the architecture
 * gate. A probe that hit a resolution failure writes a typed marker instead of
 * throwing, so the gate can fail closed with evidence rather than the task
 * graph aborting before the report is written.
 */
internal data class DependencyProbeEvidence(
    val resolvedRecords: List<ResolvedDependency>,
    val failures: List<String>,
)

internal fun readDependencyProbeEvidence(probeFiles: List<File>): DependencyProbeEvidence {
    val resolvedRecords = mutableListOf<ResolvedDependency>()
    val failures = mutableListOf<String>()
    probeFiles.forEach { file ->
        if (!file.isFile) {
            failures += "Missing dependency probe output: ${file.path}"
            return@forEach
        }
        try {
            val raw = ReportNormalizer.readJson(file, Array<Any>::class.java).toList()
            val failed = raw.firstOrNull { (it as? Map<*, *>)?.get("resolutionFailed") == true }
            if (failed != null) {
                failures += (failed as Map<*, *>)["message"]?.toString() ?: "unknown dependency resolution failure"
            } else {
                resolvedRecords += ReportNormalizer.readJson(file, Array<ResolvedDependency>::class.java).toList()
            }
        } catch (exception: Exception) {
            failures += "Invalid dependency probe output ${file.path}: ${exception.message}"
        }
    }
    return DependencyProbeEvidence(resolvedRecords, failures)
}

/**
 * Codes whose FAILURE presence means the baseline verifier could not establish
 * its mandatory evidence (it early-returns without running the architecture
 * checks). These must fail EVERY baseline-backed check: the gate cannot claim
 * a check passed when its evidence was never produced.
 */
internal val baselineEvidenceFailureCodes: Set<DiagnosticCode> = setOf(
    DiagnosticCode.EMPTY_SECTION,
    DiagnosticCode.DEPENDENCY_RESOLUTION_FAILED,
)

/**
 * Routes baseline diagnostics into the architecture gate. If the baseline
 * verifier early-returned with evidence-unavailable FAILURE diagnostics
 * ([baselineEvidenceFailureCodes]), every baseline-backed check fails closed.
 * Otherwise diagnostics are routed through [classify] (the exhaustive
 * DiagnosticCode → check-id mapping).
 */
internal fun routeBaselineDiagnostics(
    diagnostics: List<VerificationDiagnostic>,
    target: Map<String, MutableList<VerificationDiagnostic>>,
    baselineCheckIds: Set<String>,
    classify: (DiagnosticCode) -> String?,
) {
    val evidenceFailures = diagnostics.filter {
        it.severity == DiagnosticSeverity.FAILURE && it.code in baselineEvidenceFailureCodes
    }
    if (evidenceFailures.isNotEmpty()) {
        baselineCheckIds.forEach { checkId ->
            target.getValue(checkId) += evidenceFailures
        }
    } else {
        diagnostics.forEach { diagnostic ->
            classify(diagnostic.code)?.let { target.getValue(it) += diagnostic }
        }
    }
}

/**
 * Fail-closed evidence collection for the architecture gate: an exception in an
 * evidence source becomes FAILURE diagnostics on the affected checks instead of
 * aborting before the report is written. The final report write must happen
 * after all collectEvidence calls and before the terminal exception.
 */
internal fun collectEvidence(
    label: String,
    affectedChecks: Set<String>,
    target: Map<String, MutableList<VerificationDiagnostic>>,
    evidence: () -> Unit,
) {
    try {
        evidence()
    } catch (exception: Exception) {
        affectedChecks.forEach { checkId ->
            target.getValue(checkId) += VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION,
                "$label could not complete: ${exception.message ?: exception.javaClass.name}",
            )
        }
    }
}

/**
 * Pinned identities of every enrollment architecture guard the 0.6.0 gate
 * requires. Discovery is by exact class identity, not by count: deleting or
 * renaming one of these classes must fail the gate even if the others still run.
 */
internal val enrollmentArchitectureTestClasses: Set<String> = setOf(
    "dev.tramai.testing.ProviderTckEnrollmentArchitectureTest",
    "dev.tramai.testing.ApprovalContinuationStoreTckEnrollmentArchitectureTest",
    "dev.tramai.testing.ApprovalStoreTckEnrollmentArchitectureTest",
    "dev.tramai.testing.AuditStoreTckEnrollmentArchitectureTest",
    "dev.tramai.testing.ChatMemoryStoreTckEnrollmentArchitectureTest",
    "dev.tramai.testing.SovereignOpsAuditOutboxStoreTckEnrollmentArchitectureTest",
    "dev.tramai.testing.SuspendedInvocationStoreTckEnrollmentArchitectureTest",
    "dev.tramai.testing.WorkflowCheckpointStoreTckEnrollmentArchitectureTest",
    "dev.tramai.testing.WorkflowLeaseCheckpointFenceTckEnrollmentArchitectureTest",
    "dev.tramai.testing.WorkflowLeaseStoreTckEnrollmentArchitectureTest",
)

/**
 * Compares discovered enrollment test classes against the pinned identities.
 * Returns check-id → FAILURE diagnostics for missing (deleted/renamed) guards
 * and for unexpected discovered classes (identity drift).
 */
internal fun enrollmentGuardDiagnostics(discoveredClasses: Set<String>): Map<String, List<VerificationDiagnostic>> {
    val providerClass = "dev.tramai.testing.ProviderTckEnrollmentArchitectureTest"
    fun route(className: String): String = if (className == providerClass) "provider-contracts" else "store-contracts"
    val result = mutableMapOf<String, MutableList<VerificationDiagnostic>>()
    (enrollmentArchitectureTestClasses - discoveredClasses).forEach { className ->
        result.getOrPut(route(className)) { mutableListOf() }.add(
            VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION,
                "Expected enrollment architecture test $className was not discovered — its guard may have been deleted or renamed",
            ),
        )
    }
    (discoveredClasses - enrollmentArchitectureTestClasses).forEach { className ->
        result.getOrPut(route(className)) { mutableListOf() }.add(
            VerificationDiagnostic.failure(
                DiagnosticCode.EMPTY_SECTION,
                "Unexpected enrollment architecture test $className was discovered — guard identity drifted",
            ),
        )
    }
    return result
}
