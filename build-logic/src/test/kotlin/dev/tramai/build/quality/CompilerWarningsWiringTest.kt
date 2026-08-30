package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 10.1c C-series wiring contract (review round): `check`/`verifyPr` own the
 * compiler-warning gate; a malformed/deleted baseline fails closed before any
 * compile happens.
 */
class CompilerWarningsWiringTest : StaticAnalysisContractTestBase() {
    @Test
    fun `c9 check owns compiler gate`() {
        val run = gradleUntil(":verifyCompilerWarnings", "--no-build-cache", "check", "--dry-run")
        assertTrue(
            run.output.contains(":verifyCompilerWarnings"),
            "check's task graph must contain :verifyCompilerWarnings. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `c10 verifyPr owns compiler gate`() {
        val run = gradleUntil(":verifyCompilerWarnings", "--no-build-cache", "verifyPr", "--dry-run")
        assertTrue(
            run.output.contains(":verifyCompilerWarnings"),
            "verifyPr's task graph must contain :verifyCompilerWarnings. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `c7 deleted compiler baseline fails closed`() {
        val baseline = File(worktree, "config/warnings/baseline.json")
        check(baseline.isFile) { "worktree must carry the committed compiler baseline" }
        baseline.delete()
        git(worktree, "commit", "-am", "delete compiler baseline")
        val run = gradle("verifyCompilerWarnings", "-PtramaiCompilerWarningsBaseRef=HEAD", "--no-build-cache")
        assertTrue(
            run.exit != 0 && run.output.contains("fails closed"),
            "deleted baseline must fail the gate before any compile. Output: ${run.output.take(1500)}",
        )
    }
}
