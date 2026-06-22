package dev.tramai.spring.sovereign.ops.outbox

/**
 * Default recovery resolver that always returns [SovereignOpsPreparedRecoveryDecision.UNKNOWN].
 *
 * This ensures the safest default: PREPARED records are never automatically
 * moved to PENDING without a resolver that can prove the approval denial
 * committed. Custom resolvers are expected for production use.
 */
object UnknownSovereignOpsApprovalRecoveryResolver : SovereignOpsApprovalRecoveryResolver {
    override suspend fun resolvePreparedOutboxRecord(
        record: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsPreparedRecoveryDecision = SovereignOpsPreparedRecoveryDecision.UNKNOWN
}
