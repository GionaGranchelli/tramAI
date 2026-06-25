package dev.tramai.core.approval.gateway

import dev.tramai.core.workflow.SovereignWorkflowResult
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.time.Instant

class ApprovalGatewayContractTest {

    // ── Value class validation ──

    @Test
    fun `ApprovalSubject rejects blank value`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ApprovalSubject(" ") }
            .withMessage("approval-subject-blank")
    }

    @Test
    fun `ApproverRole rejects blank value`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ApproverRole(" ") }
            .withMessage("approver-role-blank")
    }

    @Test
    fun `ApprovalId rejects blank value`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ApprovalId(" ") }
            .withMessage("approval-id-blank")
    }

    @Test
    fun `WorkflowRunId rejects blank value`() {
        assertThatIllegalArgumentException()
            .isThrownBy { WorkflowRunId(" ") }
            .withMessage("workflow-run-id-blank")
    }

    @Test
    fun `AuditStreamId rejects blank value`() {
        assertThatIllegalArgumentException()
            .isThrownBy { AuditStreamId(" ") }
            .withMessage("audit-stream-id-blank")
    }

    @Test
    fun `ResumeToken rejects blank value`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ResumeToken(" ") }
            .withMessage("resume-token-blank")
    }

    @Test
    fun `ApprovalRecommendation rejects blank type`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                ApprovalRecommendation(
                    type = " ",
                    summary = "valid summary",
                )
            }
            .withMessage("approval-recommendation-type-blank")
    }

    @Test
    fun `ApprovalRecommendation rejects blank summary`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                ApprovalRecommendation(
                    type = "claim-triage",
                    summary = " ",
                )
            }
            .withMessage("approval-recommendation-summary-blank")
    }

    // ── Suspended result ──

    @Test
    fun `Suspended result exposes durable resume identifiers`() {
        val result = ApprovalRequestResult.Suspended(
            approvalId = ApprovalId("approval-1"),
            workflowRunId = WorkflowRunId("workflow-1"),
            auditStreamId = AuditStreamId("audit-1"),
            resumeToken = ResumeToken("resume-1"),
        )

        assertThat(result.approvalId).isEqualTo(ApprovalId("approval-1"))
        assertThat(result.workflowRunId).isEqualTo(WorkflowRunId("workflow-1"))
        assertThat(result.auditStreamId).isEqualTo(AuditStreamId("audit-1"))
        assertThat(result.resumeToken).isEqualTo(ResumeToken("resume-1"))
    }

    // ── Expired result ──

    @Test
    fun `Expired result is explicit terminal approval state`() {
        val expiredAt = Instant.parse("2026-06-25T10:00:00Z")

        val result = ApprovalRequestResult.Expired(
            approvalId = ApprovalId("approval-1"),
            expiredAt = expiredAt,
            reason = "approval-expired",
        )

        assertThat(result.expiredAt).isEqualTo(expiredAt)
        assertThat(result.reason).isEqualTo("approval-expired")
    }

    // ── Already approved / denied ──

    @Test
    fun `AlreadyApproved preserves decision`() {
        val decision = HumanApprovalDecision.Approved(
            approvalId = ApprovalId("approval-1"),
            decidedBy = "reviewer-1",
            decidedAt = Instant.parse("2026-06-25T10:00:00Z"),
            comment = "Looks correct",
        )

        val result = ApprovalRequestResult.AlreadyApproved(decision)

        assertThat(result.decision.approvalId).isEqualTo(ApprovalId("approval-1"))
        assertThat(result.decision.decidedBy).isEqualTo("reviewer-1")
    }

    @Test
    fun `AlreadyDenied preserves decision with reason`() {
        val decision = HumanApprovalDecision.Denied(
            approvalId = ApprovalId("approval-1"),
            decidedBy = "reviewer-1",
            decidedAt = Instant.parse("2026-06-25T10:00:00Z"),
            reason = "requires-legal-review",
            comment = "Needs legal team input first",
        )

        val result = ApprovalRequestResult.AlreadyDenied(decision)

        assertThat(result.decision.reason).isEqualTo("requires-legal-review")
    }

    // ── Gateway contract returns without blocking ──

    private class RecordingApprovalGateway : ApprovalGateway {
        var calls = 0

        override suspend fun requestApproval(
            subject: ApprovalSubject,
            recommendation: ApprovalRecommendation,
            requiredRole: ApproverRole,
            workflowRunId: WorkflowRunId?,
        ): ApprovalRequestResult {
            calls++
            return ApprovalRequestResult.Suspended(
                approvalId = ApprovalId("approval-1"),
                workflowRunId = workflowRunId ?: WorkflowRunId("workflow-1"),
                auditStreamId = AuditStreamId("audit-1"),
                resumeToken = ResumeToken("resume-1"),
            )
        }
    }

    @Test
    fun `gateway request approval returns Suspended result without blocking`() = runTest {
        val gateway = RecordingApprovalGateway()

        val result = gateway.requestApproval(
            subject = ApprovalSubject("claim-1"),
            recommendation = ApprovalRecommendation(
                type = "claim-triage",
                summary = "requires medical review",
            ),
            requiredRole = ApproverRole("medical-reviewer"),
            workflowRunId = WorkflowRunId("workflow-1"),
        )

        assertThat(result).isInstanceOf(ApprovalRequestResult.Suspended::class.java)
        assertThat(gateway.calls).isOne()
    }

    @Test
    fun `gateway request approval defaults workflowRunId to null`() = runTest {
        val gateway = RecordingApprovalGateway()

        val result = gateway.requestApproval(
            subject = ApprovalSubject("claim-1"),
            recommendation = ApprovalRecommendation(
                type = "claim-triage",
                summary = "requires medical review",
            ),
            requiredRole = ApproverRole("medical-reviewer"),
        )

        assertThat(result).isInstanceOf(ApprovalRequestResult.Suspended::class.java)
        val suspended = result as ApprovalRequestResult.Suspended
        assertThat(suspended.workflowRunId).isEqualTo(WorkflowRunId("workflow-1"))
        assertThat(gateway.calls).isOne()
    }

    // ── SovereignWorkflowResult ──

    @Test
    fun `SovereignWorkflowResult Completed preserves value`() {
        val result: SovereignWorkflowResult<String> =
            SovereignWorkflowResult.Completed("done")

        assertThat(result).isInstanceOf(SovereignWorkflowResult.Completed::class.java)
        assertThat((result as SovereignWorkflowResult.Completed).value).isEqualTo("done")
    }

    @Test
    fun `SovereignWorkflowResult SuspendedForApproval exposes identifiers`() {
        val result = SovereignWorkflowResult.SuspendedForApproval(
            approvalId = ApprovalId("approval-1"),
            workflowRunId = WorkflowRunId("workflow-1"),
            auditStreamId = AuditStreamId("audit-1"),
            resumeToken = ResumeToken("resume-1"),
        )

        assertThat(result.approvalId).isEqualTo(ApprovalId("approval-1"))
        assertThat(result.workflowRunId).isEqualTo(WorkflowRunId("workflow-1"))
    }

    @Test
    fun `SovereignWorkflowResult Rejected preserves reason`() {
        val result = SovereignWorkflowResult.Rejected("policy-violation")

        assertThat(result.reason).isEqualTo("policy-violation")
    }

    @Test
    fun `SovereignWorkflowResult Expired preserves reason`() {
        val result = SovereignWorkflowResult.Expired("approval-expired")

        assertThat(result.reason).isEqualTo("approval-expired")
    }
}
