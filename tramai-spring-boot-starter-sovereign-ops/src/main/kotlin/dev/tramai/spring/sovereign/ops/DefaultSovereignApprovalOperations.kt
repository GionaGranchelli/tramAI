package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.spring.sovereign.ops.outbox.DefaultSovereignOpsAuditDigestService
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditDigestService
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatcher
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStore
import kotlinx.coroutines.CancellationException

/**
 * Default implementation of [SovereignApprovalOperations].
 *
 * Read operations delegate directly to an [ApprovalStore].
 * Write operations ([denyApproval]) use a [SovereignOpsApprovalMutationStore]
 * for atomic approval denial + audit outbox intent, then dispatch pending
 * outbox records for audit emission.
 *
 * ## Atomicity guarantee
 * The approval transition is only attempted after the audit outbox record
 * is appended. If audit dispatch fails, the operation still returns success
 * because the audit intent is durably recorded and can be retried later.
 *
 * ## Durability gate
 * Mutations require a durable outbox store ([SovereignOpsAuditOutboxStore.isDurable]).
 * In-memory outbox stores are rejected to prevent audit intent loss on restart.
 */
class DefaultSovereignApprovalOperations(
    private val approvalStore: ApprovalStore,
    private val mutationStore: SovereignOpsApprovalMutationStore,
    private val properties: SovereignOpsProperties,
    private val outboxDispatcher: SovereignOpsAuditOutboxDispatcher?,
    private val outboxStore: SovereignOpsAuditOutboxStore,
    private val digestService: SovereignOpsAuditDigestService = DefaultSovereignOpsAuditDigestService,
) : SovereignApprovalOperations {

    private companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")
        private val SAFE_ACTOR = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,255}")
        private const val MAX_REASON_LENGTH = 4096
    }

    override suspend fun getApproval(approvalId: String): SovereignApprovalSummary? {
        validateApprovalId(approvalId)
        return approvalStore.get(approvalId)?.toSummary()
    }

    override suspend fun denyApproval(
        approvalId: String,
        actor: String,
        reason: String,
    ): SovereignApprovalSummary {
        check(properties.mutationsEnabled) {
            "tramai-sovereign-ops-mutations-disabled"
        }
        validateApprovalId(approvalId)
        validateActor(actor)
        validateReason(reason)

        checkNotNull(outboxDispatcher) {
            "tramai-sovereign-ops-audit-unavailable"
        }

        check(outboxStore.isDurable()) {
            "tramai-sovereign-ops-audit-outbox-not-durable"
        }

        val request = approvalStore.get(approvalId)
            ?: throw IllegalStateException(ERROR_INVALID_APPROVAL_ID)

        val approvalIdDigest = digestService.approvalIdDigest(approvalId)
        val reasonDigest = digestService.reasonDigest(reason)
        val eventKey = "deny:$approvalIdDigest:${request.version + 1}"

        val auditIntent = SovereignOpsAuditOutboxRecord(
            aggregateIdDigest = approvalIdDigest,
            eventKey = eventKey,
            actor = actor,
            workflowRunId = request.binding.workflowRunId,
            correlationId = null,
            approvalStatus = "DENIED",
            approvalVersion = request.version + 1,
            reasonDigest = reasonDigest,
            reasonLength = reason.length,
        )

        val result = mutationStore.denyApprovalWithAuditIntent(
            approvalId = approvalId,
            expectedVersion = request.version,
            actor = actor,
            reason = reason,
            auditIntent = auditIntent,
        )

        // Dispatch the outbox record. If dispatch fails, the mutation + outbox
        // are already durable — the record can be retried later.
        try {
            outboxDispatcher.dispatchPending(limit = 1)
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // Non-fatal: audit intent is durably recorded, will be retried
        }

        return result.approval.toSummary()
    }

    // ── Validation ──

    private fun validateApprovalId(id: String) {
        require(id.isNotBlank()) { ERROR_INVALID_APPROVAL_ID }
        require(id.length <= 128) { ERROR_INVALID_APPROVAL_ID }
        require(SAFE_ID.matches(id)) { ERROR_INVALID_APPROVAL_ID }
    }

    private fun validateActor(actor: String) {
        require(actor.isNotBlank()) { ERROR_INVALID_ACTOR }
        require(actor.length <= 256) { ERROR_INVALID_ACTOR }
        require(SAFE_ACTOR.matches(actor)) { ERROR_INVALID_ACTOR }
    }

    private fun validateReason(reason: String) {
        require(reason.isNotBlank()) { "tramai-sovereign-ops-invalid-reason" }
        require(reason.length <= MAX_REASON_LENGTH) { "tramai-sovereign-ops-invalid-reason" }
    }

    // ── Mapping ──

    private fun ApprovalRequest.toSummary(): SovereignApprovalSummary =
        SovereignApprovalSummary(
            approvalId = approvalId,
            status = status.name,
            workflowRunId = binding.workflowRunId,
            correlationId = null,
            createdAt = requestedAt,
            expiresAt = expiresAt,
            actor = decidedBy,
            reasonCode = decisionComment,
        )
}

/** @see DefaultSovereignApprovalOperations */
private const val ERROR_INVALID_APPROVAL_ID = "tramai-sovereign-ops-invalid-approval-id"

/** @see DefaultSovereignApprovalOperations */
private const val ERROR_INVALID_ACTOR = "tramai-sovereign-ops-invalid-actor"
