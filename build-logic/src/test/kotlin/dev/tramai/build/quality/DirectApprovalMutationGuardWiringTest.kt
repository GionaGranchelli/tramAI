package dev.tramai.build.quality

import kotlin.test.Test
import kotlin.test.assertTrue

class DirectApprovalMutationGuardWiringTest : StaticAnalysisContractTestBase() {
    @Test fun `check owns the direct approval mutation guard`() {
        val out = gradleUntil(":verifyDirectApprovalMutations", "--no-build-cache", "check", "--dry-run").output
        assertTrue(out.contains(":verifyDirectApprovalMutations"))
    }

    @Test fun `verifyPr owns the direct approval mutation guard`() {
        val out = gradleUntil(":verifyDirectApprovalMutations", "--no-build-cache", "verifyPr", "--dry-run").output
        assertTrue(out.contains(":verifyDirectApprovalMutations"))
    }
}
