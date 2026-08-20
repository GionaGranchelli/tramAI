package dev.tramai.spring.sovereign.ops.observability

import dev.tramai.core.observation.event.RuntimeMetrics
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxDispatchResult
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxRecoverySummary
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerObserver
import dev.tramai.spring.sovereign.ops.outbox.SovereignOpsAuditOutboxWorkerRunSummary
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import java.time.Duration

private const val INSTRUMENTATION_SCOPE = "dev.tramai.sovereign.ops.observability"

// Worker cycle/duration attributes
private val ATTR_WORKER_OUTCOME = AttributeKey.stringKey(RuntimeAttributes.OUTBOX_WORKER_OUTCOME.name)
private val ATTR_WORKER_FAILURE_ACTION = AttributeKey.stringKey(RuntimeAttributes.OUTBOX_FAILURE_ACTION.name)
private val ATTR_WORKER_ERROR_TYPE = AttributeKey.stringKey(RuntimeAttributes.OUTBOX_ERROR_TYPE.name)

// Worker failures attributes
private val ATTR_FAILURE_ACTION = AttributeKey.stringKey(RuntimeAttributes.OUTBOX_FAILURE_ACTION.name)
private val ATTR_FAILURE_ERROR_TYPE = AttributeKey.stringKey(RuntimeAttributes.OUTBOX_ERROR_TYPE.name)

// Recovery record attributes
private val ATTR_RECOVERY_RESULT = AttributeKey.stringKey(RuntimeAttributes.OUTBOX_RECOVERY_RESULT.name)

// Dispatch record attributes
private val ATTR_DISPATCH_RESULT = AttributeKey.stringKey(RuntimeAttributes.OUTBOX_DISPATCH_RESULT.name)

/**
 * OpenTelemetry-backed observer for the sovereign ops audit outbox worker.
 *
 * Emits five metric instruments on every worker cycle. All attributes are
 * low-cardinality and sanitised — no approval IDs, reason text, tokens,
 * prompts, model responses, file paths, exception messages, or stack traces
 * are ever recorded.
 *
 * ## Metric contract
 *
 * ### worker.cycles — LongCounter {cycle}
 * Attributes: worker.outcome, worker.failure_action, worker.error_type
 *
 * ### worker.duration — DoubleHistogram ms
 * Attributes: same as worker.cycles
 *
 * ### worker.failures — LongCounter {failure}
 * Attributes: worker.failure_action, worker.error_type
 *
 * ### worker.recovered.records — LongCounter {record}
 * Attributes: recovery.result = inspected | moved_to_pending | failed_permanent | resolver_failure
 *
 * ### worker.dispatched.records — LongCounter {record}
 * Attributes: dispatch.result = claimed | emitted | failed_retryable | failed_permanent
 *
 * @param meter The OpenTelemetry [Meter] used to create instruments.
 */
class OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(
    meter: Meter,
) : SovereignOpsAuditOutboxWorkerObserver {

    constructor(
        openTelemetry: OpenTelemetry,
        instrumentationName: String = INSTRUMENTATION_SCOPE,
    ) : this(meter = openTelemetry.getMeter(instrumentationName))

    // ── Instruments ─────────────────────────────────────────────────

    private val cycles: LongCounter = meter.counterBuilder(RuntimeMetrics.SOVEREIGN_OPS_OUTBOX_WORKER_CYCLES.name)
        .setDescription("Outbox worker cycles completed per action and outcome")
        .setUnit("{cycle}")
        .build()

    private val duration: DoubleHistogram = meter.histogramBuilder(RuntimeMetrics.SOVEREIGN_OPS_OUTBOX_WORKER_DURATION.name)
        .setDescription("Duration of each outbox worker cycle")
        .setUnit("ms")
        .build()

    private val recoveredRecords: LongCounter = meter.counterBuilder(RuntimeMetrics.SOVEREIGN_OPS_OUTBOX_WORKER_RECOVERED_RECORDS.name)
        .setDescription("Records affected by PREPARED recovery per result type")
        .setUnit("{record}")
        .build()

    private val dispatchedRecords: LongCounter = meter.counterBuilder(RuntimeMetrics.SOVEREIGN_OPS_OUTBOX_WORKER_DISPATCHED_RECORDS.name)
        .setDescription("Records affected by dispatch per result type")
        .setUnit("{record}")
        .build()

    private val cycleFailures: LongCounter = meter.counterBuilder(RuntimeMetrics.SOVEREIGN_OPS_OUTBOX_WORKER_FAILURES.name)
        .setDescription("Failure notifications emitted by the sovereign ops audit outbox worker")
        .setUnit("{failure}")
        .build()

    // ── SPI ─────────────────────────────────────────────────────────

    override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
        val outcome = if (summary.failure != null) "failure" else "success"
        val failureAction = summary.failure?.action ?: "none"
        val errorType = summary.failure?.errorCode ?: "none"

        val attrs = Attributes.of(
            ATTR_WORKER_OUTCOME, outcome,
            ATTR_WORKER_FAILURE_ACTION, failureAction,
            ATTR_WORKER_ERROR_TYPE, errorType,
        )

        // Cycle counter — one increment per completed cycle
        cycles.add(1, attrs)

        // Duration histogram (clamp negative defensively)
        val ms = Duration.between(summary.startedAt, summary.completedAt).toMillis()
        val safeMs = if (ms < 0) 0.0 else ms.toDouble()
        duration.record(safeMs, attrs)

        // Recovery record counters
        summary.recovered?.let { recordRecovery(it) }

        // Dispatch record counters
        summary.dispatched?.let { recordDispatch(it) }
    }

    override fun onCycleFailed(action: String, errorCode: String) {
        cycleFailures.add(1, Attributes.of(
            ATTR_FAILURE_ACTION, action,
            ATTR_FAILURE_ERROR_TYPE, errorCode,
        ))
    }

    // ── Private helpers ─────────────────────────────────────────────

    private fun recordRecovery(r: SovereignOpsAuditOutboxRecoverySummary) {
        if (r.inspected > 0) {
            recoveredRecords.add(r.inspected.toLong(),
                Attributes.of(ATTR_RECOVERY_RESULT, "inspected"))
        }
        if (r.movedToPending > 0) {
            recoveredRecords.add(r.movedToPending.toLong(),
                Attributes.of(ATTR_RECOVERY_RESULT, "moved_to_pending"))
        }
        if (r.markedFailedPermanent > 0) {
            recoveredRecords.add(r.markedFailedPermanent.toLong(),
                Attributes.of(ATTR_RECOVERY_RESULT, "failed_permanent"))
        }
        if (r.resolverFailures > 0) {
            recoveredRecords.add(r.resolverFailures.toLong(),
                Attributes.of(ATTR_RECOVERY_RESULT, "resolver_failure"))
        }
    }

    private fun recordDispatch(d: SovereignOpsAuditOutboxDispatchResult) {
        if (d.claimed > 0) {
            dispatchedRecords.add(d.claimed.toLong(),
                Attributes.of(ATTR_DISPATCH_RESULT, "claimed"))
        }
        if (d.emitted > 0) {
            dispatchedRecords.add(d.emitted.toLong(),
                Attributes.of(ATTR_DISPATCH_RESULT, "emitted"))
        }
        if (d.failedRetryable > 0) {
            dispatchedRecords.add(d.failedRetryable.toLong(),
                Attributes.of(ATTR_DISPATCH_RESULT, "failed_retryable"))
        }
        if (d.failedPermanent > 0) {
            dispatchedRecords.add(d.failedPermanent.toLong(),
                Attributes.of(ATTR_DISPATCH_RESULT, "failed_permanent"))
        }
    }
}
