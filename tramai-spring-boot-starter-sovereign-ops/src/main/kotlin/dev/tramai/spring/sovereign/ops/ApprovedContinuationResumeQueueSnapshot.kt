package dev.tramai.spring.sovereign.ops

/**
 * Sanitised aggregated snapshot of the approved-continuation resume queue.
 *
 * Contains only counts and safe scalar values — no approval IDs,
 * workflow run IDs, resume tokens, metadata, or raw error messages.
 *
 * Field semantics mirror the eligibility rules of the active resume queue
 * implementation's claim query — approved approval, pending continuation,
 * valid credential with matching workflow_run_id, non-expired,
 * lease/reclaim eligibility, and retry due.
 *
 * @property eligibleNow items that can be claimed right now.
 * @property delayedRetry items waiting for retry backoff.
 * @property activeLeases items claimed with non-expired lease.
 * @property expiredLeases items claimed with expired lease (reclaimable).
 * @property terminalFailures continuations cancelled with a resume error code.
 * @property oldestEligibleAgeSeconds age in seconds of the oldest eligible item, or null if none.
 * @property oldestRetryDueInSeconds seconds until the oldest retry is due, or null if none.
 * @property lastErrorCodeCounts safe reason code → count for retryable and terminal resume failures (class names only, no messages).
 */
data class ApprovedContinuationResumeQueueSnapshot(
    val eligibleNow: Long,
    val delayedRetry: Long,
    val activeLeases: Long,
    val expiredLeases: Long,
    val terminalFailures: Long,
    val oldestEligibleAgeSeconds: Long?,
    val oldestRetryDueInSeconds: Long?,
    val lastErrorCodeCounts: Map<String, Long>,
)
