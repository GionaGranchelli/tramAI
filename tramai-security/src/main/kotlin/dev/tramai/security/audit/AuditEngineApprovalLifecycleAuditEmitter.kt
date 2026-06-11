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
            "toolName" to bounded(toolName),
            "toolCallId" to bounded(toolCallId),
            "argumentsDigest" to bounded(argumentsDigest.value),
            "expiresAt" to expiresAt.toString(),
        )
        auditEngine.emit(
            auditStreamId = safeStreamId(workflowRunId),
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
        val safeActor = safeActorId(resumedBy)
        val metadata = mutableMapOf(
            "approvalId" to bounded(approvalId),
            "toolName" to bounded(toolName),
            "resumedBy" to bounded(safeActor),
        )
        auditEngine.emit(
            auditStreamId = safeStreamId(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = safeActor,
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
        val safeActor = safeActorId(completedBy)
        val metadata = mutableMapOf(
            "approvalId" to bounded(approvalId),
            "toolName" to bounded(toolName),
            "completedBy" to bounded(safeActor),
        )
        auditEngine.emit(
            auditStreamId = safeStreamId(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = safeActor,
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
            "toolName" to bounded(toolName),
            "reasonCode" to safeReasonCode(reason),
        )
        auditEngine.emit(
            auditStreamId = safeStreamId(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = null,
            enforcementPoint = "APPROVAL_UNCERTAIN_OUTCOME",
            decision = "CANCELLED_UNCERTAIN",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = safeReasonCode(reason),
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
            "toolName" to bounded(toolName),
            "reasonCode" to safeReasonCode(reason),
        )
        auditEngine.emit(
            auditStreamId = safeStreamId(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = null,
            enforcementPoint = "APPROVAL_SUSPENSION_CANCELLED",
            decision = "CANCELLED",
            policyVersion = null,
            workflowDigest = null,
            reasonCode = safeReasonCode(reason),
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
            "toolName" to bounded(toolName),
            "claimedAt" to claimedAt.toString(),
        )
        auditEngine.emit(
            auditStreamId = safeStreamId(workflowRunId),
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
        val safeActor = safeActorId(cancelledBy)
        val metadata = mapOf(
            "approvalId" to bounded(approvalId),
            "toolName" to bounded(toolName),
            "cancelledBy" to bounded(safeActor),
            "reasonCode" to safeReasonCode(reasonCode),
        )
        auditEngine.emit(
            auditStreamId = safeStreamId(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = safeActor,
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
        val safeActor = safeActorId(cancelledBy)
        val metadata = mapOf(
            "approvalId" to bounded(approvalId),
            "toolName" to bounded(toolName),
            "cancelledBy" to bounded(safeActor),
            "reasonCode" to safeReasonCode(reasonCode),
        )
        auditEngine.emit(
            auditStreamId = safeStreamId(workflowRunId),
            workflowRunId = workflowRunId,
            correlationId = null,
            actor = safeActor,
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

        private val SAFE_REASON_CODE = Regex("[a-z0-9][a-z0-9._:-]{0,127}")

        /**
         * Safe actor identity pattern. Allows opaque user IDs, service-account IDs,
         * email-like identifiers, UUIDs, and bounded token-shaped values.
         * Rejects free-form comments, secrets, spaces, line breaks, key-value payloads.
         */
        private val SAFE_ACTOR_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")

        private fun bounded(value: String): String =
            if (value.length <= MAX_VALUE_LENGTH) value
            else value.take(MAX_VALUE_LENGTH)

        private fun safeStreamId(raw: String): String {
            require(raw.isNotBlank()) { "Audit stream ID must not be blank" }
            require(raw == raw.trim()) { "Audit stream ID must not have surrounding whitespace" }
            require(raw.length <= MAX_VALUE_LENGTH) { "Audit stream ID exceeds maximum length of $MAX_VALUE_LENGTH" }
            return raw
        }

        private fun safeReasonCode(raw: String): String =
            raw.takeIf(SAFE_REASON_CODE::matches) ?: "approval_reason_redacted"

        /**
         * Defense-in-depth normalization for actor identifiers entering durable audit evidence.
         * Valid values pass through unchanged. Invalid values are redacted to a sentinel.
         */
        private fun safeActorId(raw: String): String =
            raw.takeIf { it.length <= MAX_VALUE_LENGTH && SAFE_ACTOR_ID.matches(it) }
                ?: "approval_actor_redacted"
    }
}
