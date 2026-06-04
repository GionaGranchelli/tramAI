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
        val state = streams.computeIfAbsent(auditStreamId) { StreamState() }
        return state.lock.withLock {
            val latest = state.events.lastOrNull()
            val newEvent = eventFactory(latest)

            // auditStreamId must match
            require(newEvent.auditStreamId == auditStreamId) {
                "event auditStreamId '${newEvent.auditStreamId}' does not match expected '$auditStreamId'"
            }

            // sequence must be exactly 1 more than latest
            val expectedSequence = (latest?.sequenceNumber ?: 0L) + 1L
            require(newEvent.sequenceNumber == expectedSequence) {
                "Expected sequenceNumber $expectedSequence for stream '$auditStreamId' but got ${newEvent.sequenceNumber}"
            }

            // previousEventHash must match latest eventHash
            require(newEvent.previousEventHash == latest?.eventHash) {
                "previousEventHash does not match latest eventHash"
            }

            // eventHash must equal calculated hash
            val calculatedHash = newEvent.copy(eventHash = "").calculateHash()
            require(newEvent.eventHash == calculatedHash) {
                "eventHash does not match calculated hash"
            }

            // schemaVersion must be current
            require(newEvent.schemaVersion == CURRENT_AUDIT_SCHEMA_VERSION) {
                "Unsupported audit schema version ${newEvent.schemaVersion}"
            }

            // no duplicate eventId in stream
            require(state.events.none { it.eventId == newEvent.eventId }) {
                "Duplicate eventId '${newEvent.eventId}' in stream '$auditStreamId'"
            }

            // defensive copy of metadata
            val defensiveCopy = newEvent.copy(metadata = newEvent.metadata.toMap())
            val snapshot = defensiveCopy.snapshot()
            state.events.add(snapshot)
            snapshot
        }
    }

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> {
        val state = streams[auditStreamId] ?: return emptyList()
        return state.lock.withLock {
            state.events.map { it.snapshot() }
        }
    }

    override suspend fun latestEvent(auditStreamId: String): AuditEvent? {
        val state = streams[auditStreamId] ?: return null
        return state.lock.withLock {
            state.events.lastOrNull()?.snapshot()
        }
    }
}
