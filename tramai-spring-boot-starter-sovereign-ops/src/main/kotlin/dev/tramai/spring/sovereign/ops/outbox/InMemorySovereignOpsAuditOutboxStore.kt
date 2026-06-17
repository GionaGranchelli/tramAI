package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [SovereignOpsAuditOutboxStore].
 *
 * Records are stored in a [ConcurrentHashMap] keyed by [SovereignOpsAuditOutboxRecord.outboxId].
 * A secondary index enforces [SovereignOpsAuditOutboxRecord.eventKey] uniqueness.
 *
 * ## Dispatchable statuses
 * Claiming considers:
 * - [SovereignOpsAuditOutboxStatus.PENDING]: eligible for first dispatch
 * - [SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE]: eligible for retry
 * - [SovereignOpsAuditOutboxStatus.EMITTING] with expired claim: eligible for recovery
 *
 * ## Durability
 * Returns `isDurable() = false`. This store does not survive process restarts.
 * Applications requiring durable audit outbox persistence must provide their own
 * implementation of [SovereignOpsAuditOutboxStore].
 */
class InMemorySovereignOpsAuditOutboxStore : SovereignOpsAuditOutboxStore {

    private val store = ConcurrentHashMap<String, SovereignOpsAuditOutboxRecord>()
    private val eventKeyIndex = ConcurrentHashMap<String, String>()

    override fun isDurable(): Boolean = false

    override suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord {
        require(record.outboxId.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-id" }
        require(record.eventKey.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-event-key" }

        // Enforce outboxId uniqueness
        val existing = store.putIfAbsent(record.outboxId, record)
        require(existing == null) { "tramai-sovereign-ops-outbox-duplicate-id: ${record.outboxId}" }

        // Enforce eventKey uniqueness
        val previousKey = eventKeyIndex.putIfAbsent(record.eventKey, record.outboxId)
        if (previousKey != null) {
            // Rollback the id-based insert
            store.remove(record.outboxId)
            require(false) { "tramai-sovereign-ops-outbox-duplicate-event-key: ${record.eventKey}" }
        }

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
            val updated = when (record.status) {
                SovereignOpsAuditOutboxStatus.PENDING ->
                    record.copy(
                        status = SovereignOpsAuditOutboxStatus.EMITTING,
                        attemptCount = record.attemptCount + 1,
                        claimedBy = claimedBy,
                        claimedAt = now,
                        claimExpiresAt = now.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY),
                    )
                SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE ->
                    record.copy(
                        status = SovereignOpsAuditOutboxStatus.EMITTING,
                        attemptCount = record.attemptCount + 1,
                        claimedBy = claimedBy,
                        claimedAt = now,
                        claimExpiresAt = now.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY),
                    )
                SovereignOpsAuditOutboxStatus.EMITTING -> {
                    val expiresAt = record.claimExpiresAt
                    if (expiresAt != null && expiresAt.isBefore(now)) {
                        record.copy(
                            status = SovereignOpsAuditOutboxStatus.EMITTING,
                            attemptCount = record.attemptCount + 1,
                            claimedBy = claimedBy,
                            claimedAt = now,
                            claimExpiresAt = now.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY),
                        )
                    } else {
                        null
                    }
                }
                else -> null
            }
            if (updated != null && store.replace(id, record, updated)) {
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

    override suspend fun findByEventKey(eventKey: String): SovereignOpsAuditOutboxRecord? {
        val id = eventKeyIndex[eventKey] ?: return null
        return store[id]
    }

    override suspend fun listPending(limit: Int): List<SovereignOpsAuditOutboxRecord> =
        store.values
            .filter { it.status == SovereignOpsAuditOutboxStatus.PENDING }
            .take(limit)
}
