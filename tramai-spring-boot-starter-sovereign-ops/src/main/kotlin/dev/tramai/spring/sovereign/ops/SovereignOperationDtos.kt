package dev.tramai.spring.sovereign.ops

import java.time.Instant

/**
 * Safe summary of an approval request.
 *
 * Does NOT expose:
 * - approval tokens
 * - resume tokens
 * - raw tool arguments
 * - sensitive payloads
 */
data class SovereignApprovalSummary(
    val approvalId: String,
    val status: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val createdAt: Instant?,
    val expiresAt: Instant?,
    val actor: String?,
    val reasonCode: String?,
)

/**
 * Safe summary of a suspended invocation.
 *
 * Does NOT expose:
 * - raw replay envelopes
 * - raw tool arguments
 * - sensitive payloads
 * - raw messages
 */
data class SovereignSuspendedInvocationSummary(
    val suspendedInvocationId: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val serviceName: String?,
    val operationName: String?,
    val createdAt: Instant?,
    val status: String,
    val replayEnvelopeDigest: String?,
)

/**
 * Safe summary of an audit event.
 *
 * Does NOT expose:
 * - raw prompts
 * - raw model responses
 * - sensitive payloads
 * - document content
 */
data class SovereignAuditEventSummary(
    val eventId: String,
    val sequenceNumber: Long,
    val auditStreamId: String,
    val workflowRunId: String?,
    val correlationId: String?,
    val actor: String?,
    val enforcementPoint: String?,
    val decision: String?,
    val reasonCode: String?,
    val eventHash: String,
    val previousEventHash: String?,
    val timestamp: Instant,
)

/**
 * Runtime health/state check.
 *
 * Reports which store beans are available and the persistence mode
 * (file-backed or in-memory) based on detected bean types.
 */
data class SovereignRuntimeStatus(
    val runtimeAvailable: Boolean,
    val auditStoreAvailable: Boolean,
    val approvalStoreAvailable: Boolean,
    val approvalContinuationStoreAvailable: Boolean,
    val suspendedInvocationStoreAvailable: Boolean,
    val persistenceMode: String,
)
