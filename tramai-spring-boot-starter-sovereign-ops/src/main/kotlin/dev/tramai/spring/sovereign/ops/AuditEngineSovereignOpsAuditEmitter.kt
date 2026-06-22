package dev.tramai.spring.sovereign.ops

import dev.tramai.security.audit.AuditEmission
import dev.tramai.security.audit.AuditEngine
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import java.security.MessageDigest

/**
 * [AuditEngine]-backed [SovereignOpsAuditEmitter] that emits safe,
 * hash-chained audit events for sovereign ops mutations.
 *
 * ## Security guarantees
 * - Never stores raw approval tokens, resume tokens, or replay envelopes
 * - Never stores raw reason text — only a digest and length
 * - Approval ID is digested in the audit stream ID to avoid raw ID leakage
 * - All metadata values are bounded to prevent unbounded audit bloat
 * - Actor is stored as provided (already validated by the caller)
 */
class AuditEngineSovereignOpsAuditEmitter(
    private val auditEngine: AuditEngine,
) : SovereignOpsAuditEmitter {

    override suspend fun approvalDenied(
        approvalId: String,
        actor: String,
        reason: String,
        approvalStatus: String,
        approvalVersion: Long?,
        workflowRunId: String?,
        correlationId: String?,
    ) {
        val approvalIdDigest = sha256Hex("sovereign-ops-approval:$approvalId")
        val reasonDigest = sha256Hex("sovereign-ops-reason:$reason")

        val metadata = mutableMapOf(
            "approvalIdDigest" to bounded(approvalIdDigest),
            "approvalStatus" to bounded(approvalStatus),
            "approvalVersion" to (approvalVersion?.toString() ?: "unknown"),
            "reasonDigest" to bounded(reasonDigest),
            "reasonLength" to reason.length.toString(),
        )

        auditEngine.emit(
            AuditEmission(
                auditStreamId = "sovereign-ops-approval:$approvalIdDigest",
                workflowRunId = workflowRunId,
                correlationId = correlationId,
                actor = actor,
                enforcementPoint = "sovereign-ops.approval.deny",
                decision = "DENIED",
                policyVersion = null,
                workflowDigest = null,
                reasonCode = "sovereign-ops-admin-denial",
                metadata = metadata,
            )
        )
    }

    /**
     * Outbox replay emission using pre-digested values.
     *
     * This override does NOT re-hash [SovereignOpsAuditOutboxRecord.aggregateIdDigest]
     * or [SovereignOpsAuditOutboxRecord.reasonDigest] — it uses the values
     * stored in the outbox record directly, avoiding the double-hashing bug.
     */
    override suspend fun approvalDeniedFromOutbox(record: SovereignOpsAuditOutboxRecord) {
        val metadata = mutableMapOf(
            "approvalIdDigest" to bounded(record.aggregateIdDigest),
            "approvalStatus" to bounded(record.approvalStatus),
            "approvalVersion" to (record.approvalVersion?.toString() ?: "unknown"),
            "reasonDigest" to bounded(record.reasonDigest),
            "reasonLength" to record.reasonLength.toString(),
        )

        auditEngine.emit(
            AuditEmission(
                auditStreamId = "sovereign-ops-approval:${record.aggregateIdDigest}",
                workflowRunId = record.workflowRunId,
                correlationId = record.correlationId,
                actor = record.actor,
                enforcementPoint = "sovereign-ops.approval.deny",
                decision = "DENIED",
                policyVersion = null,
                workflowDigest = null,
                reasonCode = "sovereign-ops-admin-denial",
                metadata = metadata,
            )
        )
    }

    companion object {
        private const val MAX_BOUNDED_LENGTH = 256

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        private fun bounded(value: String): String =
            if (value.length <= MAX_BOUNDED_LENGTH) value
            else value.take(MAX_BOUNDED_LENGTH)
    }
}
