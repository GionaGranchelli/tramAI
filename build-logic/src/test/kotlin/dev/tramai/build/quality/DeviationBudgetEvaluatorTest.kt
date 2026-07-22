package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Focused production-path tests for DeviationBudgetEvaluator.
 *
 * These tests invoke the actual policy code (evaluateRiskWorsening,
 * evaluateDeviationBudget) with controlled inputs — no Gradle project,
 * no file I/O, no mock baseline documents.
 */
class DeviationBudgetEvaluatorTest {

    private val parser = DeviationParser(File("/nonexistent"))

    // ─── Test helpers ───

    /** Create a deviation entry for testing. */
    private fun dev(
        id: String,
        metric: String,
        scope: String = ":tramai-core",
        baseline: Int = 0,
        allowed: Int = 5
    ) = DeviationParser.DeviationEntry(
        id = id, metric = metric, scope = scope,
        baseline = baseline, allowed = allowed,
        reason = "test", acceptedAt = "2026-07-22",
        targetPhase = "0.7.0", owner = "test"
    )

    /** Create a cancellation catch finding for testing. */
    private fun catchFinding(
        module: String = ":tramai-core",
        file: String = "test.kt",
        function: String = "foo",
        catchType: String = "Exception",
        risk: String = "medium"
    ) = CancellationCatchFinding(
        module = module, file = file, function = function,
        catchType = catchType, risk = risk
    )

    /** Run evaluateRiskWorsening with given inputs and return diagnostics. */
    private fun evaluateRiskWorsening(
        metricName: String = "cancellationRiskWorsening",
        currentCatches: List<CancellationCatchFinding>,
        committedCatches: List<CancellationCatchFinding>,
        deviations: List<DeviationParser.DeviationEntry> = emptyList()
    ): List<VerificationDiagnostic> {
        val evaluator = DeviationBudgetEvaluator(parser)
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val committedIds = committedCatches.map {
            FindingIdentity.fromCancellationCatch(it).toIdentityKey()
        }
        evaluator.evaluateRiskWorsening(
            metricName = metricName,
            deviations = deviations,
            allCurrent = currentCatches.toList<Any?>(),
            committedIds = committedIds,
            committedFindings = committedCatches.toList<Any?>(),
            toModuleScope = { f -> DeviationBudgetEvaluator.moduleScope(f) },
            riskWorseCode = DiagnosticCode.CANCELLATION_RISK_WORSENED,
            diagnostics = diagnostics
        )
        return diagnostics
    }

    /** Run evaluateDeviationBudget with given inputs and return diagnostics. */
    private fun evaluateDeviationBudget(
        metricName: String = "cancellationCriticalCount",
        currentCatches: List<CancellationCatchFinding>,
        committedCatches: List<CancellationCatchFinding>,
        deviations: List<DeviationParser.DeviationEntry> = emptyList(),
        riskFilter: ((Any?) -> Boolean)? = { f -> (f as CancellationCatchFinding).risk == "critical" },
        riskWorseCode: DiagnosticCode? = DiagnosticCode.CANCELLATION_RISK_WORSENED,
        riskWorseningMetricName: String? = "cancellationRiskWorsening"
    ): List<VerificationDiagnostic> {
        val evaluator = DeviationBudgetEvaluator(parser)
        val diagnostics = mutableListOf<VerificationDiagnostic>()
        val committedIds = committedCatches.map {
            FindingIdentity.fromCancellationCatch(it).toIdentityKey()
        }
        val currentIds = currentCatches.map {
            FindingIdentity.fromCancellationCatch(it).toIdentityKey()
        }
        evaluator.evaluateDeviationBudget(
            metricName = metricName,
            deviations = deviations,
            committedIds = committedIds,
            currentIds = currentIds,
            allCurrent = currentCatches.toList<Any?>(),
            riskFilter = riskFilter,
            toModuleScope = { f -> DeviationBudgetEvaluator.moduleScope(f) },
            diagnosticCode = DiagnosticCode.NEW_CANCELLATION_FINDING,
            riskWorseCode = riskWorseCode,
            riskWorseningMetricName = riskWorseningMetricName,
            committedFindings = committedCatches.toList<Any?>(),
            diagnostics = diagnostics
        )
        return diagnostics
    }

    // ─── Test 1: low → critical with unchanged identity ───

