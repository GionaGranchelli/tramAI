package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Configuration-cache compatibility of the canonical static-analysis task:
 * a cold run stores an entry and a warm run reuses it with zero problems.
 * CI runners inject a Develocity init script whose CCUD bookkeeping can force
 * a re-store instead of a reuse; either outcome is CC-compatible, but a CC
 * *problem* is not (enforced via --configuration-cache-problems=fail).
 */
class StaticAnalysisConfigCacheTest : StaticAnalysisContractTestBase() {
    @Test
    fun `configuration cache cold to warm`() {
        val base = baseBranch()
        val ccDir = File(worktree, ".gradle/configuration-cache")
        if (ccDir.exists()) ccDir.deleteRecursively()

        val cold =
            gradle(
                "--no-build-cache",
                "verifyStaticAnalysis",
                "-PtramaiStaticAnalysisBaseRef=$base",
                "-PchangeClass=build-logic",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
            )
        assertPasses(cold, "cold configuration-cache run")
        assertTrue(
            cold.output.contains("Configuration cache entry stored"),
            "cold run must store a configuration-cache entry. Output: ${cold.output.take(1200)}",
        )

        val warm =
            gradle(
                "--no-build-cache",
                "verifyStaticAnalysis",
                "-PtramaiStaticAnalysisBaseRef=$base",
                "-PchangeClass=build-logic",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
            )
        assertPasses(warm, "warm configuration-cache run")
        assertTrue(
            warm.output.contains("Configuration cache entry reused") ||
                warm.output.contains("Configuration cache entry stored"),
            "warm run must reuse (or re-store without problems) the entry. Output: ${warm.output.take(1200)}",
        )
    }
}
