package dev.tramai.spring.sovereign.ops

import dev.tramai.core.approval.gateway.ApprovalId
import dev.tramai.core.approval.gateway.ApprovalResumeCredentialStore
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Runtime-owned implementation of [ApprovedContinuationResumeWorker].
 *
 * Flow per cycle:
 * 1. Claim approved + pending continuations via [ApprovedContinuationResumeQueue].
 * 2. For each item, load the sealed resume credential.
 * 3. If credential missing → mark failed (terminal) and continue.
 * 4. Call [ApprovalResumeControlPlane.resume] with the revealed token.
 * 5. On success → mark succeeded, delete credential.
 * 6. On already-completed → mark succeeded (reconciliation), delete stale credential.
 * 7. On expired conflict → mark failed (terminal), delete credential.
 * 8. On other failure → mark failed (retryable), keep credential.
 *
 * Never exposes resume tokens to logs, REST, or human-facing surfaces.
 *
 * @param queue claim store for approved continuations.
 * @param credentialStore internal encrypted credential store (PR #111).
 * @param resumeControlPlane the approval resume control plane.
 * @param clock clock for temporal checks.
 */
class SovereignOpsApprovedContinuationResumeWorker(
    private val queue: ApprovedContinuationResumeQueue,
    private val credentialStore: ApprovalResumeCredentialStore,
    private val resumeControlPlane: ApprovalResumeControlPlane,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovedContinuationResumeWorker {

    override suspend fun runOnce(limit: Int): ApprovedContinuationResumeWorkerResult {
        val now = clock.instant()
        val leaseUntil = now.plusSeconds(120)
        val items = queue.claimApprovedPending(
            workerId = WORKER_ID,
            limit = limit,
            leaseUntil = leaseUntil,
        )

        if (items.isEmpty()) {
            return ApprovedContinuationResumeWorkerResult(
                scanned = 0,
                resumed = 0,
                skipped = 0,
                failed = 0,
            )
        }

        var resumed = 0
        var skipped = 0
        var failed = 0

        for (item in items) {
            val outcome = processItem(item)
            when (outcome) {
                Outcome.RESUMED -> resumed++
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> failed++
            }
        }

        return ApprovedContinuationResumeWorkerResult(
            scanned = items.size,
            resumed = resumed,
            skipped = skipped,
            failed = failed,
        )
    }

    private suspend fun processItem(item: ApprovedContinuationResumeWorkItem): Outcome {
        // 1. Load sealed credential
        val credential = try {
            credentialStore.get(item.approvalId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            queue.markResumeFailed(
                item.approvalId,
                WORKER_ID,
                "credential-store-error:${e::class.simpleName}",
                retryAt = clock.instant().plusSeconds(30),
            )
            return Outcome.FAILED
        }

        if (credential == null) {
            queue.markResumeFailed(
                item.approvalId,
                WORKER_ID,
                "credential-not-found",
                retryAt = null,
            )
            return Outcome.SKIPPED
        }

        // 2. Reveal the token and resume
        val resumeToken = credential.resumeToken.revealForInternalResume()
        val command = ApprovalResumeCommand(
            approvalId = item.approvalId,
            resumeToken = resumeToken,
            resumedBy = WORKER_ID,
            expectedApprovalVersion = item.approvalVersion,
            expectedContinuationVersion = item.continuationVersion,
        )

        val result = try {
            resumeControlPlane.resume(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            queue.markResumeFailed(
                item.approvalId,
                WORKER_ID,
                "resume-error:${e::class.simpleName}",
                retryAt = clock.instant().plusSeconds(30),
            )
            return Outcome.FAILED
        }

        return when (result) {
            is ApprovalResumeResult.Resumed -> {
                queue.markResumeSucceeded(item.approvalId, WORKER_ID)
                credentialStore.delete(item.approvalId)
                Outcome.RESUMED
            }

            is ApprovalResumeResult.AlreadyCompleted -> {
                queue.markResumeSucceeded(item.approvalId, WORKER_ID)
                credentialStore.delete(item.approvalId)
                Outcome.RESUMED
            }

            is ApprovalResumeResult.NotFound,
            is ApprovalResumeResult.NotApproved -> {
                queue.markResumeFailed(
                    item.approvalId,
                    WORKER_ID,
                    "resume-${result::class.simpleName}",
                    retryAt = null,
                )
                Outcome.SKIPPED
            }

            is ApprovalResumeResult.Conflict -> {
                if (result.reason == "approval-continuation-expired") {
                    queue.markResumeFailed(
                        item.approvalId,
                        WORKER_ID,
                        "continuation-expired",
                        retryAt = null,
                    )
                    credentialStore.delete(item.approvalId)
                    Outcome.SKIPPED
                } else {
                    queue.markResumeFailed(
                        item.approvalId,
                        WORKER_ID,
                        "resume-conflict:${result.reason}",
                        retryAt = clock.instant().plusSeconds(60),
                    )
                    Outcome.FAILED
                }
            }

            is ApprovalResumeResult.Failed -> {
                queue.markResumeFailed(
                    item.approvalId,
                    WORKER_ID,
                    "resume-failed:${result.reason}",
                    retryAt = clock.instant().plusSeconds(30),
                )
                Outcome.FAILED
            }
        }
    }

    private enum class Outcome { RESUMED, SKIPPED, FAILED }

    companion object {
        const val WORKER_ID: String = "tramai-sovereign-approved-resume-worker"
    }
}
