package dev.tramai.security.audit

import java.time.Instant

const val CURRENT_AUDIT_SCHEMA_VERSION = 1

data class AuditEvent(
    val schemaVersion: Int,
    val hashAlgorithm: AuditHashAlgorithm,
    val auditStreamId: String,
    val eventId: String,
    val sequenceNumber: Long,
    val workflowRunId: String?,
    val correlationId: String?,
    val actor: String?,
    val enforcementPoint: String,
    val decision: String,
    val policyVersion: String?,
    val workflowDigest: String?,
    val previousEventHash: String?,
    val eventHash: String,
    val timestamp: Instant,
    val reasonCode: String?,
    val metadata: Map<String, String> = emptyMap(),
)
