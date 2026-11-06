package dev.tramai.spring.sovereign.ops

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditEvidenceKind
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.classifyEvidence

/**
 * Audit emitter for sovereign operations mutations.
 *
 * Implementations produce safe, hash-chained audit events for every
 * state-changing operation exposed through the ops layer. The noop
 * implementation is used when no audit infrastructure is available.
 *
 * Sensitive data (approval tokens, resume tokens, raw replay envelopes,
 * raw tool arguments, prompts, responses, raw reason text) must NEVER
 * be included in audit events.
 */
fun interface SovereignOpsAuditEmitter {
    /**
     * Whether this emitter is backed by real audit infrastructure.
     *
     * Returns `true` for real implementations (e.g. [AuditEngineSovereignOpsAuditEmitter])
     * and `false` for [NoopSovereignOpsAuditEmitter]. Mutations should fail
     * closed when this returns `false`.
     */
    fun isActive(): Boolean = true

    /**
     * Called when an approval is denied through the sovereign ops layer.
     *
     * @param approvalId The approval request identifier.
     * @param actor The identity of the actor who performed the denial.
     * @param reason The human-readable reason for denial (never stored raw).
     * @param approvalStatus The resulting approval status (e.g. "DENIED").
     * @param approvalVersion The version of the approval after the transition.
     * @param workflowRunId The workflow run ID, if available.
     * @param correlationId The correlation ID, if available.
     */
    suspend fun approvalDenied(
        approvalId: String,
        actor: String,
        reason: String,
        approvalStatus: String,
        approvalVersion: Long?,
        workflowRunId: String?,
        correlationId: String?,
    )

    /**
     * Called during outbox dispatch replay with pre-digested audit values.
     *
     * Unlike [approvalDenied], this method receives already-digested values
     * from the outbox record — the implementation must NOT re-hash them.
     *
     * Implementations that support outbox replay MUST override this method.
     * The default throws [UnsupportedOperationException] to prevent silent
     * double-hashing of digested values.
     */
    suspend fun approvalDeniedFromOutbox(record: SovereignOpsAuditOutboxRecord): Unit =
        throw UnsupportedOperationException("tramai-sovereign-ops-outbox-emission-unsupported")

    /**
     * Called during outbox dispatch replay, for evidence of any kind.
     *
     * Binary compatibility is not semantic correctness: a default that forwarded every record to
     * [approvalDeniedFromOutbox] would keep labelling approvals and refusals as denials, which is the
     * defect 0.7.6f removes. The default is therefore **kind-aware**:
     * - [SovereignOpsAuditEvidenceKind.APPROVAL_DENIED] -> [approvalDeniedFromOutbox], which is correct
     *   for the kind it names;
     * - [SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED] and
     *   [SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL] -> [UnsupportedOperationException], so an
     *   emitter that cannot represent them stops emitting instead of mislabelling. The record is kept and
     *   parked as `FAILED_PERMANENT` by the dispatcher: visible, not silently wrong.
     *
     * A legacy record (no persisted kind) is classified from its persisted status first, and fails closed
     * when that status cannot name a kind.
     */
    suspend fun evidenceFromOutbox(record: SovereignOpsAuditOutboxRecord) {
        when (classifyEvidence(record).kind) {
            SovereignOpsAuditEvidenceKind.APPROVAL_DENIED -> {
                approvalDeniedFromOutbox(record)
            }

            SovereignOpsAuditEvidenceKind.APPROVAL_APPROVED -> {
                throw UnsupportedOperationException("tramai-sovereign-ops-approval-evidence-unsupported")
            }

            SovereignOpsAuditEvidenceKind.AUTHORIZATION_REFUSAL -> {
                throw UnsupportedOperationException("tramai-sovereign-ops-refusal-evidence-unsupported")
            }
        }
    }
}
