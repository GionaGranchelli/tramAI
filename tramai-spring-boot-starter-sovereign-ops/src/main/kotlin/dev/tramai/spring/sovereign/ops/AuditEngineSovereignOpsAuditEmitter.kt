package dev.tramai.spring.sovereign.ops

import dev.tramai.security.audit.AuditEmission
import dev.tramai.security.audit.AuditEngine
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.classifyEvidence
import java.security.MessageDigest

/**
 * [AuditEngine]-backed [SovereignOpsAuditEmitter] that emits safe,
 * hash-chained audit events for sovereign ops mutations.
 *
 * ## Security guarantees
 * - Never stores raw approval tokens, resume tokens, or replay envelopes
 * - Never stores raw reason text -- only a digest and length
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

        val metadata =
            mutableMapOf(
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
            ),
        )
    }

    /**
     * Outbox replay emission using pre-digested values.
     *
     * Kept for callers that still hand over a denial directly. A denial is what it says it is, so this
     * routes through the same kind-correct mapping instead of keeping a second, differently-labelled path.
     */
    override suspend fun approvalDeniedFromOutbox(record: SovereignOpsAuditOutboxRecord) {
        evidenceFromOutbox(
            record.copy(evidenceKind = SovereignOpsAuditEvidenceKind.APPROVAL_DENIED),
        )
    }

    /**
     * 0.7.6f §6: emits what the record is evidence of, not what the old emission path assumed.
     *
     * This override is the exact output guarantee for the three kinds: an approval is never labelled a
     * denial, and a refusal is never labelled as either. Stored digests are used as they are -- nothing
     * here re-hashes [SovereignOpsAuditOutboxRecord.aggregateIdDigest] or
     * [SovereignOpsAuditOutboxRecord.reasonDigest].
     */
    override suspend fun evidenceFromOutbox(record: SovereignOpsAuditOutboxRecord) {
        val evidence = classifyEvidence(record)
        val mapping = EMITTED_AS.getValue(evidence.kind)
        val metadata =
            buildMap {
                put(METADATA_APPROVAL_ID_DIGEST, bounded(record.aggregateIdDigest))
                record.approvalStatus?.let { put(METADATA_APPROVAL_STATUS, bounded(it)) }
                record.approvalVersion?.let { put(METADATA_APPROVAL_VERSION, it.toString()) }
                put(METADATA_REASON_DIGEST, bounded(record.reasonDigest))
                put(METADATA_REASON_LENGTH, record.reasonLength.toString())
                // Correlation back to exactly one outbox record.
                put(METADATA_OUTBOX_ID, bounded(record.outboxId))
                put(METADATA_EVENT_KEY, bounded(record.eventKey))
                if (evidence.kind == SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL) {
                    record.refusalReason?.let { put(METADATA_REFUSAL_REASON, bounded(it)) }
                }
                // legacyEvidence says one thing only: the source record carried NO evidenceKind, so its kind
                // was reconstructed from persisted approvalStatus. It does not mean "this emitter took a
                // compatibility path" -- a denial written with a persisted kind sets no flag even though it
                // is emitted by the same code.
                if (evidence.legacy) put(METADATA_LEGACY_EVIDENCE, "true")
            }

        auditEngine.emit(
            AuditEmission(
                auditStreamId = "sovereign-ops-approval:${record.aggregateIdDigest}",
                workflowRunId = record.workflowRunId,
                correlationId = record.correlationId,
                actor = record.actor,
                enforcementPoint = mapping.enforcementPoint,
                decision = mapping.decision,
                policyVersion = null,
                workflowDigest = null,
                reasonCode = mapping.reasonCode,
                metadata = metadata,
            ),
        )
    }

    companion object {
        private const val MAX_BOUNDED_LENGTH = 256

        private const val METADATA_APPROVAL_ID_DIGEST = "approvalIdDigest"
        private const val METADATA_APPROVAL_STATUS = "approvalStatus"
        private const val METADATA_APPROVAL_VERSION = "approvalVersion"
        private const val METADATA_REASON_DIGEST = "reasonDigest"
        private const val METADATA_REASON_LENGTH = "reasonLength"
        private const val METADATA_OUTBOX_ID = "outboxId"
        private const val METADATA_EVENT_KEY = "eventKey"
        private const val METADATA_REFUSAL_REASON = "refusalReason"
        private const val METADATA_LEGACY_EVIDENCE = "legacyEvidence"

        /** What each kind is emitted as. Total by construction: the enum has exactly these members. */
        private val EMITTED_AS: Map<SovereignOpsAuditEvidenceKind, EmissionMapping> =
            mapOf(
                SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED to
                    EmissionMapping(
                        enforcementPoint = "sovereign-ops.approval.decide",
                        decision = "APPROVED",
                        reasonCode = "sovereign-ops-approval-granted",
                    ),
                SovereignOpsAuditEvidenceKind.APPROVAL_DENIED to
                    EmissionMapping(
                        enforcementPoint = "sovereign-ops.approval.decide",
                        decision = "DENIED",
                        reasonCode = "sovereign-ops-admin-denial",
                    ),
                SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL to
                    EmissionMapping(
                        enforcementPoint = "sovereign-ops.approval.authorization",
                        decision = "DENIED",
                        reasonCode = "sovereign-ops-authorization-refused",
                    ),
            )

        private data class EmissionMapping(
            val enforcementPoint: String,
            val decision: String,
            val reasonCode: String,
        )

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest
                .digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        private fun bounded(value: String): String =
            if (value.length <= MAX_BOUNDED_LENGTH) {
                value
            } else {
                value.take(MAX_BOUNDED_LENGTH)
            }
    }
}
