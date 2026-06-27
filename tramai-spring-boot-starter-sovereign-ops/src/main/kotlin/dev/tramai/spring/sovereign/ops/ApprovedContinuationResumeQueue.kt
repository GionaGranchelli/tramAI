package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApprovalId
import java.time.Instant

/**
 * SPI for claiming and managing approved + pending continuations eligible for auto-resume.
 *
 * Implementations must use `SELECT ... FOR UPDATE SKIP LOCKED` to avoid
 * duplicate resume under concurrent workers.
 */
interface ApprovedContinuationResumeQueue {

    /**
     * Claim up to [limit] approved + pending continuations that are eligible
     * for resume. The lease expires at [leaseUntil] — the worker must complete
     * processing and call [markResumeSucceeded] or [markResumeFailed] before then.
     *
     * Each item is claimed with [workerId] so that another worker running in
     * parallel does not pick up the same item.
     */
    suspend fun claimApprovedPending(
        workerId: String,
        limit: Int,
        leaseUntil: Instant,
    ): List<ApprovedContinuationResumeWorkItem>

    /**
     * Mark a claimed item as successfully resumed.
     */
    suspend fun markResumeSucceeded(approvalId: ApprovalId, workerId: String)

    /**
     * Mark a claimed item as failed.
     *
     * @param retryAt when to make this item available for retry, or null for terminal failure.
     */
    suspend fun markResumeFailed(
        approvalId: ApprovalId,
        workerId: String,
        reasonCode: String,
        retryAt: Instant? = null,
    )
}

/**
 * A work item claimed by the resume worker.
 */
data class ApprovedContinuationResumeWorkItem(
    val approvalId: ApprovalId,
    val approvalVersion: Long,
    val continuationVersion: Long,
    val workflowRunId: String,
)
