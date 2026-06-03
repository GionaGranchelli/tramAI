package dev.tramai.security.audit

interface AuditStore {
    suspend fun append(auditStreamId: String, event: AuditEvent)
    suspend fun readStream(auditStreamId: String): List<AuditEvent>
    suspend fun latestEvent(auditStreamId: String): AuditEvent?
    suspend fun clear()
}
