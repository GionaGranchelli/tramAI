package dev.tramai.observability

import dev.tramai.orchestration.TramaiWorkerObserver
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class OpenTelemetryTramaiWorkerObserver(
    private val tracer: Tracer,
    private val meter: Meter = OpenTelemetry.noop().getMeter("dev.tramai.observability"),
) : TramaiWorkerObserver {

    private val metrics = OpenTelemetryTramaiWorkerMetrics(meter)
    private val activeWorkerSpan = AtomicReference<Span>()

    constructor(
        openTelemetry: OpenTelemetry,
        instrumentationName: String = "dev.tramai.observability",
    ) : this(
        tracer = openTelemetry.getTracer(instrumentationName),
        meter = openTelemetry.getMeter(instrumentationName),
    )

    override fun onWorkerStarted(workerId: String) {
        val span = tracer.spanBuilder("worker.$workerId").startSpan()
        span.setAttribute("tramai.worker.id", workerId)
        activeWorkerSpan.set(span)
    }

    override fun onWorkerStopped(workerId: String) {
        activeWorkerSpan.getAndSet(null)?.let { span ->
            span.setAttribute("tramai.worker.outcome", "stopped")
            span.end()
        }
    }

    override fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) {
        recordEvent("tramai.worker.heartbeat", mapOf(
            "tramai.worker.id" to workerId,
            "tramai.worker.uptime_ms" to uptimeMillis,
            "tramai.worker.claimed_count" to claimedCount,
        ))
        metrics.heartbeats.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            "tramai.worker.uptime_ms" to uptimeMillis,
        )))
    }

    override fun onLeaseAcquired(workflowId: String, workerId: String) {
        recordEvent("tramai.worker.lease.acquired", mapOf(
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.id" to workerId,
        ))
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            "tramai.lease.operation" to "acquired",
            "tramai.worker.workflow_id" to workflowId,
        )))
    }

    override fun onLeaseReleased(workflowId: String, workerId: String) {
        recordEvent("tramai.worker.lease.released", mapOf(
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.id" to workerId,
        ))
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            "tramai.lease.operation" to "released",
            "tramai.worker.workflow_id" to workflowId,
        )))
    }

    override fun onLeaseExpired(workflowId: String, workerId: String) {
        recordEvent("tramai.worker.lease.expired", mapOf(
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.id" to workerId,
        ))
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            "tramai.lease.operation" to "expired",
            "tramai.worker.workflow_id" to workflowId,
        )))
    }

    override fun onLeaseRenewed(workflowId: String, workerId: String, newExpiry: Long) {
        recordEvent("tramai.worker.lease.renewed", mapOf(
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.id" to workerId,
            "tramai.lease.expiry" to newExpiry,
        ))
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            "tramai.lease.operation" to "renewed",
            "tramai.worker.workflow_id" to workflowId,
        )))
    }

    override fun onLeaseContested(workflowId: String, claimantWorkerId: String, currentWorkerId: String) {
        recordEvent("tramai.worker.lease.contested", mapOf(
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.id" to claimantWorkerId,
            "tramai.worker.current_owner" to currentWorkerId,
        ))
        metrics.leases.add(1, workerAttributes(claimantWorkerId, extraAttributes = mapOf(
            "tramai.lease.operation" to "contested",
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.current_owner" to currentWorkerId,
        )))
    }

    override fun onLeaseRenewalFailed(workflowId: String, workerId: String, error: Throwable) {
        activeWorkerSpan.get()?.recordException(error)
        recordEvent("tramai.worker.lease.renewal_failed", mapOf(
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.id" to workerId,
            "error_type" to (error::class.simpleName ?: "Throwable"),
        ))
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            "tramai.lease.operation" to "renewal_failed",
            "tramai.worker.workflow_id" to workflowId,
        )))
    }

    override fun onLeaseReleaseFailed(workflowId: String, workerId: String, error: Throwable) {
        activeWorkerSpan.get()?.recordException(error)
        recordEvent("tramai.worker.lease.release_failed", mapOf(
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.id" to workerId,
            "error_type" to (error::class.simpleName ?: "Throwable"),
        ))
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            "tramai.lease.operation" to "release_failed",
            "tramai.worker.workflow_id" to workflowId,
        )))
    }

    override fun onPollFailed(workerId: String, error: Throwable) {
        activeWorkerSpan.get()?.recordException(error)
        recordEvent("tramai.worker.poll.failed", mapOf(
            "tramai.worker.id" to workerId,
            "error_type" to (error::class.simpleName ?: "Throwable"),
        ))
    }

    override fun onWorkTakenOver(workflowId: String, previousWorkerId: String, newWorkerId: String) {
        recordEvent("tramai.worker.work_taken_over", mapOf(
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.previous_owner" to previousWorkerId,
            "tramai.worker.id" to newWorkerId,
        ))
    }

    override fun onUnknownAttempt(runId: String, stepName: String, priorWorkerId: String, attemptTime: Long) {
        recordEvent("tramai.worker.unknown_attempt", mapOf(
            "tramai.worker.run_id" to runId,
            "tramai.worker.step_name" to stepName,
            "tramai.worker.prior_worker_id" to priorWorkerId,
            "tramai.worker.attempt_time" to attemptTime,
        ))
    }

    override fun onStepAttemptStarted(runId: String, stepName: String, attemptId: String, workerId: String) {
        recordEvent("tramai.worker.step.started", mapOf(
            "tramai.worker.run_id" to runId,
            "tramai.worker.step_name" to stepName,
            "tramai.worker.attempt_id" to attemptId,
            "tramai.worker.id" to workerId,
        ))
    }

    override fun onStepAttemptCompleted(runId: String, stepName: String, attemptId: String, workerId: String) {
        recordEvent("tramai.worker.step.completed", mapOf(
            "tramai.worker.run_id" to runId,
            "tramai.worker.step_name" to stepName,
            "tramai.worker.attempt_id" to attemptId,
            "tramai.worker.id" to workerId,
        ))
    }

    override fun onStepAttemptFailed(runId: String, stepName: String, attemptId: String, workerId: String, error: Throwable) {
        activeWorkerSpan.get()?.recordException(error)
        recordEvent("tramai.worker.step.failed", mapOf(
            "tramai.worker.run_id" to runId,
            "tramai.worker.step_name" to stepName,
            "tramai.worker.attempt_id" to attemptId,
            "tramai.worker.id" to workerId,
            "error_type" to (error::class.simpleName ?: "Throwable"),
        ))
    }

    override fun onShutdownStarted(workerId: String) {
        recordEvent("tramai.worker.shutdown.started", mapOf(
            "tramai.worker.id" to workerId,
        ))
    }

    override fun onDrainProgress(workerId: String, done: Int, pending: Int) {
        recordEvent("tramai.worker.drain.progress", mapOf(
            "tramai.worker.id" to workerId,
            "tramai.worker.drain_done" to done,
            "tramai.worker.drain_pending" to pending,
        ))
    }

    override fun onShutdownComplete(workerId: String) {
        recordEvent("tramai.worker.shutdown.complete", mapOf(
            "tramai.worker.id" to workerId,
        ))
        metrics.shutdowns.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            "tramai.worker.shutdown.outcome" to "complete",
        )))
    }

    override fun onWorkflowAbandoned(workflowId: String, workerId: String, lastStep: String?, timeoutMillis: Long) {
        activeWorkerSpan.get()?.setAttribute("tramai.worker.abandoned_workflow", workflowId)
        recordEvent("tramai.worker.workflow.abandoned", mapOf(
            "tramai.worker.workflow_id" to workflowId,
            "tramai.worker.id" to workerId,
            "tramai.worker.last_step" to (lastStep ?: ""),
            "tramai.worker.timeout_ms" to timeoutMillis,
        ))
    }

    private fun recordEvent(name: String, attributes: Map<String, Any?>) {
        activeWorkerSpan.get()?.addEvent(name, attributes.toOpenTelemetryAttributes())
    }

    private fun workerAttributes(
        workerId: String,
        extraAttributes: Map<String, Any?> = emptyMap(),
    ): Attributes = buildMap<String, Any?> {
        put("tramai.worker.id", workerId)
        putAll(extraAttributes)
    }.toOpenTelemetryAttributes()
}

private class OpenTelemetryTramaiWorkerMetrics(
    meter: Meter,
) {
    val heartbeats: LongCounter = meter.counterBuilder("tramai.worker.heartbeats")
        .setDescription("Worker heartbeat events")
        .setUnit("{heartbeat}")
        .build()

    val shutdowns: LongCounter = meter.counterBuilder("tramai.worker.shutdowns")
        .setDescription("Worker shutdown events")
        .setUnit("{shutdown}")
        .build()

    val leases: LongCounter = meter.counterBuilder("tramai.worker.leases")
        .setDescription("Worker lease operations (acquired, released, expired, renewed, contested, failed)")
        .setUnit("{lease}")
        .build()
}
