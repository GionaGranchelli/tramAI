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
 * state mutation. Set `mutations-enabled: true` to allow cancel/force-recover.
 */
@ConfigurationProperties(prefix = "tramai.sovereign.ops")
data class SovereignOpsProperties(
    /** Enables operations service beans. When false, no ops beans are created. */
    var enabled: Boolean = true,

    /** When false, state-changing operations (cancel, force-recover) are blocked. */
    var mutationsEnabled: Boolean = false,

    /** Maximum number of items returned by list/query operations. */
    var maxPageSize: Int = 100,
)
