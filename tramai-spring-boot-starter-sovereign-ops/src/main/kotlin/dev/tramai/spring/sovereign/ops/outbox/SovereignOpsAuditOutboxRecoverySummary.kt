package dev.tramai.spring.sovereign.ops.outbox

/**
 * Summary of a PREPARED recovery operation.
 *
 * @property inspected Number of PREPARED records examined.
 * @property movedToPending Number of records recovered to PENDING (resolver returned COMMITTED_DENIED).
 * @property markedFailedPermanent Number of records marked FAILED_PERMANENT (resolver returned NOT_COMMITTED).
 * @property skippedUnresolved Number of records left as-is (resolver returned UNKNOWN).
 * @property resolverFailures Number of records skipped due to resolver exception.
 */
data class SovereignOpsAuditOutboxRecoverySummary(
    val inspected: Int,
    val movedToPending: Int = 0,
    val markedFailedPermanent: Int = 0,
    val skippedUnresolved: Int = 0,
    val resolverFailures: Int = 0,
)
