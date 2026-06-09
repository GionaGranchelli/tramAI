package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalRecoveryCoordinator
import dev.tramai.core.approval.ForceCancelClaimedCommand
import dev.tramai.core.approval.SAFE_REASON_CODE_PATTERN
import dev.tramai.core.exception.ApprovalAuthorizationException
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import dev.tramai.core.exception.ApprovalRecoveryAuditUnavailableException
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * In-memory implementation of [ApprovalRecoveryCoordinator].
 *
 * Wraps an [ApprovalContinuationStore] with audit emission and
 * safe exception mapping.
 *
 * ## Lifecycle
 *
 * `PENDING -> CLAIMED -> COMPLETED`
 * `PENDING -> EXPIRED`
 * `PENDING -> CANCELLED`
 * `CLAIMED -> CANCELLED_UNCERTAIN` (via [forceCancelClaimed])
 *
 * ## Two-phase recovery audit
 *
 * **Requested event** (before mutation):
 * This is the synchronous pre-mutation audit gate.
 * If emission fails, the mutation MUST NOT proceed.
 * Database-backed implementations should use a transactional outbox
 * when atomic transition-and-audit persistence is required.
 *
 * **Cancelled event** (after mutation):
 * Best-effort notification. Failure here does not roll back
 * a successful store transition.
 *
 * ## Fresh-claim semantics
 *
 * - [findStaleClaims] is the normal operator discovery path.
 * - [forceCancelClaimed] may resolve any explicitly selected CLAIMED
 *   continuation. It does not require the continuation to be "stale".
 * - Reconciliation remains mandatory because the external side effect
 *   may already have occurred before the force cancellation.
 * - Automatic reclaim and retry remain forbidden.
 * - The CANCELLED_UNCERTAIN status is terminal.
 */
class InMemoryApprovalRecoveryCoordinator(
    private val store: ApprovalContinuationStore,
    private val lifecycleAuditEmitter: ApprovalLifecycleAuditEmitter,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalRecoveryCoordinator {

    private companion object {
        private val SAFE_REASON_CODE = Regex(SAFE_REASON_CODE_PATTERN)
    }

    override suspend fun findStaleClaims(
        claimedBefore: Instant,
        limit: Int,
    ): List<ApprovalContinuation> {
        val stale = store.findStaleClaimed(claimedBefore, limit)

        // Emit detection audit events — best-effort, does not fail the operation
        stale.forEach { cont ->
            try {
                val claimedAt = cont.claimedAt ?: return@forEach
                lifecycleAuditEmitter.onStaleClaimDetected(
                    approvalId = cont.approvalId,
                    workflowRunId = cont.workflowRunId,
                    toolName = cont.toolName,
                    claimedAt = claimedAt,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Operational reporting is best-effort for stale detection.
            }
        }

        return stale
    }

    override suspend fun forceCancelClaimed(
        command: ForceCancelClaimedCommand,
    ): ApprovalContinuation {
        require(SAFE_REASON_CODE.matches(command.reasonCode)) {
            "reasonCode must match [a-z0-9][a-z0-9._:-]{0,63}"
        }

        val existing = try {
            store.get(command.approvalId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: ApprovalContinuationNotFoundException) {
            throw e
        } catch (e: RuntimeException) {
            throw ApprovalAuthorizationException(command.approvalId)
        } ?: throw ApprovalContinuationNotFoundException(command.approvalId)
        try {
            lifecycleAuditEmitter.onClaimedContinuationForceCancellationRequested(
                approvalId = command.approvalId,
                workflowRunId = existing.workflowRunId,
                toolName = existing.toolName,
                cancelledBy = command.operatorId,
                reasonCode = command.reasonCode,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApprovalRecoveryAuditUnavailableException(e)
        }

        val continuation = try {
            store.forceCancelClaimed(
                approvalId = command.approvalId,
                expectedVersion = command.expectedVersion,
                cancelledBy = command.operatorId,
                reasonCode = command.reasonCode,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            throw mapStoreError(e, command.approvalId)
        }

        try {
            lifecycleAuditEmitter.onClaimedContinuationForceCancelled(
                approvalId = command.approvalId,
                workflowRunId = continuation.workflowRunId,
                toolName = continuation.toolName,
                cancelledBy = command.operatorId,
                reasonCode = command.reasonCode,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Requested event is the durable audit boundary; completion notification is best-effort.
        }

        return continuation
    }

    private fun mapStoreError(e: RuntimeException, approvalId: String): RuntimeException = when (e) {
        is ApprovalContinuationNotFoundException -> ApprovalContinuationNotFoundException(approvalId)
        is ApprovalContinuationConflictException -> ApprovalAuthorizationException(approvalId)
        is ApprovalContinuationNotClaimableException -> ApprovalAuthorizationException(approvalId)
        else -> ApprovalAuthorizationException(approvalId)
    }
}
