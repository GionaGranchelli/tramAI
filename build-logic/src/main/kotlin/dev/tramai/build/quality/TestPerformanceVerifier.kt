package dev.tramai.build.quality

class TestPerformanceVerifier(private val configuration: TestQualityConfiguration) {
    fun verify(committed: TestPerformanceData, current: TestPerformanceData): List<VerificationDiagnostic> {
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        if (committed.status != "measured" || current.status != "measured") {
            diagnostics += VerificationDiagnostic.failure(
                DiagnosticCode.TEST_REPORT_MISSING,
                "Test-performance baseline and current observations must both be measured"
            )
            return diagnostics
        }

        configuration.criticalModules.forEach { module ->
            val baseline = committed.byModule[module]
            val measured = current.byModule[module]
            if (baseline == null || measured == null) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.TEST_REPORT_MISSING,
                    "Missing expected test-performance report for $module",
                    modulePath = module
                )
                return@forEach
            }
            if (regression(baseline.medianDurationMs, measured.medianDurationMs) > 25.0) {
                diagnostics += VerificationDiagnostic.warning(
                    DiagnosticCode.TEST_PERFORMANCE_REGRESSION,
                    "$module median test duration regressed from ${baseline.medianDurationMs}ms to ${measured.medianDurationMs}ms"
                )
            }
        }

        // Use byIdentity from allTests for full test comparison
        val baselineByIdentity = committed.byIdentity
        val currentByIdentity = current.byIdentity

        // Check for newly skipped tests across all tests
        currentByIdentity.forEach { (identity, measured) ->
            val baseline = baselineByIdentity[identity] ?: return@forEach
            if (regression(baseline.durationMs, measured.durationMs) > 50.0) {
                diagnostics += VerificationDiagnostic.warning(
                    DiagnosticCode.CRITICAL_TEST_REGRESSION,
                    "${measured.module}:${measured.className}.${measured.testName} regressed from " +
                        "${baseline.durationMs}ms to ${measured.durationMs}ms"
                )
            }
            if (!baseline.skipped && measured.skipped) {
                diagnostics += VerificationDiagnostic.failure(
                    DiagnosticCode.CRITICAL_TEST_NEWLY_SKIPPED,
                    "Critical test newly skipped: ${measured.module}:${measured.className}.${measured.testName}",
                    modulePath = measured.module
                )
            }
        }

        // Verify same test identities exist across committed and current
        val baselineIds = baselineByIdentity.keys
        val currentIds = currentByIdentity.keys
        val missingTests = baselineIds - currentIds
        val newTests = currentIds - baselineIds
        if (missingTests.isNotEmpty()) {
            diagnostics += VerificationDiagnostic.warning(
                DiagnosticCode.CRITICAL_TEST_NEWLY_SKIPPED,
                "${missingTests.size} test(s) from the baseline are missing in current measurements"
            )
        }
        if (newTests.isNotEmpty()) {
            diagnostics += VerificationDiagnostic.warning(
                DiagnosticCode.CRITICAL_TEST_NEWLY_SKIPPED,
                "${newTests.size} new test(s) appeared in current measurements"
            )
        }

        return diagnostics
    }

    private fun regression(baseline: Long, current: Long): Double =
        if (baseline <= 0) {
            if (current <= 0) 0.0 else 100.0
        } else {
            (current - baseline) * 100.0 / baseline
        }
}
