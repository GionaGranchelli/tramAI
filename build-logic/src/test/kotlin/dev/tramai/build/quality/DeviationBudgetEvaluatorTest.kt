package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Focused production-path tests for DeviationBudgetEvaluator.
 *
 * These tests invoke the actual policy code (evaluateDeviationBudget,
 * evaluateRiskWorsening) with controlled inputs — no Gradle project,
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
        val committed = listOf(catchFinding(risk = "low"))
        val current = listOf(catchFinding(risk = "critical"))

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

    // ─── Test 2: cancellationRiskWorsening deviation through the routing layer ───
    //
    // Calls evaluateDeviationBudget() — the same entry point the production
    // verifier uses. This proves the routing forwards risk-worsening deviations
    // to evaluateRiskWorsening with the correct metric name.

    @Test
    fun `cancellationRiskWorsening deviation via budget evaluator authorizes worsening`() {
        val committed = listOf(catchFinding(risk = "low"))
        val current = listOf(catchFinding(risk = "critical"))
        // Only a cancellationRiskWorsening deviation exists
        val deviations = listOf(
            dev(id = "MQ-RW-1", metric = "cancellationRiskWorsening",
                scope = ":tramai-core", baseline = 0, allowed = 1)
        )

        val diagnostics = evaluateDeviationBudget(
            metricName = "cancellationCriticalCount",
            currentCatches = current,
            committedCatches = committed,
            deviations = deviations,
            riskWorseCode = DiagnosticCode.CANCELLATION_RISK_WORSENED,
            riskWorseningMetricName = "cancellationRiskWorsening"
        )

        // The risk worsening must be ACCEPTED through the routing layer
        val failures = diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }
        val accepted = diagnostics.filter { it.severity == DiagnosticSeverity.ACCEPTED }
        assertEquals(0, failures.size,
            "cancellationRiskWorsening deviation via routing must authorize — no failures")
        assertEquals(1, accepted.size,
            "Must produce one ACCEPTED diagnostic through the routing layer")
        assertEquals("MQ-RW-1", accepted[0].deviationId,
            "Accepted diagnostic must reference the covering deviation")
        assertEquals(DiagnosticCode.CANCELLATION_RISK_WORSENED, accepted[0].code,
            "Diagnostic code must be CANCELLATION_RISK_WORSENED, not NEW_CANCELLATION_FINDING")
    }

    // ─── Test 3: cancellationCriticalCount deviation cannot authorize through routing ───
    //
    // Calls evaluateDeviationBudget() with only a cancellationCriticalCount deviation.
    // The evaluator must route risk worsening to the cancellationRiskWorsening metric,
    // not to cancellationCriticalCount. With no matching deviation, it must FAIL.

    @Test
    fun `cancellationCriticalCount deviation via budget evaluator cannot authorize worsening`() {
        val committed = listOf(catchFinding(risk = "low"))
        val current = listOf(catchFinding(risk = "critical"))
        // Only a cancellationCriticalCount deviation exists — no cancellationRiskWorsening
        val deviations = listOf(
            dev(id = "MQ-CC-1", metric = "cancellationCriticalCount",
                scope = ":tramai-core", baseline = 0, allowed = 10)
        )

        val diagnostics = evaluateDeviationBudget(
            metricName = "cancellationCriticalCount",
            currentCatches = current,
            committedCatches = committed,
            deviations = deviations,
            riskWorseCode = DiagnosticCode.CANCELLATION_RISK_WORSENED,
            riskWorseningMetricName = "cancellationRiskWorsening"
        )

        // The risk worsening must NOT be authorized via cancellationCriticalCount
        val failures = diagnostics.filter { it.severity == DiagnosticSeverity.FAILURE }
        val accepted = diagnostics.filter { it.severity == DiagnosticSeverity.ACCEPTED }
        assertEquals(1, failures.size,
            "cancellationCriticalCount deviation must NOT authorize risk worsening through routing")
        assertEquals(0, accepted.size,
            "No ACCEPTED diagnostics when only cancellationCriticalCount deviation exists")
        assertTrue(failures[0].message.contains("risk worsened"),
            "Failure must mention risk worsening: ${failures[0].message}")
    }

    // ─── Test 4: non-critical catches don't consume critical budget ───
    //
    // Committed: 1 critical catch.
    // Current: same critical + 1 new critical catch + 5 non-critical catches.
    // Deviation allows: 2 critical catches in scope.
    //
    // With correct implementation (riskFilter on matchingAll):
    //   matchingAll = 2 (only critical catches) ≤ allowed=2 → ACCEPTED
    //
    // With the old bug (no riskFilter on matchingAll):
    //   matchingAll = 7 (all catches including non-critical) > allowed=2 → FAILURE

    @Test
    fun `non-critical catches do not consume critical allowance`() {
        val committed = listOf(
            catchFinding(risk = "critical")                           // identity: foo
        )
        val current = listOf(
            catchFinding(risk = "critical"),                          // same identity: foo — still critical
            catchFinding(file = "new-critical.kt", risk = "critical"), // NEW critical catch — different identity
            catchFinding(function = "noncrit1", risk = "low"),
            catchFinding(function = "noncrit2", risk = "medium"),
            catchFinding(function = "noncrit3", risk = "high"),
            catchFinding(file = "other.kt", risk = "low"),
            catchFinding(file = "other2.kt", risk = "medium")
        )
        // Deviation allows exactly 2 critical catches in :tramai-core
        val deviations = listOf(
            dev(id = "MQ-CC-1", metric = "cancellationCriticalCount",
                scope = ":tramai-core", baseline = 1, allowed = 2)
        )

        val diagnostics = evaluateDeviationBudget(
            metricName = "cancellationCriticalCount",
            currentCatches = current,
            committedCatches = committed,
            deviations = deviations,
            riskFilter = { f -> (f as CancellationCatchFinding).risk == "critical" }
        )

        // With correct riskFilter: matchingAll = 2 critical catches ≤ allowed=2
        // The new critical catch should be ACCEPTED
        val criticalFailures = diagnostics.filter {
            it.code == DiagnosticCode.NEW_CANCELLATION_FINDING &&
                it.severity == DiagnosticSeverity.FAILURE
        }
        val accepted = diagnostics.filter {
            it.code == DiagnosticCode.NEW_CANCELLATION_FINDING &&
                it.severity == DiagnosticSeverity.ACCEPTED
        }
        assertEquals(0, criticalFailures.size,
            "Non-critical catches must NOT consume the critical allowance — no failures expected")
        assertTrue(accepted.any { it.message.contains("new finding") },
            "New critical catch should be accepted within budget")
    }

    // ─── Test 5: multiset delta reports only exact new occurrences ───

    @Test
    fun `duplicate identity growth reports only the count delta not all occurrences`() {
        // Identity X appears once in committed, twice in current (critical+medium share same identity).
        // The medium occurrence creates delta=1 for that identity. After riskFilter only the critical
        // occurrence is counted as "added". Brand-new identities (bar, baz, qux) each add 1.
        // Total added = 1 (from duplicated identity under filter) + 3 (new identities) = 4.
        // Without the multiset fix the reported count would be 5 (all current filtered findings).
        val committed = listOf(
            catchFinding(risk = "critical")                             // identity: foo/Exception
        )
        val current = listOf(
            catchFinding(risk = "critical"),                            // same identity — critical
            catchFinding(function = "foo", risk = "medium"),            // SAME identity, delta=1 — filtered out by riskFilter
            catchFinding(function = "bar", risk = "critical"),          // NEW identity
            catchFinding(function = "baz", risk = "critical"),          // NEW identity
            catchFinding(function = "qux", risk = "critical")           // NEW identity
        )
        val deviations = listOf(
            dev(id = "MQ-CC-1", metric = "cancellationCriticalCount",
                scope = ":tramai-core", baseline = 1, allowed = 5)
        )

        val diagnostics = evaluateDeviationBudget(
            metricName = "cancellationCriticalCount",
            currentCatches = current,
            committedCatches = committed,
            deviations = deviations
        )

        val accepted = diagnostics.filter {
            it.code == DiagnosticCode.NEW_CANCELLATION_FINDING &&
                it.severity == DiagnosticSeverity.ACCEPTED
        }
        assertTrue(accepted.isNotEmpty(), "Deviation should accept the new findings")
        val message = accepted.first().message
        // 4 new findings: 1 from duplicated identity under critical filter + bar + baz + qux
        assertTrue(
            message.contains("4 new finding"),
            "Must report exactly 4 new findings (delta-driven, not all occurrences), got: $message"
        )
    }
}
