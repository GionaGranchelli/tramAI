package dev.tramai.spring.sovereign.ops.micrometer

import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatchResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecoverySummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerRunSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Micrometer-backed observer for the sovereign ops audit outbox worker.
 *
 * Emits five metric instruments on every worker cycle. All tags are
 * low-cardinality and sanitised - no approval IDs, reason text, tokens,
 * prompts, model responses, file paths, exception messages, or stack traces
 * are ever recorded.
 *
 * ## Metric contract
 *
 * ### worker.cycles - Counter {cycle}
 * Tags: outcome, failure_action, error_type
 *
 * ### worker.duration - Timer (ms)
 * Tags: same as worker.cycles
 *
 * ### worker.failures - Counter {failure}
 * Tags: failure_action, error_type
 *
 * ### worker.recovered.records - Counter {record}
 * Tags: result in {inspected, moved_to_pending, failed_permanent, resolver_failure}
 *
 * ### worker.dispatched.records - Counter {record}
 * Tags: result in {claimed, emitted, failed_retryable, failed_permanent}
 *
 * @param registry The Micrometer [MeterRegistry] used to create instruments.
 */
class MicrometerSovereignOpsAuditOutboxWorkerObserver(
    private val registry: MeterRegistry,
) : SovereignOpsAuditOutboxWorkerObserver {

    override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
        val outcome = if (summary.failure != null) "failure" else "success"
        val failureAction = summary.failure?.action ?: "none"
        val errorType = summary.failure?.errorCode ?: "none"

        val tags = listOf(
            Tag.of("outcome", outcome),
            Tag.of("failure_action", failureAction),
            Tag.of("error_type", errorType),
        )

        registry.counter("tramai.sovereign.ops.outbox.worker.cycles", tags).increment()

        val ms = Duration.between(summary.startedAt, summary.completedAt).toMillis()
        val safeMs = if (ms < 0) 0L else ms
        registry.timer("tramai.sovereign.ops.outbox.worker.duration", tags)
            .record(safeMs, TimeUnit.MILLISECONDS)

        summary.recovered?.let { recordRecovery(it) }
        summary.dispatched?.let { recordDispatch(it) }
    }

    override fun onCycleFailed(action: String, errorCode: String) {
        registry.counter(
            "tramai.sovereign.ops.outbox.worker.failures",
            listOf(
                Tag.of("failure_action", action),
                Tag.of("error_type", errorCode),
            ),
        ).increment()
    }

    private fun recordRecovery(r: SovereignOpsAuditOutboxRecoverySummary) {
        if (r.inspected > 0) {
            registry.counter(
                METRIC_RECOVERED,
                listOf(Tag.of("result", "inspected")),
            ).increment(r.inspected.toDouble())
        }
        if (r.movedToPending > 0) {
            registry.counter(
                METRIC_RECOVERED,
                listOf(Tag.of("result", "moved_to_pending")),
            ).increment(r.movedToPending.toDouble())
        }
        if (r.markedFailedPermanent > 0) {
            registry.counter(
                METRIC_RECOVERED,
                listOf(Tag.of("result", "failed_permanent")),
            ).increment(r.markedFailedPermanent.toDouble())
        }
        if (r.resolverFailures > 0) {
            registry.counter(
                METRIC_RECOVERED,
                listOf(Tag.of("result", "resolver_failure")),
            ).increment(r.resolverFailures.toDouble())
        }
    }

    private fun recordDispatch(d: SovereignOpsAuditOutboxDispatchResult) {
        if (d.claimed > 0) {
            registry.counter(
                METRIC_DISPATCHED,
                listOf(Tag.of("result", "claimed")),
            ).increment(d.claimed.toDouble())
        }
        if (d.emitted > 0) {
            registry.counter(
                METRIC_DISPATCHED,
                listOf(Tag.of("result", "emitted")),
            ).increment(d.emitted.toDouble())
        }
        if (d.failedRetryable > 0) {
            registry.counter(
                METRIC_DISPATCHED,
                listOf(Tag.of("result", "failed_retryable")),
            ).increment(d.failedRetryable.toDouble())
        }
        if (d.failedPermanent > 0) {
            registry.counter(
                METRIC_DISPATCHED,
                listOf(Tag.of("result", "failed_permanent")),
            ).increment(d.failedPermanent.toDouble())
        }
    }
}

/** @see MicrometerSovereignOpsAuditOutboxWorkerObserver */
private const val METRIC_RECOVERED = "tramai.sovereign.ops.outbox.worker.recovered.records"

/** @see MicrometerSovereignOpsAuditOutboxWorkerObserver */
private const val METRIC_DISPATCHED = "tramai.sovereign.ops.outbox.worker.dispatched.records"
