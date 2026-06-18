package dev.tramai.spring.sovereign.ops.outbox

/**
 * Resolver that determines whether a stuck PREPARED outbox record
 * corresponds to a committed approval denial that can be safely
 * moved to PENDING for dispatch.
 *
 * ## Contract
 * Return [SovereignOpsPreparedRecoveryDecision.COMMITTED_DENIED] only
 * when you can prove the approval transition committed. Never dispatch
 * a PREPARED record without positive proof.
 *
 * ## Security invariants
 * - Resolver implementations must not expose raw approval IDs, raw reason
 *   text, or operator comments.
 * - Implementations must not log or persist the encryption key.
 * - Implementations must not throw on the first failure -- resolver
 *   errors are caught and counted in the recovery summary.
 */
fun interface SovereignOpsApprovalRecoveryResolver {

    /**
     * Resolve a stuck PREPARED outbox record.
     *
     * @param record The PREPARED outbox record to resolve.
     * @return [SovereignOpsPreparedRecoveryDecision] indicating the resolution outcome.
     * @throws kotlinx.coroutines.CancellationException Always propagated.
     */
    suspend fun resolvePreparedOutboxRecord(
        record: SovereignOpsAuditOutboxRecord,
    ): SovereignOpsPreparedRecoveryDecision
}

/**
 * Decision returned by [SovereignOpsApprovalRecoveryResolver.resolvePreparedOutboxRecord].
 */
enum class SovereignOpsPreparedRecoveryDecision {
    /** Approval denial is confirmed committed -- safe to move PREPARED -> PENDING. */
    COMMITTED_DENIED,

    /** Approval denial did not complete -- safe to mark FAILED_PERMANENT. */
    NOT_COMMITTED,

    /** Cannot determine -- skip this record. */
    UNKNOWN,
}
