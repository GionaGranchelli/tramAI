package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory implementation of [SovereignOpsAuditOutboxStore].
 *
 * Records are stored in a [ConcurrentHashMap] keyed by [SovereignOpsAuditOutboxRecord.outboxId].
 * A secondary index enforces [SovereignOpsAuditOutboxRecord.eventKey] uniqueness.
 */
class InMemorySovereignOpsAuditOutboxStore : SovereignOpsAuditOutboxStore {

    private val store = ConcurrentHashMap<String, SovereignOpsAuditOutboxRecord>()
    private val eventKeyIndex = ConcurrentHashMap<String, String>()

    override fun isDurable(): Boolean = false

    override suspend fun append(record: SovereignOpsAuditOutboxRecord): SovereignOpsAuditOutboxRecord {
        require(record.outboxId.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-id" }
        require(record.eventKey.isNotBlank()) { "tramai-sovereign-ops-outbox-invalid-event-key" }
        require(record.status == SovereignOpsAuditOutboxStatus.PREPARED) {
            "tramai-sovereign-ops-outbox-invalid-status: only PREPARED records can be appended"
        }
        val existing = store.putIfAbsent(record.outboxId, record)
        require(existing == null) { "tramai-sovereign-ops-outbox-duplicate-id: ${record.outboxId}" }
        val previousKey = eventKeyIndex.putIfAbsent(record.eventKey, record.outboxId)
        if (previousKey != null) {
            store.remove(record.outboxId)
            require(false) { "tramai-sovereign-ops-outbox-duplicate-event-key: ${record.eventKey}" }
        }
        return record
    }

    override suspend fun markReadyForDispatch(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
    ): SovereignOpsAuditOutboxRecord {
        val record = store[outboxId]
            ?: throw IllegalStateException(ERROR_OUTBOX_NOT_FOUND)
        require(record.status == expectedStatus) {
            ERROR_OUTBOX_STATUS_MISMATCH
        }
        val updated = record.copy(status = SovereignOpsAuditOutboxStatus.PENDING)
        require(store.replace(outboxId, record, updated)) {
            ERROR_OUTBOX_CONCURRENT_UPDATE
        }
        return updated
    }

    override suspend fun claimPending(
        claimedBy: String,
        limit: Int,
        now: Instant,
    ): List<SovereignOpsAuditOutboxRecord> {
        val claimed = mutableListOf<SovereignOpsAuditOutboxRecord>()
        for ((id, record) in store) {
            if (claimed.size >= limit) break
            if (!record.isClaimable(now)) continue

            val updated = record.claimFor(claimedBy, now)
            if (store.replace(id, record, updated)) {
                claimed.add(updated)
            }
        }
        return claimed
    }

    private fun SovereignOpsAuditOutboxRecord.isClaimable(now: Instant): Boolean =
        when (status) {
            SovereignOpsAuditOutboxStatus.PENDING,
            SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE,
            -> true
            SovereignOpsAuditOutboxStatus.EMITTING -> claimExpiresAt?.isBefore(now) == true
            SovereignOpsAuditOutboxStatus.PREPARED,
            SovereignOpsAuditOutboxStatus.EMITTED,
            SovereignOpsAuditOutboxStatus.FAILED_PERMANENT,
            -> false
        }

    private fun SovereignOpsAuditOutboxRecord.claimFor(
        claimedBy: String,
        now: Instant,
    ): SovereignOpsAuditOutboxRecord =
        copy(
            status = SovereignOpsAuditOutboxStatus.EMITTING,
            attemptCount = attemptCount + 1,
            claimedBy = claimedBy,
            claimedAt = now,
            claimExpiresAt = now.plus(SovereignOpsAuditOutboxRecord.DEFAULT_CLAIM_EXPIRY),
        )

    override suspend fun markEmitted(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        emittedAt: Instant,
    ): SovereignOpsAuditOutboxRecord {
        val record = store[outboxId]
            ?: throw IllegalStateException(ERROR_OUTBOX_NOT_FOUND)
        require(record.status == expectedStatus) {
            ERROR_OUTBOX_STATUS_MISMATCH
        }
        val updated = record.copy(
            status = SovereignOpsAuditOutboxStatus.EMITTED,
            emittedAt = emittedAt,
        )
        require(store.replace(outboxId, record, updated)) {
            ERROR_OUTBOX_CONCURRENT_UPDATE
        }
        return updated
    }

    override suspend fun markFailed(
        outboxId: String,
        expectedStatus: SovereignOpsAuditOutboxStatus,
        errorCode: String,
        retryable: Boolean,
    ): SovereignOpsAuditOutboxRecord {
        val record = store[outboxId]
            ?: throw IllegalStateException(ERROR_OUTBOX_NOT_FOUND)
        require(record.status == expectedStatus) {
            ERROR_OUTBOX_STATUS_MISMATCH
        }
        val newStatus = if (retryable) SovereignOpsAuditOutboxStatus.FAILED_RETRYABLE
        else SovereignOpsAuditOutboxStatus.FAILED_PERMANENT
        val updated = record.copy(
            status = newStatus,
            lastErrorCode = errorCode,
        )
        require(store.replace(outboxId, record, updated)) {
            ERROR_OUTBOX_CONCURRENT_UPDATE
        }
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

    override suspend fun listByStatus(
        status: SovereignOpsAuditOutboxStatus,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> =
        store.values
            .filter { it.status == status }
            .take(limit)

    override suspend fun listExpiredEmitting(
        now: Instant,
        limit: Int,
    ): List<SovereignOpsAuditOutboxRecord> =
        store.values
            .filter { it.status == SovereignOpsAuditOutboxStatus.EMITTING }
            .filter { it.claimExpiresAt != null && it.claimExpiresAt.isBefore(now) }
            .take(limit)
}

/** @see InMemorySovereignOpsAuditOutboxStore */
private const val ERROR_OUTBOX_NOT_FOUND = "tramai-sovereign-ops-outbox-not-found"

/** @see InMemorySovereignOpsAuditOutboxStore */
private const val ERROR_OUTBOX_STATUS_MISMATCH = "tramai-sovereign-ops-outbox-status-mismatch"

/** @see InMemorySovereignOpsAuditOutboxStore */
private const val ERROR_OUTBOX_CONCURRENT_UPDATE = "tramai-sovereign-ops-outbox-concurrent-update"
