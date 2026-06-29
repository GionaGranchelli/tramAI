package dev.tramai.core.workflow

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.HumanApprovalDecision
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * API boundary test for [ApprovalRequestResult.toWorkflowResult].
 *
 * Proves the public Preview signature remains decision-aware.
 * This is an API contract test, not a behavior coverage test
 * (see [ApprovalRequestWorkflowResultMappersTest] for behavior).
 *
 * If this test breaks, the Preview function signature has changed
 * and must be updated in the API stability manifest and boundary doc.
 */
class ApprovalRequestWorkflowResultMappersApiBoundaryTest {

    @Test
    fun `toWorkflowResult exposes approved decision to continuation lambda`() {
        val decision = HumanApprovalDecision.Approved(
            approvalId = ApprovalId("approval-1"),
            decidedBy = "reviewer-1",
            decidedAt = Instant.parse("2026-06-25T10:00:00Z"),
            comment = "approved",
        )

        val result = ApprovalRequestResult.AlreadyApproved(decision)
            .toWorkflowResult { approved ->
                "${approved.decidedBy}:${approved.comment}"
            }

        assertThat(result).isInstanceOf(SovereignWorkflowResult.Completed::class.java)
        assertThat((result as SovereignWorkflowResult.Completed<*>).value)
            .isEqualTo("reviewer-1:approved")
    }
}
