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
 * @param comment Optional human-readable decision comment.
 * The comment may be persisted in approval metadata.
 * Do not include secrets, raw medical details, credentials, or unnecessary PII.
 * The audit outbox stores only digest/length metadata.
 * @param expectedVersion Expected approval version for optimistic concurrency (null = auto-detect).
 * @param correlationId Optional correlation ID for the decision audit trail.
 */
data class ApprovalDecisionCommand(
    val approvalId: ApprovalId,
    val actorId: String,
    val actorRole: ApproverRole,
    val comment: String? = null,
    val expectedVersion: Long? = null,
    val correlationId: String? = null,
)

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

    /** Version conflict or invalid state transition. */
    data class Conflict(
        val approvalId: ApprovalId,
        val reason: String,
    ) : ApprovalDecisionResult
}
