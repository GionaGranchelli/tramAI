package dev.tramai.spring.sovereign.ops.outbox

/**
 * Summary of a PREPARED recovery operation.
 *
 * Defined in this PR as a future contract for automatic PREPARED
 * reconciliation (PR #48). Not yet used — kept as a forward contract
 * so the DTO shape is agreed before implementation.
 *
 * @property inspected Number of PREPARED records examined.
 * @property movedToPending Number of records recovered to PENDING.
 * @property markedFailedPermanent Number of records marked terminal.
 * @property skipped Number of records left as-is (unresolvable).
 */
data class SovereignOpsAuditOutboxRecoverySummary(
    val inspected: Int,
    val movedToPending: Int?,
    val markedFailedPermanent: Int?,
    val skipped: Int,
)
