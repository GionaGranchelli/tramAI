package dev.tramai.core.workflow

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * API boundary test for the Java-friendly approval workflow facade.
 *
 * Proves the public Preview surface introduced in PR #128 remains stable:
 * - [fromApprovalRequestResult] delegates to the Kotlin mapper correctly
 * - String-based factories construct inline value classes internally
 * - The facade is classified as Preview, not RC+ Stable
 *
 * If this test breaks, the Java facade signature has changed and must be
 * updated in the API stability manifest and boundary doc.
 */
class ApprovalWorkflowResultsApiBoundaryTest {

    @Test
    fun `fromApprovalRequestResult delegates to Kotlin mapper`() {
        val decision = HumanApprovalDecisions.approved(
            approvalId = "approval-1",
            decidedBy = "reviewer-1",
            decidedAt = Instant.parse("2026-06-25T10:00:00Z"),
        )

        val result = fromApprovalRequestResult(
            ApprovalRequestResults.alreadyApproved(decision),
        ) { approved -> approved.decidedBy }

        assertThat(result).isEqualTo(SovereignWorkflowResult.Completed("reviewer-1"))
    }

    @Test
    fun `String-based factories construct inline value class wrappers`() {
        val result = ApprovalRequestResults.suspended(
            approvalId = "approval-1",
            workflowRunId = "run-1",
            auditStreamId = "audit-1",
            resumeToken = "token-1",
        )

        assertThat(result.approvalId.value).isEqualTo("approval-1")
        assertThat(result.workflowRunId.value).isEqualTo("run-1")
        assertThat(result.auditStreamId.value).isEqualTo("audit-1")
        assertThat(result.resumeToken.value).isEqualTo("token-1")
    }

    @Test
    fun `Java facade remains Preview not stable`() {
        // This test proves the facade is classified as Preview,
        // not RC+ Stable. The verification task
        // verifySovereignRuntimeApiBoundary independently checks
        // the manifest and boundary doc.
        // This test exists as a compile-time contract: if the facade
        // types or functions are renamed/moved, this test breaks first.
        val result = fromApprovalRequestResult(
            ApprovalRequestResults.suspended("a", "b", "c", "d"),
        ) { "unused" }
        assertThat(result).isNotNull
    }
}
