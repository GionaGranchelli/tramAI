package dev.tramai.engine.approval

import dev.tramai.core.approval.ApprovalBinding
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.Sha256Digest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Contract tests for [requireAttributionMatchesBinding].
 *
 * The rule is that the attribution of a governed approval must describe the same run as the
 * request binding, so "run A + workload/configuration/deployment B" is unrepresentable. Both
 * directions are asserted: a matching attribution is accepted, and one naming another run is
 * rejected with a message that names the inconsistency. The rejection case is the discriminator
 * that proves the check exists — with the binding comparison absent, an approval created under a
 * substituted identity would be accepted silently.
 */
class GovernedApprovalStoreTest {
    private val digest = Sha256Digest.of("sha256:" + "3".repeat(64))

    @Test
    fun `attribution for the bound run is accepted`() {
        requireAttributionMatchesBinding(
            request(workflowRunId = "run-binding"),
            ApprovalRunAttribution.Governed(governedIdentity(runId = "run-binding")),
        )
    }

    @Test
    fun `attribution naming another run is rejected`() {
        val failure =
            assertThrows<IllegalArgumentException> {
                requireAttributionMatchesBinding(
                    request(workflowRunId = "run-binding"),
                    ApprovalRunAttribution.Governed(governedIdentity(runId = "run-other")),
                )
            }

        assertTrue(
            failure.message.orEmpty().contains("inconsistent with its binding"),
            "the rejection must name the binding inconsistency, got: ${failure.message}",
        )
    }

    @Test
    fun `an ungoverned attribution carries no binding to check`() {
        requireAttributionMatchesBinding(request(workflowRunId = "run-binding"), ApprovalRunAttribution.Ungoverned)
    }

    private fun request(workflowRunId: String) =
        ApprovalRequest(
            approvalId = "approval-1",
            binding =
                ApprovalBinding(
                    workflowRunId = workflowRunId,
                    toolName = "test-tool",
                    argumentsDigest = digest,
                    policyVersion = "v1",
                    workflowDigest = digest,
                    approvalTokenDigest = digest,
                ),
            status = ApprovalStatus.PENDING,
            requestedBy = "test-actor",
            requestedAt = Instant.EPOCH,
            expiresAt = Instant.EPOCH.plusSeconds(3600),
            decidedBy = null,
            decidedAt = null,
            decisionComment = null,
            consumedBy = null,
            consumedAt = null,
            version = 0L,
        )
}