    @Test
    fun `low to critical with unchanged identity produces CANCELLATION_RISK_WORSENED`() {
        val committed = listOf(
            catchFinding(risk = "low")
        )
        val current = listOf(
            catchFinding(risk = "critical")
        )

        val diagnostics = evaluateRiskWorsening(
            currentCatches = current,
            committedCatches = committed
        )

        val worsened = diagnostics.filter {
            it.code == DiagnosticCode.CANCELLATION_RISK_WORSENED &&
                it.severity == DiagnosticSeverity.FAILURE
        }
        assertEquals(1, worsened.size, "low → critical must produce a failure")
        assertTrue(worsened[0].message.contains("risk worsened"),
            "Message must mention risk worsening: ${worsened[0].message}")
    }

    // ─── Test 2: cancellationRiskWorsening deviation can cover it ───

    @Test
    fun `cancellationRiskWorsening deviation can authorize the worsening`() {
        val committed = listOf(
            catchFinding(risk = "low")
        )
        val current = listOf(
            catchFinding(risk = "critical")
        )
        val deviations = listOf(
            dev(id = "MQ-RW-1", metric = "cancellationRiskWorsening",
                scope = ":tramai-core", baseline = 0, allowed = 1)
        )

        val diagnostics = evaluateRiskWorsening(
            metricName = "cancellationRiskWorsening",
            currentCatches = current,
            committedCatches = committed,
            deviations = deviations
        )

        // Must be ACCEPTED, not FAILURE
        val failures = diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }
        val accepted = diagnostics.filter { it.severity == DiagnosticSeverity.ACCEPTED }
        assertEquals(0, failures.size, "Deviation should authorize — no failures expected")
        assertEquals(1, accepted.size, "Must produce exactly one ACCEPTED diagnostic")
        assertEquals("MQ-RW-1", accepted[0].deviationId,
            "Accepted diagnostic must reference the covering deviation")
    }

    // ─── Test 3: cancellationCriticalCount deviation cannot cover it ───

    @Test
    fun `cancellationCriticalCount deviation cannot authorize risk worsening`() {
        val committed = listOf(
            catchFinding(risk = "low")
        )
        val current = listOf(
            catchFinding(risk = "critical")
        )
        // Only cancellationCriticalCount deviation, no cancellationRiskWorsening
        val deviations = listOf(
            dev(id = "MQ-CC-1", metric = "cancellationCriticalCount",
                scope = ":tramai-core", baseline = 0, allowed = 10)
        )

        val diagnostics = evaluateRiskWorsening(
            metricName = "cancellationRiskWorsening",
            currentCatches = current,
            committedCatches = committed,
            deviations = deviations
        )

        val failures = diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }
        val accepted = diagnostics.filter { it.severity == DiagnosticSeverity.ACCEPTED }
        assertEquals(1, failures.size,
            "cancellationCriticalCount deviation must NOT authorize risk worsening — must fail")
        assertEquals(0, accepted.size,
            "No ACCEPTED diagnostics when only cancellationCriticalCount deviation exists")
        assertTrue(failures[0].message.contains("risk worsened"),
            "Failure must mention risk worsening: ${failures[0].message}")
    }

    // ─── Test 4: non-critical catches don't consume critical budget ───

    @Test
    fun `non-critical catches do not consume critical allowance`() {
        // Committed: 1 critical catch
        // Current: same critical catch + 5 non-critical catches
        // Deviation allows: 1 critical catch in scope
        // Adding non-critical catches must NOT overflow the critical budget
        val committed = listOf(
            catchFinding(risk = "critical")
        )
        val current = listOf(
            catchFinding(risk = "critical"),   // same identity, still critical
            catchFinding(function = "bar", risk = "low"),     // new, low
            catchFinding(function = "baz", risk = "medium"),  // new, medium
            catchFinding(function = "qux", risk = "high"),    // new, high — but different identity so new finding
            catchFinding(file = "other.kt", risk = "low"),
            catchFinding(file = "other2.kt", risk = "medium")
        )
        // Deviation allows exactly 1 critical catch in :tramai-core
        val deviations = listOf(
            dev(id = "MQ-CC-1", metric = "cancellationCriticalCount",
                scope = ":tramai-core", baseline = 1, allowed = 1)
        )

        val diagnostics = evaluateDeviationBudget(
            metricName = "cancellationCriticalCount",
            currentCatches = current,
            committedCatches = committed,
            deviations = deviations,
            riskFilter = { f -> (f as CancellationCatchFinding).risk == "critical" }
        )

        // Critical count: there is still exactly 1 critical catch in scope
        // (the original one has the same identity). No new critical catches added.
        // The 5 non-critical catches must NOT be counted against the critical allowance.
        val criticalFailures = diagnostics.filter {
            it.code == DiagnosticCode.NEW_CANCELLATION_FINDING &&
                it.severity == DiagnosticSeverity.FAILURE
        }
        assertEquals(0, criticalFailures.size,
            "Non-critical catches must NOT consume the critical allowance")
    }
}
