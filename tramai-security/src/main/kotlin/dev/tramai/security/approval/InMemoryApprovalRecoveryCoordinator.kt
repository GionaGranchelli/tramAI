package dev.tramai.security.approval

import dev.tramai.core.approval.ApprovalContinuation
import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalLifecycleAuditEmitter
import dev.tramai.core.approval.ApprovalRecoveryCoordinator
import dev.tramai.core.approval.ForceCancelClaimedCommand
import dev.tramai.core.approval.NoOpApprovalLifecycleAuditEmitter
import dev.tramai.core.exception.ApprovalAuthorizationException
import dev.tramai.core.exception.ApprovalContinuationConflictException
import dev.tramai.core.exception.ApprovalContinuationNotFoundException
import dev.tramai.core.exception.ApprovalContinuationNotClaimableException
import java.time.Clock
import java.time.Instant

/**
 * In-memory implementation of [ApprovalRecoveryCoordinator].
 *
 * Wraps an [ApprovalContinuationStore] with audit emission and
 * safe exception mapping.
 */
class InMemoryApprovalRecoveryCoordinator(
    private val store: ApprovalContinuationStore,
    private val lifecycleAuditEmitter: ApprovalLifecycleAuditEmitter = NoOpApprovalLifecycleAuditEmitter,
    private val clock: Clock = Clock.systemUTC(),
) : ApprovalRecoveryCoordinator {

    private val SAFE_REASON_CODE = Regex("[a-z0-9][a-z0-9._:-]{0,63}")

    override suspend fun findStaleClaims(
        claimedBefore: Instant,
        limit: Int,
    ): List<ApprovalContinuation> {
        val stale = store.findStaleClaimed(claimedBefore, limit)

        // Emit detection audit events — best-effort, does not fail the operation
        stale.forEach { cont ->
            runCatching {
                lifecycleAuditEmitter.onStaleClaimDetected(
                    approvalId = cont.approvalId,
                    workflowRunId = cont.workflowRunId,
                    toolName = cont.toolName,
                    claimedAt = cont.claimedAt ?: clock.instant(),
                )
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

        val continuation = try {
            store.forceCancelClaimed(
                approvalId = command.approvalId,
                expectedVersion = command.expectedVersion,
                cancelledBy = command.operatorId,
                reasonCode = command.reasonCode,
            )
        } catch (e: RuntimeException) {
            throw mapStoreError(e, command.approvalId)
        }

        // Emit audit event — fail closed on audit failure for privileged mutation
        lifecycleAuditEmitter.onClaimedContinuationForceCancelled(
            approvalId = command.approvalId,
            workflowRunId = continuation.workflowRunId,
            toolName = continuation.toolName,
            cancelledBy = command.operatorId,
            reasonCode = command.reasonCode,
        )

        return continuation
    }

    private fun mapStoreError(e: RuntimeException, approvalId: String): RuntimeException = when (e) {
        is ApprovalContinuationNotFoundException -> ApprovalContinuationNotFoundException(approvalId)
        is ApprovalContinuationConflictException -> ApprovalAuthorizationException(approvalId)
        is ApprovalContinuationNotClaimableException -> ApprovalAuthorizationException(approvalId)
        else -> ApprovalAuthorizationException(approvalId)
    }
}
