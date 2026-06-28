package dev.tramai.spring.sovereign.ops.actuator

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration properties for approved resume worker Micrometer metrics.
 *
 * Usage:
 * ```yaml
 * tramai:
 *   sovereign:
 *     ops:
 *       actuator:
 *         approved-resume-worker-metrics:
 *           enabled: false
 *           queue-snapshot-enabled: true
 *           queue-snapshot-refresh-interval: 10s
 *           include-worker-id-tag: false
 * ```
 *
 * @property enabled Master switch for metrics. When false, no metrics observer
 *   or queue gauges are registered (default: false).
 * @property queueSnapshotEnabled When true, register gauges exposing queue
 *   snapshot counts (default: true).
 * @property queueSnapshotRefreshInterval How often the cached queue snapshot
 *   is refreshed (default: 10s).
 * @property includeWorkerIdTag When true, include [workerId] as a tag on
 *   worker metrics (default: false). Off by default to avoid unnecessary
 *   cardinality — only enable when running multiple workers.
 */
@ConfigurationProperties(prefix = "tramai.sovereign.ops.actuator.approved-resume-worker-metrics")
data class ApprovedContinuationResumeWorkerMetricsProperties(
    val enabled: Boolean = false,
    val queueSnapshotEnabled: Boolean = true,
    val queueSnapshotRefreshInterval: Duration = Duration.ofSeconds(10),
    val includeWorkerIdTag: Boolean = false,
)
