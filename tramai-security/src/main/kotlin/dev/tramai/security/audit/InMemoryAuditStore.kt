package dev.tramai.security.audit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

class InMemoryAuditStore : AuditStore {

    data class StreamState(
        val lock: Mutex = Mutex(),
        val events: MutableList<AuditEvent> = mutableListOf(),
    )

    private val streams: ConcurrentHashMap<String, StreamState> = ConcurrentHashMap()

    private fun AuditEvent.snapshot(): AuditEvent =
        copy(metadata = Collections.unmodifiableMap(LinkedHashMap(metadata)))

    override suspend fun appendNext(
        auditStreamId: String,
        eventFactory: (latest: AuditEvent?) -> AuditEvent,
    ): AuditEvent {
        require(auditStreamId.isNotBlank()) { "audit-store-invalid-stream-id" }
        val state = streams.computeIfAbsent(auditStreamId) { StreamState() }
        return state.lock.withLock {
            val latest = state.events.lastOrNull()
            // Pass the latest event's snapshot (unmodifiable view) to the factory,
            // then IMMEDIATELY snapshot the result — the snapshot IS the defensive copy.
            val rawEvent = eventFactory(latest?.snapshot())
            val snapshot = rawEvent.snapshot()

            require(snapshot.eventId.isNotBlank()) { "audit-store-invalid-event-id" }

            // auditStreamId must match
            require(snapshot.auditStreamId == auditStreamId) {
                "audit-stream-id-mismatch"
            }

            // sequence must be exactly 1 more than latest
            val expectedSequence = (latest?.sequenceNumber ?: 0L) + 1L
            require(snapshot.sequenceNumber == expectedSequence) {
                "audit-sequence-gap"
            }

            // previousEventHash must match latest eventHash
            require(snapshot.previousEventHash == latest?.eventHash) {
                "audit-hash-chain-broken"
            }

            // eventHash must equal calculated hash
            require(snapshot.eventHash == snapshot.copy(eventHash = "").calculateHash()) {
                "audit-event-hash-mismatch"
            }

            // schemaVersion must be current
            require(snapshot.schemaVersion == CURRENT_AUDIT_SCHEMA_VERSION) {
                "audit-schema-version-unsupported"
            }

            // no duplicate eventId in stream
            require(state.events.none { it.eventId == snapshot.eventId }) {
                "audit-duplicate-event-id"
            }

            state.events.add(snapshot)
            snapshot
        }
    }

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> {
        require(auditStreamId.isNotBlank()) { "audit-store-invalid-stream-id" }
        val state = streams[auditStreamId] ?: return emptyList()
        return state.lock.withLock {
            state.events.map { it.snapshot() }
        }
    }

    override suspend fun readStreamPage(
        auditStreamId: String,
        afterSequenceNumber: Long?,
        limit: Int,
    ): List<AuditEvent> {
        require(auditStreamId.isNotBlank()) { "audit-store-invalid-stream-id" }
        require(limit > 0) { "audit-store-invalid-limit" }
        require(afterSequenceNumber == null || afterSequenceNumber >= 0) {
            "audit-store-invalid-cursor"
        }
        val state = streams[auditStreamId] ?: return emptyList()
        return state.lock.withLock {
            state.events.asSequence()
                .filter { event ->
                    afterSequenceNumber == null || event.sequenceNumber > afterSequenceNumber
                }
                .take(limit)
                .map { it.snapshot() }
                .toList()
        }
    }

    override suspend fun latestEvent(auditStreamId: String): AuditEvent? {
        require(auditStreamId.isNotBlank()) { "audit-store-invalid-stream-id" }
        val state = streams[auditStreamId] ?: return null
        return state.lock.withLock {
            state.events.lastOrNull()?.snapshot()
        }
    }
}
