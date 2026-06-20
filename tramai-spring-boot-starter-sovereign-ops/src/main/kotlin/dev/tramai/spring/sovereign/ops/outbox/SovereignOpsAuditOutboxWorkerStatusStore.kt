package dev.tramai.spring.sovereign.ops.outbox

/**
 * Internal memory-backed status holder for the sovereign ops audit outbox worker.
 *
 * Implementations must be thread-safe and must not expose raw audit data,
 * approval IDs, reason text, tokens, replay envelopes, prompts, model
 * responses, tool arguments, exception messages, file paths, or stack traces.
 */
interface SovereignOpsAuditOutboxWorkerStatusStore {
    fun snapshot(): SovereignOpsAuditOutboxWorkerStatusSnapshot

    fun markLifecycleStarted()
    fun markLifecycleStopped()

    fun recordCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary)
    fun recordCycleFailed(action: String, errorCode: String)
}
