package dev.tramai.core.workflow

import dev.tramai.core.approval.gateway.ApprovalGateway
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalRequestResult
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.AuditStreamId
import dev.tramai.core.approval.gateway.HumanApprovalDecision
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Golden-path ergonomics proof for the Preview ApprovalGateway.
 *
 * This test demonstrates that a sovereign workflow can use only
 * [ApprovalGateway] as its human-approval dependency — without
 * directly wiring low-level approval, continuation, JDBC,
 * outbox, worker-lease, or resume-credential stores.
 */
class ApprovalGatewayGoldenPathErgonomicsTest {

    // ── Recording fake gateway ──

    private class RecordingApprovalGateway(
        private val result: ApprovalRequestResult,
    ) : ApprovalGateway {
        var capturedSubject: ApprovalSubject? = null
        var capturedRecommendation: ApprovalRecommendation? = null
        var capturedRequiredRole: ApproverRole? = null
        var capturedWorkflowRunId: WorkflowRunId? = null

        override suspend fun requestApproval(
            subject: ApprovalSubject,
            recommendation: ApprovalRecommendation,
            requiredRole: ApproverRole,
            workflowRunId: WorkflowRunId?,
        ): ApprovalRequestResult {
            capturedSubject = subject
            capturedRecommendation = recommendation
            capturedRequiredRole = requiredRole
            capturedWorkflowRunId = workflowRunId
            return result
        }
    }

    // ── Tiny example workflow ──

    /**
     * Minimal workflow that routes an approval request through the
     * gateway and uses the [ApprovalRequestResult.toWorkflowResult]
     * mapper to convert each outcome to a [SovereignWorkflowResult].
     *
     * This is the developer-facing ergonomic shape: the workflow
     * expresses its approval boundary through a single gateway call
     * and the mapper, with zero knowledge of the persistence layer
     * underneath or the sealed-class mapping details.
     */
    private class ExampleClaimWorkflow(
        private val gateway: ApprovalGateway,
    ) {
        suspend fun triage(input: ClaimInput): SovereignWorkflowResult<String> {
            return gateway.requestApproval(
                subject = ApprovalSubject(input.claimId),
                recommendation = ApprovalRecommendation(
                    type = "claim-triage",
                    summary = "Claim requires medical review",
                ),
                requiredRole = ApproverRole("medical-reviewer"),
                workflowRunId = WorkflowRunId(input.workflowRunId),
            ).toWorkflowResult { "approved-continue" }
        }
    }

    private data class ClaimInput(
        val claimId: String,
        val workflowRunId: String,
    )

    // ── 1. Suspended → SuspendedForApproval ──

    @Test
    fun `high risk workflow suspends through approval gateway without store wiring`() = runTest {
        val gateway = RecordingApprovalGateway(
            ApprovalRequestResult.Suspended(
                approvalId = ApprovalId("approval-1"),
                workflowRunId = WorkflowRunId("run-1"),
                auditStreamId = AuditStreamId("audit-1"),
                resumeToken = ResumeToken("token-1"),
            ),
        )

        val workflow = ExampleClaimWorkflow(gateway)
        val result = workflow.triage(ClaimInput("claim-1", "run-1"))

        // Proves correct request construction
        assertThat(gateway.capturedSubject).isEqualTo(ApprovalSubject("claim-1"))
        assertThat(gateway.capturedRecommendation!!.type).isEqualTo("claim-triage")
        assertThat(gateway.capturedRecommendation!!.summary).isEqualTo("Claim requires medical review")
        assertThat(gateway.capturedRequiredRole).isEqualTo(ApproverRole("medical-reviewer"))
        assertThat(gateway.capturedWorkflowRunId).isEqualTo(WorkflowRunId("run-1"))

        // Proves correct result mapping
        assertThat(result).isInstanceOf(SovereignWorkflowResult.SuspendedForApproval::class.java)
        val suspended = result as SovereignWorkflowResult.SuspendedForApproval
        assertThat(suspended.approvalId).isEqualTo(ApprovalId("approval-1"))
        assertThat(suspended.workflowRunId).isEqualTo(WorkflowRunId("run-1"))
        assertThat(suspended.auditStreamId).isEqualTo(AuditStreamId("audit-1"))
        assertThat(suspended.resumeToken).isEqualTo(ResumeToken("token-1"))
    }

    // ── 2. AlreadyApproved → Completed ──

    @Test
    fun `already approved result maps to Completed workflow result`() = runTest {
        val gateway = RecordingApprovalGateway(
            ApprovalRequestResult.AlreadyApproved(
                decision = HumanApprovalDecision.Approved(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "reviewer-1",
                    decidedAt = Instant.parse("2026-06-25T10:00:00Z"),
                ),
            ),
        )

        val workflow = ExampleClaimWorkflow(gateway)
        val result = workflow.triage(ClaimInput("claim-2", "run-2"))

        assertThat(result).isInstanceOf(SovereignWorkflowResult.Completed::class.java)
        val completed = result as SovereignWorkflowResult.Completed
        assertThat(completed.value).isEqualTo("approved-continue")
    }

    // ── 3. AlreadyDenied → Rejected ──

    @Test
    fun `already denied result maps to Rejected workflow result`() = runTest {
        val gateway = RecordingApprovalGateway(
            ApprovalRequestResult.AlreadyDenied(
                decision = HumanApprovalDecision.Denied(
                    approvalId = ApprovalId("approval-1"),
                    decidedBy = "reviewer-1",
                    decidedAt = Instant.parse("2026-06-25T10:00:00Z"),
                    reason = "requires-legal-review",
                ),
            ),
        )

        val workflow = ExampleClaimWorkflow(gateway)
        val result = workflow.triage(ClaimInput("claim-3", "run-3"))

        assertThat(result).isInstanceOf(SovereignWorkflowResult.Rejected::class.java)
        val rejected = result as SovereignWorkflowResult.Rejected
        assertThat(rejected.reason).isEqualTo("requires-legal-review")
    }

    // ── 4. Expired → Expired ──

    @Test
    fun `expired approval result maps to Expired workflow result`() = runTest {
        val gateway = RecordingApprovalGateway(
            ApprovalRequestResult.Expired(
                approvalId = ApprovalId("approval-1"),
                expiredAt = Instant.parse("2026-06-25T10:00:00Z"),
                reason = "approval-expired",
            ),
        )

        val workflow = ExampleClaimWorkflow(gateway)
        val result = workflow.triage(ClaimInput("claim-4", "run-4"))

        assertThat(result).isInstanceOf(SovereignWorkflowResult.Expired::class.java)
        val expired = result as SovereignWorkflowResult.Expired
        assertThat(expired.reason).isEqualTo("approval-expired")
    }
}
