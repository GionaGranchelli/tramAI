package dev.tramai.build.quality

import kotlin.test.Test
import kotlin.test.assertTrue

class StaticSafetyGuardsWiringTest : StaticAnalysisContractTestBase() {
    @Test fun `C4 check owns static safety guards`() {
        val out = gradleUntil(":verifyStaticSafetyGuards", "--no-build-cache", "check", "--dry-run").output
        assertTrue(out.contains(":verifyStaticSafetyGuards"))
    }

    @Test fun `C5 verifyPr owns static safety guards`() {
        val out = gradleUntil(":verifyStaticSafetyGuards", "--no-build-cache", "verifyPr", "--dry-run").output
        assertTrue(out.contains(":verifyStaticSafetyGuards"))
    }
}
