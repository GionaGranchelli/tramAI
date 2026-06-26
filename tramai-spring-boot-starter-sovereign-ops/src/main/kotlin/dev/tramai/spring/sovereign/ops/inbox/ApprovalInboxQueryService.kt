package dev.tramai.spring.sovereign.ops.inbox

import dev.tramai.core.approval.gateway.ApprovalId

/**
 * Preview query SPI for the approval inbox / reviewer work queue.
 *
 * This is a read-side service that provides safe projections over durable
 * approval records. It never exposes resume tokens, token digests, raw tool
 * arguments, replay envelopes, or reviewer comments.
 */
interface ApprovalInboxQueryService {
    suspend fun search(query: ApprovalInboxQuery): ApprovalInboxPage
    suspend fun getWorkItem(approvalId: ApprovalId): ApprovalInboxWorkItem?
}
