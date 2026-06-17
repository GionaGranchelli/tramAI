package dev.tramai.spring.sovereign.ops

import org.springframework.boot.context.properties.ConfigurationProperties

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
)
