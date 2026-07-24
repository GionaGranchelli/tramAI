package dev.tramai.build.quality

class CoverageBaselineVerifier(private val configuration: TestQualityConfiguration) {
    fun verify(committed: CoverageData, current: CoverageData): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        if (committed.status == "pending" || current.status == "pending") {
            diagnostics += VerificationDiagnostic.failure(
                DiagnosticCode.TEST_QUALITY_STATUS_PENDING,
                "Coverage status must never remain pending"
            )
        }
        if (committed.status != "measured" || current.status != "measured") {
            diagnostics += VerificationDiagnostic.failure(
                DiagnosticCode.COVERAGE_REPORT_MISSING,
                "Coverage baseline and current coverage must both be measured"
            )
            return diagnostics
        }

        val tolerance = configuration.coverage.regressionTolerancePercentagePoints
        configuration.criticalModules.forEach { module ->
            val baseline = committed.criticalModules[module]
            val measured = current.criticalModules[module]
            if (baseline == null || measured == null) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_REPORT_MISSING,
                    "Missing critical coverage measurement for $module",
                    modulePath = module
                )
                return@forEach
            }
            compare(module, "line", baseline.lineCoverage, measured.lineCoverage, tolerance, diagnostics)
            compare(module, "branch", baseline.branchCoverage, measured.branchCoverage, tolerance, diagnostics)
        }

        configuration.mutation.targetFamilies.forEach { (family, target) ->
            val covered = target.modules.sumOf { current.criticalModules[it]?.linesCovered ?: 0 }
            if (covered == 0) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_FAMILY_EMPTY,
                    "Critical behaviour family '$family' has no covered executable lines"
                )
            }
        }

        val documented = committed.exclusions.associate { it.pattern to it.reason }
        current.exclusions.forEach { exclusion ->
            if (documented[exclusion.pattern] != exclusion.reason) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.COVERAGE_EXCLUSION_UNDOCUMENTED,
                    "Coverage exclusion '${exclusion.pattern}' is new or has no matching documented reason"
                )
            }
        }

        return diagnostics
    }

    private fun compare(
        module: String,
        counter: String,
        baseline: Double,
        current: Double,
        tolerance: Double,
        diagnostics: MutableList<VerificationDiagnostic>
    ) {
        if (current + tolerance < baseline) {
            diagnostics += VerificationDiagnostic.failure(
                DiagnosticCode.COVERAGE_REGRESSION,
                "$module $counter coverage regressed from ${"%.2f".format(baseline)}% to ${"%.2f".format(current)}% " +
                    "(tolerance ${"%.2f".format(tolerance)} percentage points)",
                modulePath = module,
                baselineValue = baseline.toString(),
                currentValue = current.toString()
            )
        }
    }
}
