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
            // Pass the latest event's snapshot (unmodifiable view) to the factory,
            // then IMMEDIATELY snapshot the result — the snapshot IS the defensive copy.
            val rawEvent = eventFactory(latest?.snapshot())
            val snapshot = rawEvent.snapshot()

            // auditStreamId must match
            require(snapshot.auditStreamId == auditStreamId) {
                "event auditStreamId '${snapshot.auditStreamId}' does not match expected '$auditStreamId'"
            }

            // sequence must be exactly 1 more than latest
            val expectedSequence = (latest?.sequenceNumber ?: 0L) + 1L
            require(snapshot.sequenceNumber == expectedSequence) {
                "Expected sequenceNumber $expectedSequence for stream '$auditStreamId' but got ${snapshot.sequenceNumber}"
            }

            // previousEventHash must match latest eventHash
            require(snapshot.previousEventHash == latest?.eventHash) {
                "previousEventHash does not match latest eventHash"
            }

            // eventHash must equal calculated hash
            require(snapshot.eventHash == snapshot.copy(eventHash = "").calculateHash()) {
                "eventHash does not match calculated hash"
            }

            // schemaVersion must be current
            require(snapshot.schemaVersion == CURRENT_AUDIT_SCHEMA_VERSION) {
                "Unsupported audit schema version ${snapshot.schemaVersion}"
            }

            // no duplicate eventId in stream
            require(state.events.none { it.eventId == snapshot.eventId }) {
                "Duplicate eventId '${snapshot.eventId}' in stream '$auditStreamId'"
            }

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
