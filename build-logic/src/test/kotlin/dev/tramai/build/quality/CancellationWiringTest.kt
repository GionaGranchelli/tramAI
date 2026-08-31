package dev.tramai.build.quality

import kotlin.test.Test
import kotlin.test.assertTrue

class CancellationWiringTest : StaticAnalysisContractTestBase() {
    @Test fun `C1 cancellation authority remains exact base task`() {
        val out = gradle("tasks", "--all").output
        assertTrue(out.contains("verifyCancellationSafety") && out.contains("tramaiCancellationBaseSha"), out.take(2000))
    }

    @Test fun `C2 check owns cancellation safety`() {
        val out = gradleUntil(":verifyCancellationSafety", "--no-build-cache", "check", "--dry-run").output
        assertTrue(out.contains(":verifyCancellationSafety"))
    }

    @Test fun `C3 verifyPr owns cancellation safety`() {
        val out = gradleUntil(":verifyCancellationSafety", "--no-build-cache", "verifyPr", "--dry-run").output
        assertTrue(out.contains(":verifyCancellationSafety"))
    }
}
