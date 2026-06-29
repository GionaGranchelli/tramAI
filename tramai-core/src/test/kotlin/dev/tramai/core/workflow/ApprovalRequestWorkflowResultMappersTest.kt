package dev.tramai.core.workflow

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.AuditStreamId
import dev.tramai.core.approval.gateway.HumanApprovalDecision
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [ApprovalRequestResult.toWorkflowResult].
 *
 * Verifies correct mapping for all four gateway outcomes and
 * the lazy-invocation contract for [approvedValue].
 */
class ApprovalRequestWorkflowResultMappersTest {

    // ── Helpers ──

    private val approvalId = ApprovalId("approval-1")
    private val workflowRunId = WorkflowRunId("run-1")
    private val auditStreamId = AuditStreamId("audit-1")
    private val resumeToken = ResumeToken("token-1")
    private val decidedBy = "reviewer-1"
    private val decidedAt = Instant.parse("2026-06-25T10:00:00Z")

    private val approvedDecision = HumanApprovalDecision.Approved(
        approvalId = approvalId,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
    )

    private val deniedDecision = HumanApprovalDecision.Denied(
        approvalId = approvalId,
        decidedBy = decidedBy,
        decidedAt = decidedAt,
        reason = "requires-legal-review",
    )

    // ── 1. Suspended → SuspendedForApproval ──

    @Test
    fun `Suspended maps to SuspendedForApproval preserving all identifiers`() {
        val result = dev.tramai.core.approval.gateway.ApprovalRequestResult.Suspended(
            approvalId = approvalId,
            workflowRunId = workflowRunId,
            auditStreamId = auditStreamId,
            resumeToken = resumeToken,
        ).toWorkflowResult { "should-not-be-called" }

        assertThat(result).isInstanceOf(SovereignWorkflowResult.SuspendedForApproval::class.java)
        val suspended = result as SovereignWorkflowResult.SuspendedForApproval
        assertThat(suspended.approvalId).isEqualTo(approvalId)
        assertThat(suspended.workflowRunId).isEqualTo(workflowRunId)
        assertThat(suspended.auditStreamId).isEqualTo(auditStreamId)
        assertThat(suspended.resumeToken).isEqualTo(resumeToken)
    }

    // ── 2. AlreadyApproved → Completed ──

    @Test
    fun `AlreadyApproved maps to Completed with approvedValue`() {
        val result = dev.tramai.core.approval.gateway.ApprovalRequestResult.AlreadyApproved(
            decision = approvedDecision,
        ).toWorkflowResult { "approved-continue" }

        assertThat(result).isInstanceOf(SovereignWorkflowResult.Completed::class.java)
        val completed = result as SovereignWorkflowResult.Completed<*>
        assertThat(completed.value).isEqualTo("approved-continue")
    }

    // ── 3. AlreadyDenied → Rejected ──

    @Test
    fun `AlreadyDenied maps to Rejected with decision reason`() {
        val result = dev.tramai.core.approval.gateway.ApprovalRequestResult.AlreadyDenied(
            decision = deniedDecision,
        ).toWorkflowResult { "should-not-be-called" }

        assertThat(result).isInstanceOf(SovereignWorkflowResult.Rejected::class.java)
        val rejected = result as SovereignWorkflowResult.Rejected
        assertThat(rejected.reason).isEqualTo("requires-legal-review")
    }

    // ── 4. Expired → Expired ──

    @Test
    fun `Expired maps to Expired with reason`() {
        val result = dev.tramai.core.approval.gateway.ApprovalRequestResult.Expired(
            approvalId = approvalId,
            expiredAt = Instant.parse("2026-06-25T10:00:00Z"),
            reason = "approval-expired",
        ).toWorkflowResult { "should-not-be-called" }

        assertThat(result).isInstanceOf(SovereignWorkflowResult.Expired::class.java)
        val expired = result as SovereignWorkflowResult.Expired
        assertThat(expired.reason).isEqualTo("approval-expired")
    }

    // ── 5. Lambda not invoked for terminal states ──

    @Test
    fun `approvedValue lambda is not invoked for Suspended`() {
        var invoked = false
        dev.tramai.core.approval.gateway.ApprovalRequestResult.Suspended(
            approvalId = approvalId,
            workflowRunId = workflowRunId,
            auditStreamId = auditStreamId,
            resumeToken = resumeToken,
        ).toWorkflowResult { invoked = true; "value" }
        assertThat(invoked).`as`("approvedValue must not be invoked for Suspended").isFalse()
    }

    @Test
    fun `approvedValue lambda is not invoked for AlreadyDenied`() {
        var invoked = false
        dev.tramai.core.approval.gateway.ApprovalRequestResult.AlreadyDenied(
            decision = deniedDecision,
        ).toWorkflowResult { invoked = true; "value" }
        assertThat(invoked).`as`("approvedValue must not be invoked for AlreadyDenied").isFalse()
    }

    @Test
    fun `approvedValue lambda is not invoked for Expired`() {
        var invoked = false
        dev.tramai.core.approval.gateway.ApprovalRequestResult.Expired(
            approvalId = approvalId,
            expiredAt = Instant.parse("2026-06-25T10:00:00Z"),
            reason = "expired",
        ).toWorkflowResult { invoked = true; "value" }
        assertThat(invoked).`as`("approvedValue must not be invoked for Expired").isFalse()
    }

    // ── 6. Lambda invoked exactly once for AlreadyApproved ──

    @Test
    fun `approvedValue lambda receives the approved decision`() {
        var capturedDecision: HumanApprovalDecision.Approved? = null
        val result = dev.tramai.core.approval.gateway.ApprovalRequestResult.AlreadyApproved(
            decision = approvedDecision,
        ).toWorkflowResult { decision ->
            capturedDecision = decision
            "approved-continue"
        }

        assertThat(capturedDecision).`as`("approvedValue must receive the decision").isNotNull
        assertThat(capturedDecision!!.decidedBy).isEqualTo(decidedBy)
        assertThat(capturedDecision.decidedAt).isEqualTo(decidedAt)
        assertThat((result as SovereignWorkflowResult.Completed).value).isEqualTo("approved-continue")
    }

    @Test
    fun `approvedValue lambda is invoked exactly once for AlreadyApproved`() {
        var invocationCount = 0
        dev.tramai.core.approval.gateway.ApprovalRequestResult.AlreadyApproved(
            decision = approvedDecision,
        ).toWorkflowResult { invocationCount++; "approved-continue" }

        assertThat(invocationCount).`as`("approvedValue must be invoked exactly once").isEqualTo(1)
    }
}
