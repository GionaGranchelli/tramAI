package dev.tramai.engine.approval.testing

import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
    fun `argumentsDigest changes when sensitive arguments change`() = runTest {
        val builder = TestApprovalGatewayPersistenceRequestBuilder(Clock.systemUTC())
            .subject(ApprovalSubject("claim-digest"))
            .recommendation(ApprovalRecommendation(type = "type", summary = "summary"))
            .requiredRole(ApproverRole("reviewer"))
            .workflowRunId(WorkflowRunId("run-digest"))

        val first = builder.sensitiveArgumentsJson("""{"claimId":"1"}""").build()
        val second = builder.sensitiveArgumentsJson("""{"claimId":"2"}""").build()

        assertThat(first.approvalRequest.binding.argumentsDigest)
            .isNotEqualTo(second.approvalRequest.binding.argumentsDigest)
    }

    @Test
    fun `argumentsDigest is consistent across binding and continuation`() = runTest {
        val request = factory.createRequest(
            subject = ApprovalSubject("claim-2"),
            recommendation = ApprovalRecommendation(
                type = "medical-review",
                summary = "Needs review",
            ),
            requiredRole = ApproverRole("medical-reviewer"),
            workflowRunId = WorkflowRunId("run-2"),
        )

        assertThat(request.continuation.argumentsDigest)
            .isEqualTo(request.approvalRequest.binding.argumentsDigest)
    }

    @Test
    fun `replay envelope digest changes when operation reference changes`() = runTest {
        val builder = TestApprovalGatewayPersistenceRequestBuilder(Clock.systemUTC())
            .subject(ApprovalSubject("claim-digest"))
            .recommendation(ApprovalRecommendation(type = "type", summary = "summary"))
            .requiredRole(ApproverRole("reviewer"))
            .workflowRunId(WorkflowRunId("run-digest"))

        val first = builder
            .operationReference(
                serviceInterface = "com.example.FirstWorkflow",
                methodName = "execute",
                jvmMethodDescriptor = "()V",
            )
            .build()

        val second = builder
            .operationReference(
                serviceInterface = "com.example.SecondWorkflow",
                methodName = "execute",
                jvmMethodDescriptor = "()V",
            )
            .build()

        assertThat(first.suspendedInvocationMetadata.replayEnvelopeDigest)
            .isNotEqualTo(second.suspendedInvocationMetadata.replayEnvelopeDigest)
    }

    @Test
    fun `replay envelope digest is computed`() = runTest {
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
    fun `fails if workflowRunId is missing`() {
        assertThatThrownBy {
            runTest {
                factory.createRequest(
                    subject = ApprovalSubject("claim-6"),
                    recommendation = ApprovalRecommendation(
                        type = "fail-test",
                        summary = "Should fail",
                    ),
                    requiredRole = ApproverRole("reviewer"),
                    workflowRunId = null,
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("workflow-run-id")
    }

    @Test
    fun `builder supports deterministic approvalId correlationId and toolCallId`() = runTest {
        val request = TestApprovalGatewayPersistenceRequestBuilder(Clock.systemUTC())
            .subject(ApprovalSubject("claim-1"))
            .recommendation(ApprovalRecommendation(type = "type", summary = "summary"))
            .requiredRole(ApproverRole("reviewer"))
            .workflowRunId(WorkflowRunId("run-1"))
            .approvalId("approval-custom")
            .correlationId("corr-custom")
            .toolCallId("tool-call-custom")
            .build()

        assertThat(request.approvalRequest.approvalId).isEqualTo("approval-custom")
        assertThat(request.continuation.approvalId).isEqualTo("approval-custom")
        assertThat(request.suspendedInvocationMetadata.approvalId).isEqualTo("approval-custom")
        assertThat(request.continuation.correlationId).isEqualTo("corr-custom")
        assertThat(request.suspendedInvocationMetadata.correlationId).isEqualTo("corr-custom")
        assertThat(request.continuation.toolCallId).isEqualTo("tool-call-custom")
        assertThat(request.suspendedInvocationMetadata.toolCallId).isEqualTo("tool-call-custom")
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
