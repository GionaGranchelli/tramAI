package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStatus
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.exception.ApprovalStoreConflictException
import dev.tramai.core.exception.ApprovalStoreNotFoundException
import dev.tramai.core.exception.IllegalApprovalTransitionException
import dev.tramai.core.operator.OperatorCapability
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsApprovalMutationResult
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
        // The capability gate runs before the lookup; everything after it necessarily follows a read.
        capabilityRefusal(command, authority)?.let { return it }
        val approval = approvalStore.get(command.approvalId.value)
        return if (approval == null) {
            ApprovalDecisionResult.NotFound(command.approvalId)
        } else {
            approvalRefusal(approval, approvalPolicy, authority)
                ?: preconditionRefusal(command, approval, clock)
                ?: mutate(command, approval, authority, decisionType)
        }
    }

    private suspend fun mutate(
        command: ApprovalDecisionCommand,
        approval: ApprovalRequest,
        authority: OperatorAuthority,
        decisionType: ApprovalDecisionType,
    ): ApprovalDecisionResult {
        val now = clock.instant()
        val auditIntent = auditIntentFor(command, approval, authority, decisionType, now)

        return try {
            val result = applyMutation(mutationStore, command, authority, decisionType, auditIntent)

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

/**
 * L2: the capability gate, evaluated **before any state is read**.
 *
 * This is the only refusal in the system that is existence-blind: a caller without `DECIDE_APPROVAL`
 * receives the same answer whether or not the approval exists, so the endpoint cannot be used to probe for
 * approvals. Every later refusal necessarily follows a lookup and therefore discloses existence, which the
 * contract accepts and records.
 */
/** Digest helper shared by the audit-intent construction. */
private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
    return "sha256:${hash.joinToString("") { "%02x".format(it) }}"
}

private fun capabilityRefusal(
    command: ApprovalDecisionCommand,
    authority: OperatorAuthority,
): ApprovalDecisionResult? =
    if (authority.holds(OperatorCapability.DECIDE_APPROVAL)) {
        null
    } else {
        ApprovalDecisionResult.Unauthorized(command.approvalId, ApprovalUnauthorizedReason.MISSING_CAPABILITY)
    }

/**
 * L3: the per-approval role requirement, evaluated after the lookup because it is keyed by the approval's
 * own persisted `policyVersion`.
 */
private fun approvalRefusal(
    approval: ApprovalRequest,
    approvalPolicy: OperatorApprovalPolicy,
    authority: OperatorAuthority,
): ApprovalDecisionResult? =
    approvalPolicy.evaluate(approval.binding.policyVersion, authority)?.let { refusal ->
        ApprovalDecisionResult.Unauthorized(ApprovalId(approval.approvalId), refusal)
    }

/**
 * The 0.7.6d preconditions, in their committed order.
 *
 * The caller's observed version is checked before the terminal-state shortcuts: a caller holding a stale
 * version must learn that its precondition is stale, not receive an apparently successful "already decided"
 * answer for a decision it did not make. Version exhaustion is refused at the top of the domain so the
 * increment cannot wrap into a value that looks like a fresh approval.
 */
private fun preconditionRefusal(
    command: ApprovalDecisionCommand,
    approval: ApprovalRequest,
    clock: Clock,
): ApprovalDecisionResult? {
    val approvalId = ApprovalId(approval.approvalId)
    val now = clock.instant()
    return when {
        command.expectedVersion != approval.version ->
            ApprovalDecisionResult.Stale(command.approvalId, command.expectedVersion, approval.version)

        approval.version == Long.MAX_VALUE ->
            ApprovalDecisionResult.Conflict(approvalId, "tramai-approval-version-exhausted")

        approval.status == ApprovalStatus.APPROVED ->
            ApprovalDecisionResult.AlreadyApproved(
                approvalId,
                requireNotNull(approval.decidedBy) { "already approved but no decider" },
                requireNotNull(approval.decidedAt) { "already approved but no timestamp" },
            )

        approval.status == ApprovalStatus.DENIED ->
            ApprovalDecisionResult.AlreadyDenied(
                approvalId,
                requireNotNull(approval.decidedBy) { "already denied but no decider" },
                requireNotNull(approval.decidedAt) { "already denied but no timestamp" },
            )

        approval.status != ApprovalStatus.PENDING ->
            ApprovalDecisionResult.Conflict(approvalId, "approval-invalid-status-${approval.status.name}")

        !approval.expiresAt.isAfter(now) ->
            ApprovalDecisionResult.Expired(command.approvalId, approval.expiresAt)

        else -> null
    }
}

/** The audit event key for a decision; derived, never carried on the command. */
private fun eventKeyFor(
    command: ApprovalDecisionCommand,
    decisionType: ApprovalDecisionType,
): String =
    when (decisionType) {
        ApprovalDecisionType.APPROVE -> "approval-approved.${command.approvalId.value}"
        ApprovalDecisionType.DENY -> "approval-denied.${command.approvalId.value}"
    }

/**
 * Dispatch to the authoritative mutation store.
 *
 * Extracted so the decision path reads as guards-then-write, and so the deny/approve symmetry is one
 * `when` rather than two copies of five arguments inside a try block.
 */
private suspend fun applyMutation(
    mutationStore: SovereignOpsApprovalMutationStore,
    command: ApprovalDecisionCommand,
    authority: OperatorAuthority,
    decisionType: ApprovalDecisionType,
    auditIntent: SovereignOpsAuditOutboxRecord,
): SovereignOpsApprovalMutationResult =
    when (decisionType) {
        ApprovalDecisionType.APPROVE ->
            mutationStore.approveApprovalWithAuditIntent(
                approvalId = command.approvalId.value,
                expectedVersion = command.expectedVersion,
                actor = authority.subject,
                reason = command.comment ?: eventKeyFor(command, decisionType),
                auditIntent = auditIntent,
            )

        ApprovalDecisionType.DENY ->
            mutationStore.denyApprovalWithAuditIntent(
                approvalId = command.approvalId.value,
                expectedVersion = command.expectedVersion,
                actor = authority.subject,
                reason = command.comment ?: eventKeyFor(command, decisionType),
                auditIntent = auditIntent,
            )
    }

/** Builds the PREPARED audit intent for this decision. Kept separate so the write path stays readable. */
private fun auditIntentFor(
    command: ApprovalDecisionCommand,
    approval: ApprovalRequest,
    authority: OperatorAuthority,
    decisionType: ApprovalDecisionType,
    now: java.time.Instant,
): SovereignOpsAuditOutboxRecord =
        SovereignOpsAuditOutboxRecord(
            outboxId = UUID.randomUUID().toString(),
            aggregateType = "approval",
            operation = eventKeyFor(command, decisionType),
            aggregateIdDigest = sha256Hex(command.approvalId.value),
            eventKey = eventKeyFor(command, decisionType),
            actor = authority.subject,
            workflowRunId = approval.binding.workflowRunId,
            correlationId = command.correlationId,
            approvalStatus =
                when (decisionType) {
                    ApprovalDecisionType.APPROVE -> ApprovalStatus.APPROVED.name
                    ApprovalDecisionType.DENY -> ApprovalStatus.DENIED.name
                },
            approvalVersion = approval.version + 1,
            reasonDigest = command.comment?.let { sha256Hex(it) } ?: sha256Hex(eventKeyFor(command, decisionType)),
            reasonLength = command.comment?.length ?: eventKeyFor(command, decisionType).length,
            createdAt = now,
            status = SovereignOpsAuditOutboxStatus.PREPARED,
        )
