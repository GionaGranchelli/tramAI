package dev.tramai.spring.sovereign.ops.observability

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

private const val INSTRUMENT_NAME = "dev.tramai.sovereign.ops.observability"

private val ATTR_ACTION = AttributeKey.stringKey("action")
private val ATTR_OUTCOME = AttributeKey.stringKey("outcome")
private val ATTR_ERROR_TYPE = AttributeKey.stringKey("error_type")

/**
 * OpenTelemetry-backed observer for the sovereign ops audit outbox worker.
 *
 * Emits five metric instruments on every worker cycle. All attributes are
 * low-cardinality and sanitised — no approval IDs, reason text, tokens,
 * prompts, model responses, file paths, exception messages, or stack traces
 * are ever recorded.
 *
 * @param meter The OpenTelemetry [Meter] used to create instruments.
 */
class OpenTelemetrySovereignOpsAuditOutboxWorkerObserver(
    meter: Meter,
) : SovereignOpsAuditOutboxWorkerObserver {

    constructor(
        openTelemetry: OpenTelemetry,
        instrumentationName: String = INSTRUMENT_NAME,
    ) : this(meter = openTelemetry.getMeter(instrumentationName))

    // ── Instruments ─────────────────────────────────────────────────

    private val cycles: LongCounter = meter.counterBuilder("tramai.sovereign.ops.outbox.worker.cycles")
        .setDescription("Outbox worker cycles completed per action and outcome")
        .setUnit("{cycle}")
        .build()

    private val duration: DoubleHistogram = meter.histogramBuilder("tramai.sovereign.ops.outbox.worker.duration")
        .setDescription("Duration of each outbox worker cycle")
        .setUnit("ms")
        .build()

    private val recoveredRecords: LongCounter = meter.counterBuilder("tramai.sovereign.ops.outbox.worker.recovered.records")
        .setDescription("Records affected by PREPARED recovery")
        .setUnit("{record}")
        .build()

    private val dispatchedRecords: LongCounter = meter.counterBuilder("tramai.sovereign.ops.outbox.worker.dispatched.records")
        .setDescription("Records affected by dispatch")
        .setUnit("{record}")
        .build()

    private val cycleFailures: LongCounter = meter.counterBuilder("tramai.sovereign.ops.outbox.worker.failures")
        .setDescription("Unexpected exceptions escaping runOnce")
        .setUnit("{failure}")
        .build()

    // ── SPI ─────────────────────────────────────────────────────────

    override fun onCycleCompleted(summary: SovereignOpsAuditOutboxWorkerRunSummary) {
        val outcome = if (summary.failure != null) "failure" else "success"

        // Cycle counter (one per action that ran)
        if (summary.recovered != null) {
            cycles.add(1, actionAttributes("recoverPrepared", outcome))
        }
        if (summary.dispatched != null) {
            cycles.add(1, actionAttributes("dispatchPending", outcome))
        }

        // Duration histogram (clamp negative defensively)
        val ms = Duration.between(summary.startedAt, summary.completedAt).toMillis()
        val safeMs = if (ms < 0) 0.0 else ms.toDouble()
        duration.record(safeMs, outcomeAttributes(outcome))

        // Recovery record counters
        summary.recovered?.let { recordRecovery(it) }

        // Dispatch record counters
        summary.dispatched?.let { recordDispatch(it) }
    }

    override fun onCycleFailed(action: String, errorCode: String) {
        cycles.add(1, Attributes.of(ATTR_ACTION, action, ATTR_OUTCOME, "failure", ATTR_ERROR_TYPE, errorCode))
        cycleFailures.add(1, Attributes.of(ATTR_ACTION, action, ATTR_ERROR_TYPE, errorCode))
    }

    // ── Private helpers ─────────────────────────────────────────────

    private fun recordRecovery(r: SovereignOpsAuditOutboxRecoverySummary) {
        val inspected = Attributes.of(ATTR_ACTION, "recoverPrepared", ATTR_OUTCOME, "inspected")
        val movedToPending = Attributes.of(ATTR_ACTION, "recoverPrepared", ATTR_OUTCOME, "movedToPending")
        val failedPermanent = Attributes.of(ATTR_ACTION, "recoverPrepared", ATTR_OUTCOME, "failedPermanent")
        val resolverFailures = Attributes.of(ATTR_ACTION, "recoverPrepared", ATTR_OUTCOME, "resolverFailures")

        if (r.inspected > 0) recoveredRecords.add(r.inspected.toLong(), inspected)
        if (r.movedToPending > 0) recoveredRecords.add(r.movedToPending.toLong(), movedToPending)
        if (r.markedFailedPermanent > 0) recoveredRecords.add(r.markedFailedPermanent.toLong(), failedPermanent)
        if (r.resolverFailures > 0) recoveredRecords.add(r.resolverFailures.toLong(), resolverFailures)
    }

    private fun recordDispatch(d: SovereignOpsAuditOutboxDispatchResult) {
        val claimed = Attributes.of(ATTR_ACTION, "dispatchPending", ATTR_OUTCOME, "claimed")
        val emitted = Attributes.of(ATTR_ACTION, "dispatchPending", ATTR_OUTCOME, "emitted")
        val failedRetryable = Attributes.of(ATTR_ACTION, "dispatchPending", ATTR_OUTCOME, "failedRetryable")
        val failedPermanent = Attributes.of(ATTR_ACTION, "dispatchPending", ATTR_OUTCOME, "failedPermanent")

        if (d.claimed > 0) dispatchedRecords.add(d.claimed.toLong(), claimed)
        if (d.emitted > 0) dispatchedRecords.add(d.emitted.toLong(), emitted)
        if (d.failedRetryable > 0) dispatchedRecords.add(d.failedRetryable.toLong(), failedRetryable)
        if (d.failedPermanent > 0) dispatchedRecords.add(d.failedPermanent.toLong(), failedPermanent)
    }

    private fun actionAttributes(action: String, outcome: String): Attributes =
        Attributes.of(ATTR_ACTION, action, ATTR_OUTCOME, outcome, ATTR_ERROR_TYPE, "none")

    private fun outcomeAttributes(outcome: String): Attributes =
        Attributes.of(ATTR_OUTCOME, outcome, ATTR_ERROR_TYPE, "none")
}
