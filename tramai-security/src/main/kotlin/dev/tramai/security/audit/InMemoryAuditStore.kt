package dev.tramai.security.audit

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryAuditStore : AuditStore {

    private val streams: ConcurrentHashMap<String, MutableList<AuditEvent>> = ConcurrentHashMap()
    private val sequenceCounters: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()

    override suspend fun append(auditStreamId: String, event: AuditEvent) {
        synchronized(auditStreamId.intern()) {
            val counter = sequenceCounters.computeIfAbsent(auditStreamId) { AtomicLong(0L) }
            val expectedSequenceNumber = counter.get() + 1L
            require(event.sequenceNumber == expectedSequenceNumber) {
                "Expected sequenceNumber $expectedSequenceNumber for stream '$auditStreamId' but got ${event.sequenceNumber}"
            }

            val events = streams.computeIfAbsent(auditStreamId) { mutableListOf() }
            events.add(event)
            counter.incrementAndGet()
        }
    }

    override suspend fun readStream(auditStreamId: String): List<AuditEvent> {
        val events = streams[auditStreamId] ?: return emptyList()
        return synchronized(auditStreamId.intern()) {
            events.toList()
        }
    }

    override suspend fun latestEvent(auditStreamId: String): AuditEvent? {
        val events = streams[auditStreamId] ?: return null
        return synchronized(auditStreamId.intern()) {
            events.lastOrNull()
        }
    }

    override suspend fun clear() {
        streams.clear()
        sequenceCounters.clear()
    }
}
