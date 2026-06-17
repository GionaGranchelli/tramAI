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
     * Read events in an audit stream, bounded by [limit].
     * @param auditStreamId The audit stream identifier.
     * @param limit Maximum number of events to return (capped by [SovereignOpsProperties.maxPageSize]).
     * @return A list of safe audit event summaries (empty if stream not found).
     */
    suspend fun readAuditStream(
        auditStreamId: String,
        limit: Int = 100,
    ): List<SovereignAuditEventSummary>

    /**
     * Get the latest event in an audit stream.
     * @param auditStreamId The audit stream identifier.
     * @return The latest event summary, or null if the stream has no events.
     */
    suspend fun latestAuditEvent(auditStreamId: String): SovereignAuditEventSummary?
}
