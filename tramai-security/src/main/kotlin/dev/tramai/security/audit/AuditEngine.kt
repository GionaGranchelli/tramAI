package dev.tramai.security.audit

import java.time.Clock
import java.util.UUID

class AuditEngine(
    private val store: AuditStore,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun emit(
        auditStreamId: String,
        workflowRunId: String?,
        correlationId: String?,
        actor: String?,
        enforcementPoint: String,
        decision: String,
        policyVersion: String?,
        workflowDigest: String?,
        reasonCode: String?,
        metadata: Map<String, String>,
    ): AuditEvent {
        return store.appendNext(auditStreamId) { latest ->
            val event = AuditEvent(
                schemaVersion = CURRENT_AUDIT_SCHEMA_VERSION,
                hashAlgorithm = AuditHashAlgorithm.SHA_256,
                auditStreamId = auditStreamId,
                eventId = idGenerator(),
                sequenceNumber = (latest?.sequenceNumber ?: 0L) + 1L,
                workflowRunId = workflowRunId,
                correlationId = correlationId,
                actor = actor,
                enforcementPoint = enforcementPoint,
                decision = decision,
                policyVersion = policyVersion,
                workflowDigest = workflowDigest,
                previousEventHash = latest?.eventHash,
                eventHash = "",
                timestamp = clock.instant(),
                reasonCode = reasonCode,
                metadata = metadata.toMap(),
            )
            event.copy(eventHash = event.copy(eventHash = "").calculateHash())
        }
    }
}
