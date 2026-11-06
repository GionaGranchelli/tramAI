package dev.tramai.examples.spring

import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.ResumeToken
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import dev.tramai.engine.approval.testing.TestApprovalGatewayPersistenceRequestBuilder
import java.time.Clock

class RegulatedClaimTriageApprovalGatewayRequestFactory(
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalGatewayRequestFactory {
    override suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest {
        val claimId = subject.value
        val workflowRun =
            requireNotNull(workflowRunId) {
                "regulated-claim-triage-workflow-run-id-required"
            }

        return TestApprovalGatewayPersistenceRequestBuilder(clock)
            .subject(subject)
            .recommendation(recommendation)
            .requiredRole(requiredRole)
            .workflowRunId(workflowRun)
            // Pin the policy version this example deploys: 0.7.6e resolves the approver-role requirement by
            // the approval's persisted policyVersion, so a fixture that let two creation paths stamp two
            // different versions would need two policy entries for no reason (F5 residual).
            .policyVersion("0.4.0-preview")
            .approvalId("approval-gateway-$claimId")
            .correlationId("corr-$claimId")
            .resumeToken(ResumeToken("resume-token-$claimId"))
            .toolCallId("tool-call-$claimId")
            .toolName("claim-triage-model")
            .requestedBy("triage-system")
            .operationReference(
                serviceInterface = "dev.tramai.examples.spring.ClaimTriageWorkflow",
                methodName = "triage",
                jvmMethodDescriptor =
                    "(Ldev/tramai/examples/spring/ClaimTriageInput;Ldev/tramai/examples/spring/RequestedRoute;)Ldev/tramai/examples/spring/ClaimTriageResult;",
            ).sensitiveArguments(
                "claimId" to claimId,
                "recommendationType" to recommendation.summary,
                "summary" to recommendation.summary,
            ).build()
    }
}
