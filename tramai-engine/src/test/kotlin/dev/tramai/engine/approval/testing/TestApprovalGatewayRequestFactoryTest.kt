package dev.tramai.engine.approval.testing

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.SuspendedInvocationStore
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock

class TestApprovalGatewayRequestFactoryTest {

    private val factory = TestApprovalGatewayRequestFactory(clock = Clock.systemUTC())

    @Test
    fun `builds complete ApprovalGatewayPersistenceRequest`() = runTest {
        val request = factory.createRequest(
            subject = ApprovalSubject("claim-1"),
            recommendation = ApprovalRecommendation(
                type = "test-type",
                summary = "Test recommendation",
            ),
            requiredRole = ApproverRole("reviewer"),
            workflowRunId = WorkflowRunId("run-1"),
        )

        assertThat(request).isNotNull
        assertThat(request.approvalRequest.approvalId).isNotBlank()
        assertThat(request.continuation.approvalId).isEqualTo(request.approvalRequest.approvalId)
        assertThat(request.suspendedInvocationMetadata.approvalId).isEqualTo(request.approvalRequest.approvalId)
    }

    @Test
    fun `computes argumentsDigest from actual sensitive arguments`() = runTest {
        val request = factory.createRequest(
            subject = ApprovalSubject("claim-2"),
            recommendation = ApprovalRecommendation(
                type = "medical-review",
                summary = "Needs review",
            ),
            requiredRole = ApproverRole("medical-reviewer"),
            workflowRunId = WorkflowRunId("run-2"),
        )

        assertThat(request.approvalRequest.binding.argumentsDigest).isNotNull
        assertThat(request.continuation.argumentsDigest).isEqualTo(request.approvalRequest.binding.argumentsDigest)
    }

    @Test
    fun `computes replay-envelope digest from operation reference and messages`() = runTest {
        val request = factory.createRequest(
            subject = ApprovalSubject("claim-3"),
            recommendation = ApprovalRecommendation(
                type = "standard-check",
                summary = "Standard check",
            ),
            requiredRole = ApproverRole("checker"),
            workflowRunId = WorkflowRunId("run-3"),
        )

        assertThat(request.suspendedInvocationMetadata.replayEnvelopeDigest).isNotNull
        assertThat(request.replayEnvelope).isNotNull
    }

    @Test
    fun `uses same approvalId across all three records`() = runTest {
        val request = factory.createRequest(
            subject = ApprovalSubject("claim-4"),
            recommendation = ApprovalRecommendation(
                type = "approval-test",
                summary = "Consistency check",
            ),
            requiredRole = ApproverRole("admin"),
            workflowRunId = WorkflowRunId("run-4"),
        )

        val approvalId = request.approvalRequest.approvalId
        assertThat(request.continuation.approvalId).isEqualTo(approvalId)
        assertThat(request.suspendedInvocationMetadata.approvalId).isEqualTo(approvalId)
    }

    @Test
    fun `uses same workflowRunId across binding, continuation, and identity`() = runTest {
        val request = factory.createRequest(
            subject = ApprovalSubject("claim-5"),
            recommendation = ApprovalRecommendation(
                type = "workflow-check",
                summary = "Workflow consistency",
            ),
            requiredRole = ApproverRole("workflow-admin"),
            workflowRunId = WorkflowRunId("run-5"),
        )

        val runId = "run-5"
        assertThat(request.approvalRequest.binding.workflowRunId).isEqualTo(runId)
        assertThat(request.continuation.workflowRunId).isEqualTo(runId)
        assertThat(request.suspendedInvocationMetadata.identity.workflowRunId).isEqualTo(runId)
    }

    @Test
    fun `fails if workflowRunId is missing`() = runTest {
        try {
            factory.createRequest(
                subject = ApprovalSubject("claim-6"),
                recommendation = ApprovalRecommendation(
                    type = "fail-test",
                    summary = "Should fail",
                ),
                requiredRole = ApproverRole("reviewer"),
                workflowRunId = null,
            )
        } catch (e: IllegalArgumentException) {
            assertThat(e).hasMessageContaining("workflow-run-id")
        }
    }

    @Test
    fun `custom builder overrides produce consistent result`() = runTest {
        val customRequest = TestApprovalGatewayPersistenceRequestBuilder(
            clock = Clock.systemUTC(),
        )
            .subject(ApprovalSubject("custom-claim"))
            .recommendation(
                ApprovalRecommendation(
                    type = "custom-type",
                    summary = "Custom recommendation",
                ),
            )
            .requiredRole(ApproverRole("custom-role"))
            .workflowRunId(WorkflowRunId("custom-run"))
            .toolName("custom-tool")
            .requestedBy("custom-user")
            .policyVersion("2.0")
            .operationReference(
                serviceInterface = "com.example.CustomWorkflow",
                methodName = "execute",
                jvmMethodDescriptor = "(Lcom/example/Input;)Ldev/tramai/core/workflow/SovereignWorkflowResult;",
            )
            .sensitiveArgumentsJson("""{"customKey":"customValue"}""")
            .build()

        assertThat(customRequest.approvalRequest.requestedBy).isEqualTo("custom-user")
        assertThat(customRequest.approvalRequest.binding.policyVersion).isEqualTo("2.0")
        assertThat(customRequest.suspendedInvocationMetadata.operationReference.serviceInterface)
            .isEqualTo("com.example.CustomWorkflow")
    }
}
