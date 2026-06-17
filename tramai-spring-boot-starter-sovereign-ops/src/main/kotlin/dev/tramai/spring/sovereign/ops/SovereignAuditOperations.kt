package dev.tramai.spring.sovereign.ops

/**
 * Operations for reading sovereign audit event streams.
 *
 * Delegates to the underlying [AuditStore]. Only safe summaries
 * are returned — raw prompts, model responses, and sensitive
 * payloads are NEVER exposed.
 */
interface SovereignAuditOperations {

    /**
     * Read all events in an audit stream.
     * @param auditStreamId The audit stream identifier.
     * @return A list of safe audit event summaries (empty if stream not found).
     */
    suspend fun readAuditStream(auditStreamId: String): List<SovereignAuditEventSummary>

    /**
     * Get the latest event in an audit stream.
     * @param auditStreamId The audit stream identifier.
     * @return The latest event summary, or null if the stream has no events.
     */
    suspend fun latestAuditEvent(auditStreamId: String): SovereignAuditEventSummary?
}
