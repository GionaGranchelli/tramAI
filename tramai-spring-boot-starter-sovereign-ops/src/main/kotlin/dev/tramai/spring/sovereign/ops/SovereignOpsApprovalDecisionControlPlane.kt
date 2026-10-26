package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.IllegalApprovalTransitionException
import dev.tramai.core.operator.OperatorCapability
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
 * @param approvalPolicy the per-approval approver-role requirement, keyed by the approval's persisted
 * `policyVersion`. Required: an authorized decision cannot be produced without a requirement, and a
 * deployment that has not configured one must fail rather than fall back to allowing any operator.
 * @param clock clock for temporal decisions
 */
class SovereignOpsApprovalDecisionControlPlane(
    private val approvalStore: ApprovalStore,
    private val mutationStore: SovereignOpsApprovalMutationStore,
    private val approvalPolicy: OperatorApprovalPolicy,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalDecisionControlPlane {
    override suspend fun approve(
        command: ApprovalDecisionCommand,
        authority: OperatorAuthority,
    ): ApprovalDecisionResult = decideApprove(command, authority)

    override suspend fun deny(
        command: ApprovalDecisionCommand,
        authority: OperatorAuthority,
    ): ApprovalDecisionResult = decideDeny(command, authority)

    private suspend fun decideApprove(
        command: ApprovalDecisionCommand,
        authority: OperatorAuthority,
    ) = decide(command, authority, ApprovalDecisionType.APPROVE)

    private suspend fun decideDeny(
        command: ApprovalDecisionCommand,
        authority: OperatorAuthority,
    ) = decide(command, authority, ApprovalDecisionType.DENY)

    private suspend fun decide(
        command: ApprovalDecisionCommand,
        authority: OperatorAuthority,
        decisionType: ApprovalDecisionType,
    ): ApprovalDecisionResult {
        // L2, before any state is read: a caller without the capability learns nothing about the approval,
        // not even whether it exists. This is the only layer whose refusal is existence-blind.
        if (!authority.holds(OperatorCapability.DECIDE_APPROVAL)) {
            return ApprovalDecisionResult.Unauthorized(
                approvalId = command.approvalId,
                reason = ApprovalUnauthorizedReason.MISSING_CAPABILITY,
            )
        }

        val approval =
            approvalStore.get(command.approvalId.value)
                ?: return ApprovalDecisionResult.NotFound(command.approvalId)

        // L3, after the lookup because the requirement is keyed by the approval's own persisted
        // policyVersion. A capability holder can therefore distinguish exists-and-role-denied from absent;
        // that disclosure is accepted and recorded in the contract (section 2).
        approvalPolicy.evaluate(approval.binding.policyVersion, authority)?.let { refusal ->
            return ApprovalDecisionResult.Unauthorized(approvalId = ApprovalId(approval.approvalId), reason = refusal)
        }

        // The caller's observed version is the concurrency precondition, checked before anything else. In
        // particular it is checked before the terminal-state shortcuts below: a caller holding a stale
        // version must learn that its precondition is stale, not receive an apparently successful
        // "already decided" answer for a decision it did not make.
        if (command.expectedVersion != approval.version) {
            return ApprovalDecisionResult.Stale(
                approvalId = command.approvalId,
                expectedVersion = command.expectedVersion,
                currentVersion = approval.version,
            )
        }

        // A decision increments the version by one. Refusing at the top of the domain keeps that arithmetic
        // from wrapping into a value that looks like a fresh approval.
        if (approval.version == Long.MAX_VALUE) {
            return ApprovalDecisionResult.Conflict(
                approvalId = ApprovalId(approval.approvalId),
                reason = "tramai-approval-version-exhausted",
            )
        }

        val now = clock.instant()

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
        if (!approval.expiresAt.isAfter(now)) {
            return ApprovalDecisionResult.Expired(command.approvalId, approval.expiresAt)
        }

        val eventKey =
            when (decisionType) {
                ApprovalDecisionType.APPROVE -> "approval-approved.${command.approvalId.value}"
                ApprovalDecisionType.DENY -> "approval-denied.${command.approvalId.value}"
            }
        val auditIntent =
            SovereignOpsAuditOutboxRecord(
                outboxId = UUID.randomUUID().toString(),
                aggregateType = "approval",
                operation = eventKey,
                aggregateIdDigest = sha256Hex(command.approvalId.value),
                eventKey = eventKey,
                actor = authority.subject,
                workflowRunId = approval.binding.workflowRunId,
                correlationId = command.correlationId,
                approvalStatus =
                    when (decisionType) {
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
            val result =
                when (decisionType) {
                    ApprovalDecisionType.APPROVE -> {
                        mutationStore.approveApprovalWithAuditIntent(
                            approvalId = approval.approvalId,
                            expectedVersion = command.expectedVersion,
                            actor = authority.subject,
                            reason = command.comment ?: eventKey,
                            auditIntent = auditIntent,
                        )
                    }

                    ApprovalDecisionType.DENY -> {
                        mutationStore.denyApprovalWithAuditIntent(
                            approvalId = approval.approvalId,
                            expectedVersion = command.expectedVersion,
                            actor = authority.subject,
                            reason = command.comment ?: eventKey,
                            auditIntent = auditIntent,
                        )
                    }
                }

            when (decisionType) {
                ApprovalDecisionType.APPROVE -> {
                    ApprovalDecisionResult.Approved(
                        approvalId = ApprovalId(result.approval.approvalId),
                        decidedBy = result.approval.decidedBy ?: authority.subject,
                        decidedAt = result.approval.decidedAt ?: clock.instant(),
                        version = result.approval.version,
                    )
                }

                ApprovalDecisionType.DENY -> {
                    ApprovalDecisionResult.Denied(
                        approvalId = ApprovalId(result.approval.approvalId),
                        decidedBy = result.approval.decidedBy ?: authority.subject,
                        decidedAt = result.approval.decidedAt ?: clock.instant(),
                        version = result.approval.version,
                    )
                }
            }
        } catch (conflict: ApprovalStoreConflictException) {
            // Another writer won between our read and the compare-and-set. The store's CAS is the final
            // authority, so the answer is derived from persisted state rather than from the read above.
            staleOrConflict(command, conflict)
        } catch (e: IllegalApprovalTransitionException) {
            staleOrConflict(command, e)
        } catch (_: ApprovalStoreNotFoundException) {
            ApprovalDecisionResult.NotFound(ApprovalId(approval.approvalId))
        } catch (e: IllegalStateException) {
            ApprovalDecisionResult.Conflict(
                approvalId = ApprovalId(approval.approvalId),
                reason = e.message ?: "approval-mutation-failure",
            )
        }
    }

    /**
     * Re-reads authoritative state after a refused mutation and reports a [ApprovalDecisionResult.Stale]
     * when the version moved. Anything else stays a [ApprovalDecisionResult.Conflict]: the version was
     * current, so something other than concurrency refused the transition.
     */
    private suspend fun staleOrConflict(
        command: ApprovalDecisionCommand,
        refusal: Exception,
    ): ApprovalDecisionResult {
        val current =
            approvalStore.get(command.approvalId.value)
                ?: return ApprovalDecisionResult.NotFound(command.approvalId)
        return if (current.version != command.expectedVersion) {
            ApprovalDecisionResult.Stale(
                approvalId = command.approvalId,
                expectedVersion = command.expectedVersion,
                currentVersion = current.version,
            )
        } else {
            ApprovalDecisionResult.Conflict(
                approvalId = command.approvalId,
                reason = refusal.message ?: "approval-decision-conflict",
            )
        }
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return "sha256:${hash.joinToString("") { "%02x".format(it) }}"
    }
}
