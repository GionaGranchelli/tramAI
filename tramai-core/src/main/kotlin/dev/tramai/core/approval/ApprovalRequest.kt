package dev.tramai.core.approval

import java.time.Instant

/**
 * An approval request record that tracks the lifecycle of a single human-approval
 * gate within a workflow execution.
 *
 * **Lifecycle:** Created in PENDING status. Transitions to APPROVED, DENIED, or
 * TIMED_OUT via [ApprovalStore.transition]. Once terminal, no further transitions
 * are allowed.
 *
 * **Invariants:**
 * - `version` starts at 0 and is incremented atomically on each transition.
 * - `decidedBy`, `decidedAt`, and `decisionComment` are null until a transition occurs.
 * - `expiresAt` must be strictly in the future at creation time.
 * - The [binding] fields are immutable and lock the exact tool invocation context.
 */
data class ApprovalRequest(
    val approvalId: String,
    val binding: ApprovalBinding,
    val status: ApprovalStatus,
    val requestedBy: String,
    val requestedAt: Instant,
    val expiresAt: Instant,
    val decidedBy: String?,
    val decidedAt: Instant?,
    val decisionComment: String?,
    val version: Long,
)
