package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [SovereignOpsAuditOutboxStore].
 *
 * Records are stored in a [ConcurrentHashMap] keyed by [SovereignOpsAuditOutboxRecord.outboxId].
 * Claim operations use per-record optimistic toggling — only status [SovereignOpsAuditOutboxStatus.PENDING]
 * records can be claimed.
 */
class InMemorySovereignOpsAuditOutboxStore : SovereignOpsAuditOutboxStore {

    private val store = ConcurrentHashMap<String, SovereignOpsAuditOutboxRecord>()

    override suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord {
        val existing = store.putIfAbsent(record.outboxId, record)
        require(existing == null) { "tramai-sovereign-ops-outbox-duplicate-id" }
        return record
    }

    override suspend fun claimPending(
        claimedBy: String,
        limit: Int,
        now: Instant,
    ): List<SovereignOpsAuditOutboxRecord> {
        val claimed = mutableListOf<SovereignOpsAuditOutboxRecord>()
        for ((id, record) in store) {
            if (claimed.size >= limit) break
            if (record.status != SovereignOpsAuditOutboxStatus.PENDING) continue
            val updated = record.copy(
                status = SovereignOpsAuditOutboxStatus.EMITTING,
                attemptCount = record.attemptCount + 1,
            )
            if (store.replace(id, record, updated)) {
                claimed.add(updated)
            }
        }
        return claimed
    }

    override suspend fun markEmitted(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord {
        val record = store[outboxId]
            ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")
        require(record.status == expectedStatus) {
            "tramai-sovereign-ops-outbox-status-mismatch"
        }
        val updated = record.copy(
            status = SovereignOpsAuditOutboxStatus.EMITTED,
            emittedAt = emittedAt,
        )
        store[outboxId] = updated
        return updated
    }

    override suspend fun markFailed(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        errorCode: String,
        retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord {
        val record = store[outboxId]
            ?: throw IllegalStateException("tramai-sovereign-ops-outbox-not-found")
        require(record.status == expectedStatus) {
            "tramai-sovereign-ops-outbox-status-mismatch"
        }
        val newStatus = if (retryable) {
            SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE
        } else {
            SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
        }
        val updated = record.copy(
            status = newStatus,
            lastErrorCode = errorCode,
        )
        store[outboxId] = updated
        return updated
    }

    override suspend fun get(outboxId: String): SovereignOpsAuditOutboxRecord? =
        store[outboxId]

    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> =
        store.values
            .filter { it.status == SovereignOpsAuditOutboxStatus.PENDING }
            .take(limit)
}
