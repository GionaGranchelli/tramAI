package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.IllegalApprovalTransitionException
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationStore
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecord
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxStatus
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * Transactional [ApprovalDecisionControlPlane] backed by the existing
 * [SovereignOpsApprovalMutationStore] and [ApprovalStore].
 *
 * Each decision creates an audit outbox intent (PREPARED), transitions the
 * approval via the mutation store, and returns a typed result.
 *
 * @param approvalStore stores approval requests
 * @param mutationStore transactional approval mutation store
 * @param authorizer optional decision authorization policy
 * @param clock clock for temporal decisions
 */
class SovereignOpsApprovalDecisionControlPlane(
    private val approvalStore: ApprovalStore,
    private val mutationStore: SovereignOpsApprovalMutationStore,
    private val authorizer: ApprovalDecisionAuthorizer = AllowAllApprovalDecisionAuthorizer,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalDecisionControlPlane {

    override suspend fun approve(command: ApprovalDecisionCommand): ApprovalDecisionResult =
        decide(command, ApprovalDecisionType.APPROVE)

    override suspend fun deny(command: ApprovalDecisionCommand): ApprovalDecisionResult =
        decide(command, ApprovalDecisionType.DENY)

    private suspend fun decide(
        command: ApprovalDecisionCommand,
        decisionType: ApprovalDecisionType,
    ): ApprovalDecisionResult {
        val approval = approvalStore.get(command.approvalId.value)
            ?: return ApprovalDecisionResult.NotFound(command.approvalId)

        val now = clock.instant()

        if (!approval.expiresAt.isAfter(now)) {
            return ApprovalDecisionResult.Expired(command.approvalId, approval.expiresAt)
        }

        if (approval.status == ApprovalStatus.APPROVED) {
            return ApprovalDecisionResult.AlreadyApproved(
                approvalId = ApprovalId(approval.approvalId),
                decidedBy = requireNotNull(approval.decidedBy) { "already approved but no decider" },
                decidedAt = requireNotNull(approval.decidedAt) { "already approved but no timestamp" },
            )
        }
        if (approval.status == ApprovalStatus.DENIED) {
            return ApprovalDecisionResult.AlreadyDenied(
                approvalId = ApprovalId(approval.approvalId),
                decidedBy = requireNotNull(approval.decidedBy) { "already denied but no decider" },
                decidedAt = requireNotNull(approval.decidedAt) { "already denied but no timestamp" },
            )
        }
        if (approval.status != ApprovalStatus.PENDING) {
            return ApprovalDecisionResult.Conflict(
                approvalId = ApprovalId(approval.approvalId),
                reason = "approval-invalid-status-${approval.status.name}",
            )
        }

        if (!authorizer.canDecide(approval, command.actorId, command.actorRole, decisionType)) {
            return ApprovalDecisionResult.Conflict(
                approvalId = ApprovalId(approval.approvalId),
                reason = "approval-unauthorized-decision",
            )
        }

        val eventKey = when (decisionType) {
            ApprovalDecisionType.APPROVE -> "approval-approved"
            ApprovalDecisionType.DENY -> "approval-denied"
        }
        val auditIntent = SovereignOpsAuditOutboxRecord(
            outboxId = UUID.randomUUID().toString(),
            aggregateType = "approval",
            operation = eventKey,
            aggregateIdDigest = sha256Hex(command.approvalId.value),
            eventKey = eventKey,
            actor = command.actorId,
            workflowRunId = approval.binding.workflowRunId,
            correlationId = command.correlationId,
            approvalStatus = when (decisionType) {
                ApprovalDecisionType.APPROVE -> ApprovalStatus.APPROVED.name
                ApprovalDecisionType.DENY -> ApprovalStatus.DENIED.name
            },
            approvalVersion = approval.version + 1,
            reasonDigest = command.comment?.let { sha256Hex(it) } ?: sha256Hex(eventKey),
            reasonLength = command.comment?.length ?: eventKey.length,
            createdAt = now,
            status = SovereignOpsAuditOutboxStatus.PREPARED,
        )

        return try {
            val result = when (decisionType) {
                ApprovalDecisionType.APPROVE ->
                    mutationStore.approveApprovalWithAuditIntent(
                        approvalId = approval.approvalId,
                        expectedVersion = command.expectedVersion ?: approval.version,
                        actor = command.actorId,
                        reason = command.comment ?: eventKey,
                        auditIntent = auditIntent,
                    )

                ApprovalDecisionType.DENY ->
                    mutationStore.denyApprovalWithAuditIntent(
                        approvalId = approval.approvalId,
                        expectedVersion = command.expectedVersion ?: approval.version,
                        actor = command.actorId,
                        reason = command.comment ?: eventKey,
                        auditIntent = auditIntent,
                    )
            }

            when (decisionType) {
                ApprovalDecisionType.APPROVE ->
                    ApprovalDecisionResult.Approved(
                        approvalId = ApprovalId(result.approval.approvalId),
                        decidedBy = result.approval.decidedBy ?: command.actorId,
                        decidedAt = result.approval.decidedAt ?: clock.instant(),
                        version = result.approval.version,
                    )

                ApprovalDecisionType.DENY ->
                    ApprovalDecisionResult.Denied(
                        approvalId = ApprovalId(result.approval.approvalId),
                        decidedBy = result.approval.decidedBy ?: command.actorId,
                        decidedAt = result.approval.decidedAt ?: clock.instant(),
                        version = result.approval.version,
                    )
            }
        } catch (e: IllegalApprovalTransitionException) {
            ApprovalDecisionResult.Conflict(
                approvalId = ApprovalId(approval.approvalId),
                reason = e.message ?: "approval-illegal-transition",
            )
        } catch (_: ApprovalStoreNotFoundException) {
            ApprovalDecisionResult.NotFound(ApprovalId(approval.approvalId))
        } catch (e: IllegalStateException) {
            ApprovalDecisionResult.Conflict(
                approvalId = ApprovalId(approval.approvalId),
                reason = e.message ?: "approval-mutation-failure",
            )
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return "sha256:${hash.joinToString("") { "%02x".format(it) }}"
    }
}
