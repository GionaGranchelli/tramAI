package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApprovalId

/**
 * Runtime-owned worker that finds approved + pending continuations and resumes
 * them using the internal encrypted credential store.
 *
 * This worker calls [ApprovalResumeControlPlane.resume] with sealed credentials
 * from [ApprovalResumeCredentialStore] — it never exposes resume tokens to
 * human-facing surfaces.
 *
 * @see SovereignOpsApprovedContinuationResumeWorker for the implementation.
 */
interface ApprovedContinuationResumeWorker {

    /**
     * Run one cycle: claim resumable items, resume them, and record outcomes.
     *
     * @param limit max items to process in this cycle.
     * @return summary of the cycle.
     */
    suspend fun runOnce(limit: Int = 50): ApprovedContinuationResumeWorkerResult
}

/**
 * Summary of one [ApprovedContinuationResumeWorker.runOnce] cycle.
 */
data class ApprovedContinuationResumeWorkerResult(
    val scanned: Int,
    val resumed: Int,
    val skipped: Int,
    val failed: Int,
)
