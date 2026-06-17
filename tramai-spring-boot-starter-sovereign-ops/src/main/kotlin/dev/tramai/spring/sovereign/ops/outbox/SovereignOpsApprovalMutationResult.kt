package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.approval.ApprovalRequest

/**
 * Result of an atomic approval mutation with audit intent.
 */
data class SovereignOpsApprovalMutationResult(
    val approval: ApprovalRequest,
    val auditOutboxRecord: SovereignOpsAuditOutboxRecord,
)
