package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalRecoveryCoordinator
import dev.tramai.core.approval.ForceCancelClaimedCommand
import dev.tramai.core.approval.SAFE_REASON_CODE_PATTERN
import dev.tramai.core.exception.ApprovalAuthorizationException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
import dev.tramai.core.exception.ApprovalRecoveryAuditUnavailableException
import dev.tramai.core.exception.ApprovalRecoveryUnavailableException
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
 * ## Exception boundary policy
 *
 * Only locally generated, demonstrably safe command-validation
 * [IllegalArgumentException] instances (e.g. [reasonCode] validation)
 * propagate unchanged. ALL dependency-originated exceptions, including
 * [IllegalArgumentException] from store or audit-emitter calls,
 * are sanitized into framework-level exceptions with fixed messages
 * and no cause chain. [CancellationException] always propagates
 * unchanged.
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
) : ApprovalRecoveryCoordinator {

    private companion object {
        private val SAFE_REASON_CODE = Regex(SAFE_REASON_CODE_PATTERN)
    }

    override suspend fun findStaleClaims(
        claimedBefore: Instant,
        limit: Int,
    ): List<ApprovalContinuation> {
        val stale = try {
            store.findStaleClaimed(claimedBefore, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApprovalRecoveryUnavailableException(e)
        }

        // Emit detection audit events — best-effort, does not fail the operation
        stale.forEach { cont ->
            try {
                val claimedAt = cont.claimedAt
                    ?: return@forEach // Silently skip corrupted records without claimedAt.
                    // A durable implementation should report this as an invariant violation
                    // through an operational observer.
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
        // Locally generated safe validation — propagates unchanged.
        require(SAFE_REASON_CODE.matches(command.reasonCode)) {
            "reasonCode must match [a-z0-9][a-z0-9._:-]{0,63}"
        }

        // Step 1: Pre-read — sanitize dependency exceptions.
        val existing = try {
            store.get(command.approvalId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApprovalContinuationNotFoundException) {
            throw ApprovalContinuationNotFoundException(command.approvalId)
        } catch (e: Exception) {
            throw ApprovalAuthorizationException(command.approvalId)
        } ?: throw ApprovalContinuationNotFoundException(command.approvalId)

        // Step 2: Pre-mutation audit — sanitize dependency exceptions.
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

        // Step 3: Store mutation — sanitize dependency exceptions.
        val continuation = try {
            store.forceCancelClaimed(
                approvalId = command.approvalId,
                expectedVersion = command.expectedVersion,
                cancelledBy = command.operatorId,
                reasonCode = command.reasonCode,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw mapStoreError(e, command.approvalId)
        }

        // Step 4: Post-mutation audit — best-effort, non-fatal.
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

    private fun mapStoreError(e: Exception, approvalId: String): RuntimeException = when (e) {
        is ApprovalContinuationNotFoundException -> ApprovalContinuationNotFoundException(approvalId)
        else -> ApprovalAuthorizationException(approvalId)
    }
}
