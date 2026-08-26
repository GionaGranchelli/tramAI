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
    }
}
