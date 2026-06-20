package dev.tramai.spring.sovereign.ops.actuator

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusSnapshot
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerStatusStore
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation

/**
 * Read-only Actuator endpoint exposing the sanitized sovereign ops audit outbox worker status.
 *
 * Returns [SovereignOpsAuditOutboxWorkerStatusSnapshot] which contains only:
 * - configuration state (enabled, running, dispatch settings)
 * - last cycle timestamps and sanitized summaries
 * - completed/failed cycle counters
 *
 * Does NOT expose:
 * - raw outbox records
 * - approval IDs, reason text, tokens, replay envelopes
 * - prompts, model responses, tool arguments
 * - exception messages, file paths, or stack traces
 *
 * Disabled by default. Enable with:
 * ```
 * tramai.sovereign.ops.actuator.worker-status.enabled=true
 * ```
 *
 * Note: The TramAI property only creates the endpoint bean. Spring Boot
 * Actuator exposure must also be configured by the application:
 * ```
 * management.endpoints.web.exposure.include=tramaiSovereignOpsWorker
 * ```
 */
@Endpoint(id = "tramaiSovereignOpsWorker")
class SovereignOpsWorkerStatusEndpoint(
    private val statusStore: SovereignOpsAuditOutboxWorkerStatusStore,
) {
    @ReadOperation
    fun status(): SovereignOpsAuditOutboxWorkerStatusSnapshot =
        statusStore.snapshot()
}
