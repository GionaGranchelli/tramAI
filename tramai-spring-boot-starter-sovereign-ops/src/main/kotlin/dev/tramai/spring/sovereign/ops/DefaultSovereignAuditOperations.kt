package dev.tramai.spring.sovereign.ops

import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditStore

/**
 * Default implementation of [SovereignAuditOperations].
 *
 * Delegates to an [AuditStore]. Returns only safe event summaries —
 * raw prompts, model responses, and sensitive payloads are never exposed.
 * All read operations are bounded by [SovereignOpsProperties.maxPageSize]
 * and use the store's bounded [AuditStore.readStreamPage] API.
 */
class DefaultSovereignAuditOperations(
    private val store: AuditStore?,
    private val properties: SovereignOpsProperties,
) : SovereignAuditOperations {

    private companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}")
    }

    override suspend fun readAuditStream(
        auditStreamId: String,
        afterSequenceNumber: Long?,
        limit: Int?,
    ): List<SovereignAuditEventSummary> {
        validateAuditStreamId(auditStreamId)
        val effectiveLimit = limit ?: properties.maxPageSize
        require(effectiveLimit in 1..properties.maxPageSize) {
            "tramai-sovereign-ops-page-size-too-large"
        }
        require(afterSequenceNumber == null || afterSequenceNumber >= 0) {
            "tramai-sovereign-ops-invalid-audit-cursor"
        }
        val auditStore = checkNotNull(store) {
            "tramai-sovereign-ops-store-unavailable"
        }
        return auditStore.readStreamPage(
            auditStreamId = auditStreamId,
            afterSequenceNumber = afterSequenceNumber,
            limit = effectiveLimit,
        ).map { it.toSummary() }
    }

    override suspend fun latestAuditEvent(
        auditStreamId: String,
    ): SovereignAuditEventSummary? {
        validateAuditStreamId(auditStreamId)
        val auditStore = checkNotNull(store) {
            "tramai-sovereign-ops-store-unavailable"
        }
        return auditStore.latestEvent(auditStreamId)?.toSummary()
    }

    // ── Validation ──

    private fun validateAuditStreamId(id: String) {
        require(id.isNotBlank()) { ERROR_INVALID_AUDIT_STREAM_ID }
        require(id.length <= 128) { ERROR_INVALID_AUDIT_STREAM_ID }
        require(SAFE_ID.matches(id)) { ERROR_INVALID_AUDIT_STREAM_ID }
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

/** @see DefaultSovereignAuditOperations */
private const val ERROR_INVALID_AUDIT_STREAM_ID = "tramai-sovereign-ops-invalid-audit-stream-id"
