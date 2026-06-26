package dev.tramai.examples.spring

import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadata
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadataContext
import dev.tramai.spring.sovereign.ops.inbox.ApprovalInboxMetadataFactory

/**
 * Concrete [ApprovalInboxMetadataFactory] for the regulated claim triage scenario.
 *
 * Maps the safe claim context into reviewer-facing inbox metadata labels
 * without exposing raw claim payloads, tool arguments, or replay envelopes.
 *
 * `subjectId` uses the correlation ID (which is derived from the claim ID)
 * as a safe, non-sensitive subject reference.
 */
class RegulatedClaimTriageApprovalInboxMetadataFactory : ApprovalInboxMetadataFactory {

    override fun create(context: ApprovalInboxMetadataContext): ApprovalInboxMetadata =
        ApprovalInboxMetadata(
            requiredRole = ApproverRole("medical-reviewer"),
            riskLevel = "HIGH",
            subjectType = "claim",
            subjectId = context.correlationId,
            recommendationType = "claim-payout",
        )
}
