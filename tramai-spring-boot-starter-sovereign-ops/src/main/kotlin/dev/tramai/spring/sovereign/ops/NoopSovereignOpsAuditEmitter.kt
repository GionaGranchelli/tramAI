package dev.tramai.spring.sovereign.ops

/**
 * No-op implementation of [SovereignOpsAuditEmitter].
 *
 * Used when no audit infrastructure (AuditEngine) is available.
 * Allows the ops layer to function in environments where auditing
 * is not configured, with no audit side effects.
 */
object NoopSovereignOpsAuditEmitter : SovereignOpsAuditEmitter {

    override suspend fun approvalDenied(
        approvalId: String,
        actor: String,
        reason: String,
        approvalStatus: String,
        approvalVersion: Long?,
        workflowRunId: String?,
        correlationId: String?,
    ) {
        // No-op — no audit infrastructure available
    }
}
