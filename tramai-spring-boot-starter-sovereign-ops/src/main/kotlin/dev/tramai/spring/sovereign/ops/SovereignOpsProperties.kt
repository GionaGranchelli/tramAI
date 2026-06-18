package dev.tramai.spring.sovereign.ops

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Externalized configuration for TramAI sovereign operations.
 *
 * Usage:
 * ```yaml
 * tramai:
 *   sovereign:
 *     ops:
 *       enabled: true
 *       mutations-enabled: false
 *       max-page-size: 100
 *       outbox:
 *         worker:
 *           enabled: false
 * ```
 *
 * Read-capable, mutation-disabled by default — inspection is safer than
 * state mutation. Set `mutations-enabled: true` to allow administrative
 * denial of approvals.
 */
@ConfigurationProperties(prefix = "tramai.sovereign.ops")
data class SovereignOpsProperties(
    /** Enables operations service beans. When false, no ops beans are created. */
    var enabled: Boolean = true,

    /**
     * When false, mutation operations (denyApproval) are blocked.
     * When true, administrative denial of approvals is allowed.
     */
    var mutationsEnabled: Boolean = false,

    /** Maximum number of audit events returned by readAuditStream. */
    var maxPageSize: Int = 100,

    /** Configuration for sovereign ops audit outbox behavior. */
    var outbox: SovereignOpsOutboxProperties = SovereignOpsOutboxProperties(),
)

data class SovereignOpsOutboxProperties(
    val worker: SovereignOpsOutboxWorkerProperties = SovereignOpsOutboxWorkerProperties(),
)

data class SovereignOpsOutboxWorkerProperties(
    val enabled: Boolean = false,
    val initialDelay: Duration = Duration.ofSeconds(5),
    val interval: Duration = Duration.ofSeconds(30),
    val batchSize: Int = 100,
    val recoverPrepared: Boolean = true,
    val dispatchPending: Boolean = true,
    val failOnMissingDispatcher: Boolean = true,
)
