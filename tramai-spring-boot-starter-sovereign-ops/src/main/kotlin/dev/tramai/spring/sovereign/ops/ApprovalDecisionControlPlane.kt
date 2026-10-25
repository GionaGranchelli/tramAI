package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApproverRole
import java.time.Instant

/**
 * Preview decision control-plane API for approving or denying pending approvals.
 *
 * This is the application-facing boundary for human/system decision making.
 * It sits between the [dev.tramai.core.approval.gateway.ApprovalGateway]
 * request API and the future workflow resume runtime.
 *
 * Implementations must use the existing transactional approval mutation/outbox
 * boundary to ensure decisions are durably audited.
 */
interface ApprovalDecisionControlPlane {
    /**
     * Approve a pending approval request.
     * @return typed [ApprovalDecisionResult] indicating outcome.
     */
    suspend fun approve(command: ApprovalDecisionCommand): ApprovalDecisionResult

    /**
     * Deny a pending approval request.
     * @return typed [ApprovalDecisionResult] indicating outcome.
     */
    suspend fun deny(command: ApprovalDecisionCommand): ApprovalDecisionResult
}

/**
 * Command to approve or deny a pending approval request.
 *
 * @param approvalId The approval to decide on.
 * @param actorId The identity of the actor making the decision.
 * @param actorRole The role of the actor making the decision.
 * @param expectedVersion The approval version the caller observed, **required**. It is the command's
 * concurrency precondition, not a hint: the decision is applied only if authoritative state is still at
 * this version, and a mismatch returns [ApprovalDecisionResult.Stale] without mutating anything. There is
 * deliberately no default and no nullable form, because "the caller did not say which version it saw" and
 * "the caller saw the current version" are different facts, and only the second one may mutate state.
 * @param comment Optional human-readable decision comment.
 * The comment may be persisted in approval metadata.
 * Do not include secrets, raw medical details, credentials, or unnecessary PII.
 * The audit outbox stores only digest/length metadata.
 * @param correlationId Optional correlation ID for the decision audit trail.
 *
 * A command is **not** authentication evidence. [actorId] and [actorRole] are claims carried from the
 * caller; the trusted [dev.tramai.core.operator.OperatorPrincipal] produced by the 0.7.6c boundary is not
 * accepted here and cannot be, because holding one is not proof that this command came from its operator.
 * Operator authorization over these commands is 0.7.6e.
 */
data class ApprovalDecisionCommand(
    val approvalId: ApprovalId,
    val actorId: String,
    val actorRole: ApproverRole,
    val expectedVersion: Long,
    val comment: String? = null,
    val correlationId: String? = null,
) {
    init {
        // Negative versions are not part of the approval version domain (it starts at 0), and a command
        // carrying one can never be a valid precondition. Rejecting it at construction means no caller can
        // turn an invalid number into a mutation attempt.
        require(expectedVersion >= 0) { "tramai-approval-invalid-expected-version" }
    }
}

/**
 * Typed result from an approval decision operation.
 */
sealed interface ApprovalDecisionResult {
    /** The approval was successfully approved. */
    data class Approved(
        val approvalId: ApprovalId,
        val decidedBy: String,
        val decidedAt: Instant,
        val version: Long,
    ) : ApprovalDecisionResult

    /** The approval was successfully denied. */
    data class Denied(
        val approvalId: ApprovalId,
        val decidedBy: String,
        val decidedAt: Instant,
        val version: Long,
    ) : ApprovalDecisionResult

    /** The approval was already approved. */
    data class AlreadyApproved(
        val approvalId: ApprovalId,
        val decidedBy: String,
        val decidedAt: Instant,
    ) : ApprovalDecisionResult

    /** The approval was already denied. */
    data class AlreadyDenied(
        val approvalId: ApprovalId,
        val decidedBy: String,
        val decidedAt: Instant,
    ) : ApprovalDecisionResult

    /** The approval has expired and cannot be decided. */
    data class Expired(
        val approvalId: ApprovalId,
        val expiredAt: Instant,
    ) : ApprovalDecisionResult

    /** The approval was not found. */
    data class NotFound(
        val approvalId: ApprovalId,
    ) : ApprovalDecisionResult

    /**
     * The caller's observed version is not the authoritative version. Nothing was mutated and no
     * dispatchable audit fact was committed.
     *
     * This is a distinct outcome rather than a [Conflict] string so a caller can re-read, re-decide and
     * retry, and so a test can assert the precondition failed for the *version* rather than for some other
     * reason. It is also reported ahead of [AlreadyApproved]/[AlreadyDenied]: a caller holding a stale
     * version must not receive an apparently successful idempotent answer.
     */
    data class Stale(
        val approvalId: ApprovalId,
        val expectedVersion: Long,
        val currentVersion: Long,
    ) : ApprovalDecisionResult

    /** Non-version conflict: an illegal state transition or another authoritative refusal. */
    data class Conflict(
        val approvalId: ApprovalId,
        val reason: String,
    ) : ApprovalDecisionResult
}
