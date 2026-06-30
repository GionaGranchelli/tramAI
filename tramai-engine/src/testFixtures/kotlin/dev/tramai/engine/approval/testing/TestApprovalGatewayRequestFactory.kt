package dev.tramai.engine.approval.testing

import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.engine.approval.ApprovalGatewayRequestFactory
import java.time.Clock

/**
 * A reusable test/example fixture implementing [ApprovalGatewayRequestFactory].
 *
 * Applications provide scenario-specific metadata through
 * [TestApprovalGatewayRequestDefaults] and the builder's fluent API.
 *
 * **This is test/example support only.** It is not production API.
 */
class TestApprovalGatewayRequestFactory(
    private val clock: Clock = Clock.systemUTC(),
    private val defaults: TestApprovalGatewayRequestDefaults = TestApprovalGatewayRequestDefaults(),
) : ApprovalGatewayRequestFactory {

    override suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest =
        TestApprovalGatewayPersistenceRequestBuilder(clock, defaults)
            .subject(subject)
            .recommendation(recommendation)
            .requiredRole(requiredRole)
            .workflowRunId(
                requireNotNull(workflowRunId) {
                    "test-approval-gateway-workflow-run-id-required"
                },
            )
            .build()
}
