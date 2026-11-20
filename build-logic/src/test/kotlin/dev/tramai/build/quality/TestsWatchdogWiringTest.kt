package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * CI wiring contract for the `tests` job watchdog.
 *
 * The watchdog exists to abort *stalled* runs, not slow ones. Its predecessor had only an absolute
 * deadline, calibrated when the suite ran in ~12.8 minutes; once the suite reached ~14 minutes the
 * guard started aborting healthy runs that were still passing tests. That is a defect in what the
 * guard measures, not in the suite, so the guard now carries two independent conditions: inactivity
 * (a real stall) and an absolute ceiling that exists as growth margin.
 *
 * The wiring cases keep both guards and the failure artifact in place; the behavioural cases drive
 * the real script against synthetic commands, so the guards are proven to fire rather than merely
 * present in a file.
 */
class TestsWatchdogWiringTest {
    private val repoRoot = File(System.getProperty("tramai.repositoryRoot") ?: "..").canonicalFile
    private val script = File(repoRoot, ".github/scripts/test-watchdog.sh")
    private val workflow = File(repoRoot, ".github/workflows/ci.yml")

    // ── Wiring ───────────────────────────────────────────────────────────────

    @Test
    fun `the tests job runs the watchdog script`() {
        assertTrue(script.isFile, "missing ${script.path}")
        assertTrue(
            workflow.readText().contains("bash ./.github/scripts/test-watchdog.sh"),
            "the tests job must run the shared watchdog script, not an inline loop",
        )
    }

    @Test
    fun `the tests step declares both guards with growth headroom`() {
        val text = workflow.readText()
        val ceiling =
            Regex("""WATCHDOG_CEILING_SECONDS:\s*"(\d+)"""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        val inactivity =
            Regex("""WATCHDOG_INACTIVITY_SECONDS:\s*"(\d+)"""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toInt()
        val stepTimeout =
            Regex("""- name: Run tests\s*\n\s*timeout-minutes:\s*(\d+)""")
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.toInt()

        assertTrue(
            ceiling != null && ceiling >= 1200,
            "the wall-clock ceiling must stay at or above 20 minutes as growth margin, got $ceiling",
        )
        assertTrue(
            inactivity != null && inactivity in 240..300,
            "the inactivity guard must stay in the 4-5 minute band, got $inactivity",
        )
        assertTrue(
            stepTimeout != null && ceiling != null && stepTimeout * 60 > ceiling,
            "the GitHub step timeout (${stepTimeout}min) must exceed the watchdog ceiling (${ceiling}s)",
        )
    }

    @Test
    fun `the thread-dump artifact upload is preserved`() {
        val text = workflow.readText()
        assertTrue(text.contains("name: test-hang-threaddumps"), "the dump artifact must stay named")
        assertTrue(text.contains("path: /tmp/threaddumps/"), "the dump artifact must keep its path")
        assertTrue(
            Regex("""- name: Upload test hang thread dumps\s*\n\s*if: always\(\)""").containsMatchIn(text),
            "the dump artifact must upload with if: always(), or failures lose their evidence",
        )
    }

    // ── Behaviour ────────────────────────────────────────────────────────────

    @Test
    fun `a stalled run is aborted by the inactivity guard`() {
        val (exit, output) = runWatchdog(command = "bash ${helper("quiet")}", ceiling = 120, inactivity = 3)
        assertTrue(exit == 1, "a stalled run must fail; output: ${output.take(600)}")
        assertTrue(output.contains("made no progress"), "the abort must name inactivity; output: ${output.take(600)}")
    }

    @Test
    fun `a progressing run is not aborted`() {
        val (exit, output) = runWatchdog(command = "bash ${helper("progressing")}", ceiling = 120, inactivity = 3)
        assertTrue(exit == 0, "a progressing run must survive the inactivity guard; output: ${output.take(600)}")
        assertTrue(!output.contains("::error::"), "no guard may fire; output: ${output.take(600)}")
    }

    @Test
    fun `a run that keeps progressing is still aborted at the ceiling`() {
        val (exit, output) = runWatchdog(command = "bash ${helper("progressing")}", ceiling = 3, inactivity = 60)
        assertTrue(exit == 1, "the absolute ceiling must fire despite progress; output: ${output.take(600)}")
        assertTrue(
            output.contains("wall-clock ceiling"),
            "the abort must name the ceiling; output: ${output.take(600)}",
        )
    }

    // ── Harness ──────────────────────────────────────────────────────────────

    /**
     * Runs the real watchdog script with test-sized thresholds. The synthetic command is a helper
     * script so the value stays a single command, which keeps `exec` semantics and therefore the
     * kill in the abort path honest.
     */
    private fun runWatchdog(
        command: String,
        ceiling: Int,
        inactivity: Int,
    ): Pair<Int, String> {
        val work = Files.createTempDirectory("watchdog-contract-").toFile()
        val process =
            ProcessBuilder("bash", script.path)
                .directory(repoRoot)
                .redirectErrorStream(true)
                .apply {
                    environment()["WATCHDOG_COMMAND"] = command
                    environment()["WATCHDOG_CEILING_SECONDS"] = ceiling.toString()
                    environment()["WATCHDOG_INACTIVITY_SECONDS"] = inactivity.toString()
                    environment()["WATCHDOG_POLL_SECONDS"] = "1"
                    environment()["WATCHDOG_RESULTS_ROOT"] = File(work, "results").apply { mkdirs() }.path
                    environment()["WATCHDOG_DUMP_DIR"] = File(work, "dumps").path
                }.start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "the watchdog did not terminate")
        work.deleteRecursively()
        return process.exitValue() to output
    }

    private fun helper(name: String): String {
        val start = File(repoRoot, "build-logic/src/test/resources/ci-watchdog/$name.sh")
        assertTrue(start.isFile, "missing helper ${start.path}")
        // Copy out of the repository so a test run never executes from a shared, writable path.
        val copy = Files.createTempFile("watchdog-$name-", ".sh").toFile()
        start.copyTo(copy, overwrite = true)
        return copy.path
    }
}
