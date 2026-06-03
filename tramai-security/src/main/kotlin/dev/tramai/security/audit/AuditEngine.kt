package dev.tramai.security.audit

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class AuditEngine(private val store: AuditStore) {

    private val streamLocks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()

    suspend fun emit(
        auditStreamId: String,
        eventId: String,
        workflowRunId: String?,
        correlationId: String?,
        actor: String?,
        enforcementPoint: String,
        decision: String,
        policyVersion: String?,
        workflowDigest: String?,
        reasonCode: String?,
        metadata: Map<String, String>,
        timestamp: String,
    ): AuditEvent {
        val lock = streamLocks.computeIfAbsent(auditStreamId) { Mutex() }
        return lock.withLock {
            val latestEvent = store.latestEvent(auditStreamId)
            val event = AuditEvent(
                schemaVersion = 1,
                hashAlgorithm = "SHA-256",
                auditStreamId = auditStreamId,
                eventId = eventId,
                sequenceNumber = (latestEvent?.sequenceNumber ?: 0L) + 1L,
                workflowRunId = workflowRunId,
                correlationId = correlationId,
                actor = actor,
                enforcementPoint = enforcementPoint,
                decision = decision,
                policyVersion = policyVersion,
                workflowDigest = workflowDigest,
                previousEventHash = latestEvent?.eventHash,
                eventHash = "",
                timestamp = timestamp,
                reasonCode = reasonCode,
                metadata = metadata.toMap(),
            )
            val persistedEvent = event.copy(eventHash = event.calculateHash())
            store.append(auditStreamId, persistedEvent)
            persistedEvent
        }
    }
}
