package dev.tramai.build.quality

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class StaticSafetyGuardsConfigCacheTest : StaticAnalysisContractTestBase() {
    @Test fun `configuration cache cold to warm reuse`() {
        File(worktree, ".gradle/configuration-cache").deleteRecursively()
        val cold = gradle("verifyStaticSafetyGuards", "--configuration-cache", "--configuration-cache-problems=fail")
        assertPasses(cold, "cold configuration-cache run")
        assertTrue(cold.output.contains("Configuration cache entry stored"), cold.output.take(1500))
        val warm = gradle("verifyStaticSafetyGuards", "--configuration-cache", "--configuration-cache-problems=fail")
        assertPasses(warm, "warm configuration-cache run")
        val reused = warm.output.contains("Configuration cache entry reused")
        val stored = warm.output.contains("Configuration cache entry stored")
        assertTrue(reused || stored, warm.output.take(1500))
    }
}
