package dev.tramai.testing.persistence.audit

import dev.tramai.security.audit.AuditEvent
import dev.tramai.security.audit.AuditHashAlgorithm
import dev.tramai.security.audit.CURRENT_AUDIT_SCHEMA_VERSION
import dev.tramai.security.audit.calculateHash
import java.time.Instant

/**
 * Epic 8.1d: fixtures for the shared [dev.tramai.security.audit.AuditStore]
 * compatibility contract.
 *
 * The factory produces a fully valid event for the given stream/latest pair —
 * correct sequence, linked previous hash, and a self-hash computed over that
 * exact event — unless the caller explicitly overrides one relationship. That
 * way a negative fixture mutates exactly one invariant at a time (the
 * #269/#270 lesson: no fixture may accidentally violate several contracts).
 */
object AuditStoreFixtures {

    val BASE_TIME: Instant = Instant.parse("2026-06-21T12:00:00Z")

    /**
     * Builds the next valid [AuditEvent] for [latest].
     *
     * @param auditStreamId the stream the event will be appended to
     * @param eventId the event's unique ID
     * @param latest the authoritative latest event (null for a first append)
     * @param sequenceNumber explicit sequence override (null = latest + 1)
     * @param previousEventHash explicit previous-hash override (null = latest's hash)
     * @param eventHash explicit self-hash override (null = computed over the event)
     * @param schemaVersion explicit schema override (default = current)
     * @param auditStreamIdOverride event-side stream ID override (null = [auditStreamId])
     */
    fun event(
        auditStreamId: String,
        eventId: String,
        latest: AuditEvent?,
        sequenceNumber: Long? = null,
        previousEventHash: String? = null,
        eventHash: String? = null,
        schemaVersion: Int = CURRENT_AUDIT_SCHEMA_VERSION,
        auditStreamIdOverride: String? = null,
        timestamp: Instant = BASE_TIME,
        metadata: Map<String, String> = emptyMap(),
        decision: String = "APPROVED",
    ): AuditEvent {
        val raw = AuditEvent(
            schemaVersion = schemaVersion,
            hashAlgorithm = AuditHashAlgorithm.SHA_256,
            auditStreamId = auditStreamIdOverride ?: auditStreamId,
            eventId = eventId,
            sequenceNumber = sequenceNumber ?: (latest?.sequenceNumber ?: 0L) + 1L,
            workflowRunId = "wf-1",
            correlationId = "corr-1",
            actor = "user:alice",
            enforcementPoint = "test-gate",
            decision = decision,
            policyVersion = "v1",
            workflowDigest = "sha256:0001",
            previousEventHash = previousEventHash ?: latest?.eventHash,
            eventHash = "",
            timestamp = timestamp,
            reasonCode = "reason-1",
            metadata = metadata,
        )
        return raw.copy(eventHash = eventHash ?: raw.copy(eventHash = "").calculateHash())
    }

    /** An append factory producing a valid next event with the given [eventId]. */
    fun factory(
        auditStreamId: String,
        eventId: String,
        timestamp: Instant = BASE_TIME,
        metadata: Map<String, String> = emptyMap(),
    ): (AuditEvent?) -> AuditEvent = { latest ->
        event(auditStreamId, eventId, latest, timestamp = timestamp, metadata = metadata)
    }
}
