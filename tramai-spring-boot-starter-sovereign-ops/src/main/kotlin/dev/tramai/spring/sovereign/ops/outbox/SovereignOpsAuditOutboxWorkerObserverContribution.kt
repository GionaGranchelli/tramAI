package dev.tramai.spring.sovereign.ops.outbox

/**
 * Contribution wrapper for [SovereignOpsAuditOutboxWorkerObserver] instances.
 *
 * Modules like Micrometer and OpenTelemetry contribute their observers
 * through this type instead of registering a final
 * [SovereignOpsAuditOutboxWorkerObserver] bean. The base ops auto-config
 * collects all contributions and composes them into a single delegate chain
 * behind the recording observer.
 *
 * This type intentionally does NOT extend
 * [SovereignOpsAuditOutboxWorkerObserver] to prevent Spring bean type
 * collisions.
 */
data class SovereignOpsAuditOutboxWorkerObserverContribution(
    val observer: SovereignOpsAuditOutboxWorkerObserver,
)
