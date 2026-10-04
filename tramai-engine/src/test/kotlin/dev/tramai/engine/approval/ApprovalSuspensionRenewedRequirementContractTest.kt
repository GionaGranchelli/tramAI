package dev.tramai.engine.approval

import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.SensitiveToolArguments
import dev.tramai.core.policy.ApprovalRequirement
import dev.tramai.core.policy.PolicyDecision
import dev.tramai.engine.InMemorySuspendedInvocationStore
import dev.tramai.engine.planning.ServiceDefinition
import dev.tramai.engine.tool.testTool
import dev.tramai.engine.tool.toolOperation
import dev.tramai.engine.tool.toolRequest
import dev.tramai.security.approval.Sha256ToolArgumentsDigester
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Contract test for renewed-approval binding validation in
 * [ApprovalSuspensionCoordinator] — the resume path that re-validates a renewed
 * approval requirement instead of creating a new suspension.
 *
 * The zero-timeout case is the sharp boundary: a requirement with
 * `timeoutMillis = 0` describes an approval that is already expired at the moment
 * it would be persisted, so accepting it suspends the run on a requirement that
 * can never be satisfied. Rejection is the whole observable behaviour of that
 * path, which is why it is asserted directly rather than through the saga.
 *
 * Deliberately isolated: the validation reads only the request, the policy
 * decision and the arguments digester, so every other collaborator here is a
 * stand-in that must never be reached. That keeps the test independent of the
 * formatter/baseline state of the older suspension coordinator test file.
 */
class ApprovalSuspensionRenewedRequirementContractTest {
    private val toolName = "test-tool"
    private val input = """{"x":2}"""
    private val digester = Sha256ToolArgumentsDigester()

    private fun coordinator() =
        ApprovalSuspensionCoordinator(
            approvalGateCoordinator = null,
            approvalContinuationStore = null,
            suspendedInvocationStore = InMemorySuspendedInvocationStore(),
            resumeOperationRegistry = ResumeOperationRegistry(),
            // Never dereferenced on this path; only carried by the coordinator.
            serviceDefinition =
                ServiceDefinition(
                    serviceType = ApprovalSuspensionRenewedRequirementContractTest::class,
                    systemPrompt = null,
                    operations = emptyMap(),
                ),
            resumeExecutor =
                ClaimedResumeExecutor {
                    error("resume execution must not be reached while validating a renewed requirement")
                },
            toolArgumentsDigester = digester,
            clock = Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneId.of("UTC")),
            approvalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
        )

    private fun renewedRequest() =
        toolRequest(testTool(toolName), toolOperation(toolName)).copy(
            resumingApproval = true,
            allowRenewedApprovedBindingDuringResume = true,
        )

    @Test
    fun `a renewed approval requirement with zero timeout is rejected`() {
        // The digest is the real one for `input`, so the binding checks pass and the
        // positive-timeout requirement is the only thing that can reject this call.
        val decision =
            PolicyDecision.RequireApproval(
                ApprovalRequirement(
                    toolName = toolName,
                    argumentsDigest = digester.digest(SensitiveToolArguments.of(input)).value,
                    reason = "testing",
                    timeoutMillis = 0,
                ),
            )

        assertThatThrownBy { runBlocking { coordinator().requireApproval(renewedRequest(), decision, input) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Renewed approval requirement must have positive timeout")
    }

    @Test
    fun `a renewed approval requirement with a positive timeout passes validation`() {
        val decision =
            PolicyDecision.RequireApproval(
                ApprovalRequirement(
                    toolName = toolName,
                    argumentsDigest = digester.digest(SensitiveToolArguments.of(input)).value,
                    reason = "testing",
                    timeoutMillis = 60_000,
                ),
            )

        runBlocking { coordinator().requireApproval(renewedRequest(), decision, input) }
    }
}
