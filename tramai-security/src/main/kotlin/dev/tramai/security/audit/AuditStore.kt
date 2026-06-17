package dev.tramai.security.audit

interface AuditStore {
    suspend fun appendNext(
        auditStreamId: String,
        eventFactory: (latest: AuditEvent?) -> AuditEvent,
    ): AuditEvent

    suspend fun readStream(auditStreamId: String): List<AuditEvent>

    /**
     * Read a page of audit events, starting after [afterSequenceNumber].
     *
     * Default implementation delegates to [readStream] for full backward
     * compatibility. Concrete stores should override for efficient bounded reads.
     *
     * @param auditStreamId The audit stream identifier.
     * @param afterSequenceNumber Return only events with sequenceNumber greater than
     *        this value. Pass `null` to start from the beginning.
     * @param limit Maximum number of events to return. Must be > 0.
     * @return A list of events in ascending sequenceNumber order, up to [limit] items.
     */
    suspend fun readStreamPage(
        auditStreamId: String,
        afterSequenceNumber: Long? = null,
        limit: Int,
    ): List<AuditEvent> {
        require(limit > 0) { "audit-store-invalid-limit" }
        require(afterSequenceNumber == null || afterSequenceNumber >= 0) {
            "audit-store-invalid-cursor"
        }
        return readStream(auditStreamId)
            .asSequence()
            .filter { event ->
                afterSequenceNumber == null || event.sequenceNumber > afterSequenceNumber
            }
            .take(limit)
            .toList()
    }

    suspend fun latestEvent(auditStreamId: String): AuditEvent?
}
