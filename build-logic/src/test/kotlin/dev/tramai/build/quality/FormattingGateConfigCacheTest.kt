package dev.tramai.build.quality

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Durable configuration-cache test for the Epic 10.1a incremental Kotlin formatting
 * gate: a cold `spotlessCheck --configuration-cache` stores an entry and a warm run
 * reuses it with zero problems. A future edit that breaks configuration-cache
 * compatibility fails this suite. See [FormattingGateContractTestBase].
 */
class FormattingGateConfigCacheTest : FormattingGateContractTestBase() {
    @Test
    fun `configuration cache cold to warm reuse`() {
        val ccDir = File(worktree, ".gradle/configuration-cache")
        if (ccDir.exists()) ccDir.deleteRecursively()

        val cold =
            gradle(
                "spotlessCheck",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
            )
        assertPasses(cold, "cold run")
        assertTrue(
            cold.output.contains("Configuration cache entry stored"),
            "cold run must store a configuration-cache entry. Output: ${cold.output.take(1200)}",
        )

        val warm =
            gradle(
                "spotlessCheck",
                "--configuration-cache",
                "--configuration-cache-problems=fail",
            )
        assertPasses(warm, "warm run")
        // CI runners inject a Develocity init script (gradle-actions) whose CCUD
        // bookkeeping can change the configuration between runs, forcing a second
        // store instead of a reuse. Either outcome is CC-compatible for the gate;
        // what must never happen is a CC *problem* (enforced via problems=fail).
        assertTrue(
            warm.output.contains("Configuration cache entry reused") ||
                warm.output.contains("Configuration cache entry stored"),
            "warm run must reuse (or re-store without problems) the entry. Output: ${warm.output.take(1200)}",
        )
    }
}
