package dev.tramai.spring.sovereign.ops.outbox

import java.time.Instant

/**
 * Sanitized snapshot of the sovereign ops audit outbox worker state.
 *
 * Exposes configuration and recent cycle state without leaking raw audit
 * data, approval IDs, reason text, tokens, replay envelopes, prompts,
 * model responses, tool arguments, exception messages, file paths, or
 * stack traces.
 *
 * This is an **internal service bean**, not a public API. Applications
 * that want to expose this via HTTP must add their own authentication
 * and authorization layer.
 */
data class SovereignOpsAuditOutboxWorkerStatusSnapshot(
    val enabled: Boolean,
    val running: Boolean,
    val recoverPreparedEnabled: Boolean,
    val dispatchPendingEnabled: Boolean,
    val batchSize: Int,
    val intervalMillis: Long,
    val initialDelayMillis: Long,
    val lastCycleStartedAt: Instant?,
    val lastCycleCompletedAt: Instant?,
    val lastCycleDurationMillis: Long?,
    val lastRecovered: SovereignOpsAuditOutboxRecoverySummary?,
    val lastDispatched: SovereignOpsAuditOutboxDispatchResult?,
    val lastFailure: SovereignOpsAuditOutboxWorkerFailureSummary?,
    val lastFailureAt: Instant?,
    val totalCyclesCompleted: Long,
    val totalCyclesFailed: Long,
)
