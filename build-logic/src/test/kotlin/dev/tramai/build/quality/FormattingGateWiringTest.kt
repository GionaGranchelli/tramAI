package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Durable aggregate-wiring tests for the Epic 10.1a incremental Kotlin formatting
 * gate: `check` and `verifyPr` must both include the gate in their task graphs.
 * A future edit that detaches the gate from the aggregate tasks fails this suite.
 * See [FormattingGateContractTestBase].
 */
class FormattingGateWiringTest : FormattingGateContractTestBase() {
    @Test
    fun `check owns the gate`() {
        val run = gradle("--no-build-cache", "check", "--dry-run")
        assertPasses(run, "check --dry-run")
        assertTrue(
            run.output.contains(":spotlessCheck"),
            "check must include spotlessCheck. Output: ${run.output.take(1200)}",
        )
    }

    @Test
    fun `verifyPr owns the gate`() {
        // gradleUntil: the graph (including :spotlessCheck) is printed before the
        // full verifyPr graph hangs locally (pre-existing Gradle 9 / node issue).
        val run = gradleUntil(":spotlessCheck", "--no-build-cache", "verifyPr", "--dry-run")
        assertTrue(
            run.output.contains(":spotlessCheck"),
            "verifyPr must include spotlessCheck in its task graph. Output: ${run.output.take(1200)}",
        )
    }
}
