package dev.tramai.build.quality

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression test for the second observed gate defect: `architectureContractEnrollmentTest`
 * reads provider sources off disk but declared none of them as task inputs, so editing a
 * scanned source left it UP-TO-DATE and `verify060Architecture` re-reported its previous
 * verdict — a stale red after a real fix, and a stale green after a regression.
 *
 * Runs against the disposable worktree, which is created from the repository's committed
 * HEAD: this test observes the *committed* state, so it must be run after committing (which
 * is how the harness is designed to work).
 *
 * Assertions are made on the enrollment task's own status line, never on the whole log: a
 * Gradle log carries `UP-TO-DATE` for unrelated tasks, which makes a whole-output `contains`
 * assertion pass or fail for reasons unrelated to this task. The settled run is asserted
 * UP-TO-DATE first as a positive control, so the final assertion cannot pass vacuously.
 *
 * The probe lives in a module that is not on this task's classpath and is not compiled by its
 * graph, so only the declared scanned-source inputs can explain the invalidation. A module on
 * the classpath (such as tramai-standalone) would confound it: its rebuilt jar changes the
 * pre-existing classpath input, so the test would pass with or without the fix.
 */
class ArchitectureEnrollmentInvalidationTest : StaticAnalysisContractTestBase() {
    /** The status line Gradle prints for the enrollment task, if it ran at all. */
    private fun enrollmentLine(output: String): String =
        output
            .lines()
            .filter { it.contains("> Task :architectureContractEnrollmentTest") }
            .joinToString("\n")

    @Test
    fun `C1 editing a scanned source makes the enrollment task rerun without --rerun-tasks`() {
        gradle(":architectureContractEnrollmentTest", "--console=plain")
        val settled = gradle(":architectureContractEnrollmentTest", "--console=plain")
        val settledLine = enrollmentLine(settled.output)
        assertTrue(
            settledLine.isNotEmpty(),
            "positive control failed: no enrollment task line in the settled run at all. " +
                "Output tail: ${settled.output.takeLast(1500)}",
        )
        assertTrue(
            settledLine.contains("UP-TO-DATE"),
            "positive control failed: the settled run's own line should be UP-TO-DATE, otherwise " +
                "the final assertion proves nothing. Line: $settledLine",
        )

        val probe = "tramai-observability/src/main/kotlin/dev/tramai/testing/EnrollmentInvalidationProbe.kt"
        writeKt(probe, "package dev.tramai.testing\n\ninternal object EnrollmentInvalidationProbe\n")

        val afterEdit = gradle(":architectureContractEnrollmentTest", "--console=plain")
        val afterLine = enrollmentLine(afterEdit.output)
        assertTrue(
            afterLine.isNotEmpty(),
            "the enrollment task produced no status line after a scanned source changed. " +
                "Output tail: ${afterEdit.output.takeLast(1500)}",
        )
        assertFalse(
            afterLine.contains("UP-TO-DATE"),
            "editing a scanned source must invalidate architectureContractEnrollmentTest without " +
                "--rerun-tasks, but its own line still read UP-TO-DATE: $afterLine",
        )
    }
}
