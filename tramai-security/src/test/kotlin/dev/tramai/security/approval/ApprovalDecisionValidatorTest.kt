package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.Sha256Digest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class ApprovalDecisionValidatorTest {

    @Test
    fun `AllowAny preserves compatibility`() {
        assertThatCode {
            AllowAnyApprovalDecisionValidator.validate(request(), "consumer-2")
        }.doesNotThrowAnyException()
    }

    @Test
    fun `RequireDistinctRequesterAndConsumer rejects same actor`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                RequireDistinctRequesterAndConsumer.validate(request(requestedBy = "same-actor"), "same-actor")
            }
            .withMessage("Approval requester and consumer must be different actors")
    }

    @Test
    fun `RequireDistinctRequesterAndConsumer accepts different actor`() {
        assertThatCode {
            RequireDistinctRequesterAndConsumer.validate(request(requestedBy = "requester"), "consumer")
        }.doesNotThrowAnyException()
    }

    private fun request(requestedBy: String = "requester") = ApprovalRequest(
        approvalId = "approval-1",
        binding = ApprovalBinding(
            workflowRunId = "wf-1",
            toolName = "tool-1",
            argumentsDigest = Sha256Digest.of("sha256:0000000000000000000000000000000000000000000000000000000000000000"),
            policyVersion = "policy-v1",
            workflowDigest = Sha256Digest.of("sha256:1111111111111111111111111111111111111111111111111111111111111111"),
            approvalTokenDigest = Sha256Digest.of("sha256:2222222222222222222222222222222222222222222222222222222222222222"),
        ),
        status = ApprovalStatus.PENDING,
        requestedBy = requestedBy,
        requestedAt = Instant.parse("2026-06-05T10:00:00Z"),
        expiresAt = Instant.parse("2026-06-05T11:00:00Z"),
        decidedBy = null,
        decidedAt = null,
        decisionComment = null,
        consumedBy = null,
        consumedAt = null,
        version = 0L,
    )
}
