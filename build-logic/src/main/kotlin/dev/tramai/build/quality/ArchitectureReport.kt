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
    fun write(report: ArchitectureVerificationReport, outputFile: File) {
        ReportNormalizer.writeJson(toJsonValue(report), outputFile)
    }

    fun toJson(report: ArchitectureVerificationReport): String =
        ReportNormalizer.toJson(toJsonValue(report))

    private fun toJsonValue(report: ArchitectureVerificationReport): Map<String, Any> = mapOf(
        "schemaVersion" to report.schemaVersion,
        "status" to report.status.name,
        "checks" to report.checks.map { check ->
            mapOf(
                "id" to check.id,
                "status" to check.status.name,
                "diagnostics" to check.diagnostics.map(::diagnosticJson),
            )
        },
        "summary" to mapOf(
            "checks" to report.summary.checks,
            "passed" to report.summary.passed,
            "failed" to report.summary.failed,
        ),
    )

    private fun diagnosticJson(diagnostic: VerificationDiagnostic): Map<String, String> = buildMap {
        put("code", diagnostic.code.name)
        put("severity", diagnostic.severity.name)
        put("message", diagnostic.message)
        diagnostic.modulePath?.let { put("modulePath", it) }
        diagnostic.findingId?.let { put("findingId", it) }
        diagnostic.deviationId?.let { put("deviationId", it) }
        diagnostic.baselineValue?.let { put("baselineValue", it) }
        diagnostic.currentValue?.let { put("currentValue", it) }
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
