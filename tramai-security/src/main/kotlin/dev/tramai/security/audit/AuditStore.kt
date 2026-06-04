package dev.tramai.security.audit

interface AuditStore {
    suspend fun appendNext(auditStreamId: String, eventFactory: suspend (latest: AuditEvent?) -> AuditEvent): AuditEvent
    suspend fun readStream(auditStreamId: String): List<AuditEvent>
    suspend fun latestEvent(auditStreamId: String): AuditEvent?
}
