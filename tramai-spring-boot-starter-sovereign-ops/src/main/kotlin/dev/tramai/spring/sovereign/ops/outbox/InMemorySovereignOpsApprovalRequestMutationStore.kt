package dev.tramai.spring.sovereign.ops.outbox

import dev.tramai.core.approval.ApprovalContinuationStore
import dev.tramai.core.approval.ApprovalRequest
import dev.tramai.core.approval.ApprovalStore
import dev.tramai.engine.SuspendedInvocationStore
import dev.tramai.engine.approval.ApprovalGatewayPersistenceRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * In-memory implementation of [SovereignOpsApprovalRequestMutationStore].
 *
 * This implementation serializes create attempts per approval ID. Unlike the
 * JDBC implementation it cannot provide a true rollback boundary because the
 * underlying Preview store interfaces do not expose delete operations.
 */
class InMemorySovereignOpsApprovalRequestMutationStore(
    private val approvalStore: ApprovalStore,
    private val suspendedInvocationStore: SuspendedInvocationStore,
    private val continuationStore: ApprovalContinuationStore,
    private val outboxStore: SovereignOpsAuditOutboxStore? = null,
) : SovereignOpsApprovalRequestMutationStore {

    private val approvalLocks = ConcurrentHashMap<String, ReentrantLock>()

    override suspend fun createApprovalRequest(
        request: ApprovalGatewayPersistenceRequest,
        auditIntent: SovereignOpsAuditOutboxRecord?,
    ): SovereignOpsApprovalRequestMutationResult {
        val approvalId = request.approvalRequest.approvalId
        val lock = approvalLocks.computeIfAbsent(approvalId) { ReentrantLock() }
        lock.lock()
        try {
            val existing = approvalStore.get(approvalId)
            if (existing != null) {
                return SovereignOpsApprovalRequestMutationResult.Existing(existing)
            }

            approvalStore.create(request.approvalRequest)
            suspendedInvocationStore.create(
                metadata = request.suspendedInvocationMetadata,
                replayEnvelope = request.replayEnvelope,
            )
            continuationStore.create(
                continuation = request.continuation,
                arguments = request.sensitiveArguments,
            )

            if (auditIntent != null) {
                val durableOutboxStore = requireNotNull(outboxStore) {
                    "tramai-sovereign-ops-approval-request-mutation-missing-outbox-store"
                }
                durableOutboxStore.append(auditIntent)
                durableOutboxStore.markReadyForDispatch(
                    outboxId = auditIntent.outboxId,
                    expectedStatus = SovereignOpsAuditOutboxStatus.PREPARED,
                )
            }

            return request.toCreatedResult()
        } catch (e: Exception) {
            val current = approvalStore.get(approvalId)
            if (current != null) {
                return SovereignOpsApprovalRequestMutationResult.Existing(current)
            }
            throw e
        } finally {
            lock.unlock()
        }
    }

    private fun ApprovalGatewayPersistenceRequest.toCreatedResult():
        SovereignOpsApprovalRequestMutationResult.Created =
        SovereignOpsApprovalRequestMutationResult.Created(
            approvalId = approvalRequest.approvalId,
            correlationId = suspendedInvocationMetadata.correlationId,
            resumeToken = resumeToken,
        )
}
