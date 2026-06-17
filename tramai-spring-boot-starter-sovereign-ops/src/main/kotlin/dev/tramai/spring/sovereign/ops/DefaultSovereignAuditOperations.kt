package dev.tramai.spring.sovereign.ops

import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore

/**
 * Default implementation of [SovereignAuditOperations].
 *
 * Delegates to an [AuditStore]. Returns only safe event summaries —
 * raw prompts, model responses, and sensitive payloads are never exposed.
 */
class DefaultSovereignAuditOperations(
    private val store: AuditStore?,
) : SovereignAuditOperations {

    private companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")
    }

    override suspend fun readAuditStream(
        auditStreamId: String,
    ): List<SovereignAuditEventSummary> {
        validateAuditStreamId(auditStreamId)
        if (store == null) {
            throw IllegalStateException("tramai-sovereign-ops-store-unavailable")
        }
        return store.readStream(auditStreamId).map { it.toSummary() }
    }

    override suspend fun latestAuditEvent(
        auditStreamId: String,
    ): SovereignAuditEventSummary? {
        validateAuditStreamId(auditStreamId)
        if (store == null) {
            throw IllegalStateException("tramai-sovereign-ops-store-unavailable")
        }
        return store.latestEvent(auditStreamId)?.toSummary()
    }

    // ── Validation ──

    private fun validateAuditStreamId(id: String) {
        require(id.isNotBlank()) { "tramai-sovereign-ops-invalid-audit-stream-id" }
        require(id.length <= 256) { "tramai-sovereign-ops-invalid-audit-stream-id" }
        require(SAFE_ID.matches(id)) { "tramai-sovereign-ops-invalid-audit-stream-id" }
    }

    // ── Mapping ──

    private fun AuditEvent.toSummary(): SovereignAuditEventSummary =
        SovereignAuditEventSummary(
            eventId = eventId,
            sequenceNumber = sequenceNumber,
            auditStreamId = auditStreamId,
            workflowRunId = workflowRunId,
            correlationId = correlationId,
            actor = actor,
            enforcementPoint = enforcementPoint,
            decision = decision,
            reasonCode = reasonCode,
            eventHash = eventHash,
            previousEventHash = previousEventHash,
            timestamp = timestamp,
        )
}
