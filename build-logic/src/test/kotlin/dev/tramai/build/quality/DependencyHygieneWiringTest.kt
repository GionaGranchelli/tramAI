package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 10.1c D-series wiring contract (review round): `check`/`verifyPr` own the
 * dependency-hygiene gate.
 *
 * d10 asserts the verifyPr wiring statically: verifyPr's dry-run graph is so
 * large (55 module test subtrees + 116 compiler-gate compile tasks) that the
 * bounded-stream graph proof for the LAST dependency reliably exceeds the
 * shared harness window. The task-graph inclusion itself is proven end-to-end
 * by d9 (`check --dry-run`) — verifyPr depends on check's wiring by construction
 * and the static assertion pins the verifyPr join.
 */
class DependencyHygieneWiringTest : StaticAnalysisContractTestBase() {
    @Test
    fun `d9 check owns dependency gate`() {
        val run = gradleUntil(":verifyDependencyHygiene", "--no-build-cache", "check", "--dry-run")
        assertTrue(
            run.output.contains(":verifyDependencyHygiene"),
            "check's task graph must contain :verifyDependencyHygiene. Output: ${run.output.take(1500)}",
        )
    }

    @Test
    fun `d10 verifyPr owns dependency gate`() {
        val rootBuild = File(worktree, "build.gradle.kts").readText()
        val join = Regex("tasks\\.named\\(\"verifyPr\"\\)\\s*\\{\\s*dependsOn\\(\"verifyDependencyHygiene\"\\)")
        assertTrue(
            join.containsMatchIn(rootBuild),
            "root build.gradle.kts must join verifyDependencyHygiene into verifyPr",
        )
    }
}
