package dev.tramai.security.audit

import java.time.Clock
import java.util.UUID

class AuditEngine(
    private val store: AuditStore,
    private val clock: Clock = Clock.systemUTC(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun emit(emission: AuditEmission): AuditEvent {
        return store.appendNext(emission.auditStreamId) { latest ->
            val event = AuditEvent(
                schemaVersion = CURRENT_AUDIT_SCHEMA_VERSION,
                hashAlgorithm = AuditHashAlgorithm.SHA_256,
                auditStreamId = emission.auditStreamId,
                eventId = idGenerator(),
                sequenceNumber = (latest?.sequenceNumber ?: 0L) + 1L,
                workflowRunId = emission.workflowRunId,
                correlationId = emission.correlationId,
                actor = emission.actor,
                enforcementPoint = emission.enforcementPoint,
                decision = emission.decision,
                policyVersion = emission.policyVersion,
                workflowDigest = emission.workflowDigest,
                previousEventHash = latest?.eventHash,
                eventHash = "",
                timestamp = clock.instant(),
                reasonCode = emission.reasonCode,
                metadata = emission.metadata.toMap(),
            )
            event.copy(eventHash = event.copy(eventHash = "").calculateHash())
        }
    }
}

data class AuditEmission(
    val auditStreamId: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val actor: String?,
    val enforcementPoint: String,
    val decision: String,
    val policyVersion: String?,
    val workflowDigest: String?,
    val reasonCode: String?,
    val metadata: Map<String, String>,
)
