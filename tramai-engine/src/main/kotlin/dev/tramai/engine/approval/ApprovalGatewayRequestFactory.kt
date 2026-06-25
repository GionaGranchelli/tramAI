package dev.tramai.engine.approval

import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.core.approval.gateway.WorkflowRunId

/**
 * Internal seam between the ergonomic [dev.tramai.core.approval.gateway.ApprovalGateway] SPI
 * and the existing low-level persistence records.
 *
 * The public SPI receives high-level domain types ([ApprovalSubject], [ApprovalRecommendation],
 * [ApproverRole]) but the three backing stores require richer runtime metadata: workflow digest,
 * tool identity, argument digests, approval token digests, replay envelopes, and continuation
 * metadata. This factory encapsulates that translation.
 *
 * Preview API — may change as runtime bridge requirements stabilize.
 */
interface ApprovalGatewayRequestFactory {
    suspend fun createRequest(
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
        workflowRunId: WorkflowRunId?,
    ): ApprovalGatewayPersistenceRequest
}
