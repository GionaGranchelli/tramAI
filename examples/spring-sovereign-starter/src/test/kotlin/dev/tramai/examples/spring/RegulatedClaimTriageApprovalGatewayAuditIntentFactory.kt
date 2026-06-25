package dev.tramai.examples.spring

import dev.tramai.core.approval.gateway.ApprovalRecommendation
import dev.tramai.core.approval.gateway.ApprovalSubject
import dev.tramai.core.approval.gateway.ApproverRole
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import dev.tramai.spring.sovereign.ops.ApprovalGatewayAuditIntentFactory
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * Audit intent factory for the regulated claim triage scenario.
 *
 * Creates a [SovereignOpsAuditOutboxRecord] with event key
 * `regulated-claim-triage.approval-requested` that is persisted atomically with the
 * approval, suspended invocation, and continuation records.
 *
 * @param clock clock for record creation timestamp
 */
class RegulatedClaimTriageApprovalGatewayAuditIntentFactory(
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalGatewayAuditIntentFactory {

    override fun approvalRequested(
        request: ApprovalGatewayPersistenceRequest,
        subject: ApprovalSubject,
        recommendation: ApprovalRecommendation,
        requiredRole: ApproverRole,
    ): SovereignOpsAuditOutboxRecord {
        val claimId = subject.value
        return SovereignOpsAuditOutboxRecord(
            outboxId = UUID.randomUUID().toString(),
            aggregateType = "approval",
            operation = "approvalRequested",
            aggregateIdDigest = sha256Hex(claimId),
            eventKey = "regulated-claim-triage.approval-requested.${request.approvalRequest.approvalId}",
            actor = "triage-system",
            workflowRunId = request.approvalRequest.binding.workflowRunId,
            correlationId = request.suspendedInvocationMetadata.correlationId,
            approvalStatus = "PENDING",
            approvalVersion = 0L,
            reasonDigest = sha256Hex("approval-requested"),
            reasonLength = "approval-requested".length,
            createdAt = clock.instant(),
        )
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return "sha256:${hash.joinToString("") { "%02x".format(it) }}"
    }
}
