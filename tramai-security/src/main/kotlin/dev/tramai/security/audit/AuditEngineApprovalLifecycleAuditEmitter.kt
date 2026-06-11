package dev.tramai.security.audit

import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.Sha256Digest
import java.time.Instant

/**
 * Hash-chained audit emitter for approval lifecycle events.
 *
 * Models after [AuditEnginePolicyDecisionAuditEmitter]. Maps each approval
 * lifecycle method to a safe audit event with bounded, allowlisted metadata.
 *
 * Never persists: approval tokens, raw tool arguments, IBAN, invoice content,
 * prompts, or sensitive tool payloads.
 *
 * Persists only: approvalId, workflowRunId, toolName, toolCallId, correlationId,
 * argumentsDigest, resumedBy, completedBy, safe reason codes.
 */
class AuditEngineApprovalLifecycleAuditEmitter(
    private val auditEngine: AuditEngine,
) : ApprovalLifecycleAuditEmitter {

    override suspend fun onToolExecutionSuspended(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        toolCallId: String,
        correlationId: String,
        argumentsDigest: Sha256Digest,
        expiresAt: Instant,
    ) {
        val metadata = mutableMapOf(
            "approvalId" to bounded(approvalId),
            "toolCallId" to bounded(toolCallId),
            "argumentsDigest" to bounded(argumentsDigest.value),
            "expiresAt" to expiresAt.toString(),
        )
        auditEngine.emit(
            auditStreamId = bounded(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = correlationId,
            actor = null,
            enforcementPoint = "APPROVAL_SUSPENDED",
            decision = "PENDING",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = "approval_pending",
            metadata = metadata,
        )
    }

    override suspend fun onToolExecutionResumed(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        resumedBy: String,
    ) {
        val metadata = mapOf(
            "approvalId" to bounded(approvalId),
            "resumedBy" to bounded(resumedBy),
        )
        auditEngine.emit(
            auditStreamId = bounded(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = resumedBy,
            enforcementPoint = "APPROVAL_RESUMED",
            decision = "AUTHORIZED",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = "approval_authorized",
            metadata = metadata,
        )
    }

    override suspend fun onToolExecutionCompleted(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        completedBy: String,
    ) {
        val metadata = mapOf(
            "approvalId" to bounded(approvalId),
            "completedBy" to bounded(completedBy),
        )
        auditEngine.emit(
            auditStreamId = bounded(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = completedBy,
            enforcementPoint = "APPROVAL_COMPLETED",
            decision = "SUCCESS",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = "approval_completed",
            metadata = metadata,
        )
    }

    override suspend fun onUncertainOutcome(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        reason: String,
    ) {
        val metadata = mapOf(
            "approvalId" to bounded(approvalId),
            "reason" to bounded(reason),
        )
        auditEngine.emit(
            auditStreamId = bounded(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = null,
            enforcementPoint = "APPROVAL_UNCERTAIN_OUTCOME",
            decision = "CANCELLED_UNCERTAIN",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = "approval_uncertain",
            metadata = metadata,
        )
    }

    override suspend fun onSuspensionCancelled(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        reason: String,
    ) {
        val metadata = mapOf(
            "approvalId" to bounded(approvalId),
            "reason" to bounded(reason),
        )
        auditEngine.emit(
            auditStreamId = bounded(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = null,
            enforcementPoint = "APPROVAL_SUSPENSION_CANCELLED",
            decision = "CANCELLED",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = "approval_cancelled",
            metadata = metadata,
        )
    }

    override suspend fun onStaleClaimDetected(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        claimedAt: Instant,
    ) {
        val metadata = mapOf(
            "approvalId" to bounded(approvalId),
            "claimedAt" to claimedAt.toString(),
        )
        auditEngine.emit(
            auditStreamId = bounded(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = null,
            enforcementPoint = "APPROVAL_STALE_CLAIM_DETECTED",
            decision = "STALE",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = "approval_stale_detected",
            metadata = metadata,
        )
    }

    override suspend fun onClaimedContinuationForceCancellationRequested(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        cancelledBy: String,
        reasonCode: String,
    ) {
        val metadata = mapOf(
            "approvalId" to bounded(approvalId),
            "cancelledBy" to bounded(cancelledBy),
            "reasonCode" to bounded(reasonCode),
        )
        auditEngine.emit(
            auditStreamId = bounded(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = cancelledBy,
            enforcementPoint = "APPROVAL_FORCE_CANCELLATION_REQUESTED",
            decision = "FORCE_CANCEL",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = "approval_force_cancel_requested",
            metadata = metadata,
        )
    }

    override suspend fun onClaimedContinuationForceCancelled(
        approvalId: String,
        workflowRunId: String,
        toolName: String,
        cancelledBy: String,
        reasonCode: String,
    ) {
        val metadata = mapOf(
            "approvalId" to bounded(approvalId),
            "cancelledBy" to bounded(cancelledBy),
            "reasonCode" to bounded(reasonCode),
        )
        auditEngine.emit(
            auditStreamId = bounded(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = cancelledBy,
            enforcementPoint = "APPROVAL_FORCE_CANCELLED",
            decision = "FORCE_CANCELLED",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = "approval_force_cancelled",
            metadata = metadata,
        )
    }

    companion object {
        private const val MAX_VALUE_LENGTH = 256

        private fun bounded(value: String): String =
            if (value.length <= MAX_VALUE_LENGTH) value
            else value.take(MAX_VALUE_LENGTH)
    }
}
