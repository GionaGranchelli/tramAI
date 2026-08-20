package dev.tramai.observability

import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.observation.event.RuntimeMetrics
import dev.tramai.orchestration.TramaiWorkerObserver
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenTelemetry-backed [TramaiWorkerObserver] implementation.
 *
 * All event identifiers, attribute keys, and metric names come from the
 * runtime event catalogue; literals are architecture-guarded.
 */
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
        span.setAttribute(RuntimeAttributes.WORKER_ID.name, workerId)
        activeWorkerSpan.set(span)
    }

    override fun onWorkerStopped(workerId: String) {
        activeWorkerSpan.getAndSet(null)?.let { span ->
            span.setAttribute(RuntimeAttributes.WORKER_OUTCOME.name, "stopped")
            span.end()
        }
    }

    override fun onWorkerHeartbeat(workerId: String, uptimeMillis: Long, claimedCount: Int) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_HEARTBEAT) {
                set(RuntimeAttributes.WORKER_ID, workerId)
                set(RuntimeAttributes.WORKER_UPTIME_MS, uptimeMillis)
                set(RuntimeAttributes.WORKER_CLAIMED_COUNT, claimedCount.toLong())
            },
        )
        metrics.heartbeats.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            RuntimeAttributes.WORKER_UPTIME_MS.name to uptimeMillis,
        )))
    }

    override fun onLeaseAcquired(workflowId: String, workerId: String) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_LEASE_ACQUIRED) {
                set(RuntimeAttributes.WORKER_WORKFLOW_ID, workflowId)
                set(RuntimeAttributes.WORKER_ID, workerId)
            },
        )
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            RuntimeAttributes.LEASE_OPERATION.name to "acquired",
            RuntimeAttributes.WORKER_WORKFLOW_ID.name to workflowId,
        )))
    }

    override fun onLeaseReleased(workflowId: String, workerId: String) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_LEASE_RELEASED) {
                set(RuntimeAttributes.WORKER_WORKFLOW_ID, workflowId)
                set(RuntimeAttributes.WORKER_ID, workerId)
            },
        )
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            RuntimeAttributes.LEASE_OPERATION.name to "released",
            RuntimeAttributes.WORKER_WORKFLOW_ID.name to workflowId,
        )))
    }

    override fun onLeaseExpired(workflowId: String, workerId: String) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_LEASE_EXPIRED) {
                set(RuntimeAttributes.WORKER_WORKFLOW_ID, workflowId)
                set(RuntimeAttributes.WORKER_ID, workerId)
            },
        )
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            RuntimeAttributes.LEASE_OPERATION.name to "expired",
            RuntimeAttributes.WORKER_WORKFLOW_ID.name to workflowId,
        )))
    }

    override fun onLeaseRenewed(workflowId: String, workerId: String, newExpiry: Long) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_LEASE_RENEWED) {
                set(RuntimeAttributes.WORKER_WORKFLOW_ID, workflowId)
                set(RuntimeAttributes.WORKER_ID, workerId)
                set(RuntimeAttributes.LEASE_EXPIRY, newExpiry)
            },
        )
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            RuntimeAttributes.LEASE_OPERATION.name to "renewed",
            RuntimeAttributes.WORKER_WORKFLOW_ID.name to workflowId,
        )))
    }

    override fun onLeaseContested(workflowId: String, claimantWorkerId: String, currentWorkerId: String) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_LEASE_CONTESTED) {
                set(RuntimeAttributes.WORKER_WORKFLOW_ID, workflowId)
                set(RuntimeAttributes.WORKER_ID, claimantWorkerId)
                set(RuntimeAttributes.WORKER_CURRENT_OWNER, currentWorkerId)
            },
        )
        metrics.leases.add(1, workerAttributes(claimantWorkerId, extraAttributes = mapOf(
            RuntimeAttributes.LEASE_OPERATION.name to "contested",
            RuntimeAttributes.WORKER_WORKFLOW_ID.name to workflowId,
            RuntimeAttributes.WORKER_CURRENT_OWNER.name to currentWorkerId,
        )))
    }

    override fun onLeaseRenewalFailed(workflowId: String, workerId: String, error: Throwable) {
        activeWorkerSpan.get()?.recordException(error)
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_LEASE_RENEWAL_FAILED) {
                set(RuntimeAttributes.WORKER_WORKFLOW_ID, workflowId)
                set(RuntimeAttributes.WORKER_ID, workerId)
                set(RuntimeAttributes.ERROR_TYPE, error::class.simpleName ?: "Throwable")
            },
        )
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            RuntimeAttributes.LEASE_OPERATION.name to "renewal_failed",
            RuntimeAttributes.WORKER_WORKFLOW_ID.name to workflowId,
        )))
    }

    override fun onLeaseReleaseFailed(workflowId: String, workerId: String, error: Throwable) {
        activeWorkerSpan.get()?.recordException(error)
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_LEASE_RELEASE_FAILED) {
                set(RuntimeAttributes.WORKER_WORKFLOW_ID, workflowId)
                set(RuntimeAttributes.WORKER_ID, workerId)
                set(RuntimeAttributes.ERROR_TYPE, error::class.simpleName ?: "Throwable")
            },
        )
        metrics.leases.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            RuntimeAttributes.LEASE_OPERATION.name to "release_failed",
            RuntimeAttributes.WORKER_WORKFLOW_ID.name to workflowId,
        )))
    }

    override fun onPollFailed(workerId: String, error: Throwable) {
        activeWorkerSpan.get()?.recordException(error)
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_POLL_FAILED) {
                set(RuntimeAttributes.WORKER_ID, workerId)
                set(RuntimeAttributes.ERROR_TYPE, error::class.simpleName ?: "Throwable")
            },
        )
    }

    override fun onWorkTakenOver(workflowId: String, previousWorkerId: String, newWorkerId: String) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_WORK_TAKEN_OVER) {
                set(RuntimeAttributes.WORKER_WORKFLOW_ID, workflowId)
                set(RuntimeAttributes.WORKER_PREVIOUS_OWNER, previousWorkerId)
                set(RuntimeAttributes.WORKER_ID, newWorkerId)
            },
        )
    }

    override fun onUnknownAttempt(runId: String, stepName: String, priorWorkerId: String, attemptTime: Long) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_UNKNOWN_ATTEMPT) {
                set(RuntimeAttributes.WORKER_RUN_ID, runId)
                set(RuntimeAttributes.WORKER_STEP_NAME, stepName)
                set(RuntimeAttributes.WORKER_PRIOR_WORKER_ID, priorWorkerId)
                set(RuntimeAttributes.WORKER_ATTEMPT_TIME, attemptTime)
            },
        )
    }

    override fun onStepAttemptStarted(runId: String, stepName: String, attemptId: String, workerId: String) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_STEP_STARTED) {
                set(RuntimeAttributes.WORKER_RUN_ID, runId)
                set(RuntimeAttributes.WORKER_STEP_NAME, stepName)
                set(RuntimeAttributes.WORKER_ATTEMPT_ID, attemptId)
                set(RuntimeAttributes.WORKER_ID, workerId)
            },
        )
    }

    override fun onStepAttemptCompleted(runId: String, stepName: String, attemptId: String, workerId: String) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_STEP_COMPLETED) {
                set(RuntimeAttributes.WORKER_RUN_ID, runId)
                set(RuntimeAttributes.WORKER_STEP_NAME, stepName)
                set(RuntimeAttributes.WORKER_ATTEMPT_ID, attemptId)
                set(RuntimeAttributes.WORKER_ID, workerId)
            },
        )
    }

    override fun onStepAttemptFailed(runId: String, stepName: String, attemptId: String, workerId: String, error: Throwable) {
        activeWorkerSpan.get()?.recordException(error)
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_STEP_FAILED) {
                set(RuntimeAttributes.WORKER_RUN_ID, runId)
                set(RuntimeAttributes.WORKER_STEP_NAME, stepName)
                set(RuntimeAttributes.WORKER_ATTEMPT_ID, attemptId)
                set(RuntimeAttributes.WORKER_ID, workerId)
                set(RuntimeAttributes.ERROR_TYPE, error::class.simpleName ?: "Throwable")
            },
        )
    }

    override fun onShutdownStarted(workerId: String) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_SHUTDOWN_STARTED) {
                set(RuntimeAttributes.WORKER_ID, workerId)
            },
        )
    }

    override fun onDrainProgress(workerId: String, done: Int, pending: Int) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_DRAIN_PROGRESS) {
                set(RuntimeAttributes.WORKER_ID, workerId)
                set(RuntimeAttributes.WORKER_DRAIN_DONE, done.toLong())
                set(RuntimeAttributes.WORKER_DRAIN_PENDING, pending.toLong())
            },
        )
    }

    override fun onShutdownComplete(workerId: String) {
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_SHUTDOWN_COMPLETE) {
                set(RuntimeAttributes.WORKER_ID, workerId)
            },
        )
        metrics.shutdowns.add(1, workerAttributes(workerId, extraAttributes = mapOf(
            RuntimeAttributes.WORKER_SHUTDOWN_OUTCOME.name to "complete",
        )))
    }

    override fun onWorkflowAbandoned(workflowId: String, workerId: String, lastStep: String?, timeoutMillis: Long) {
        activeWorkerSpan.get()?.setAttribute(RuntimeAttributes.WORKER_ABANDONED_WORKFLOW.name, workflowId)
        recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKER_WORKFLOW_ABANDONED) {
                set(RuntimeAttributes.WORKER_WORKFLOW_ID, workflowId)
                set(RuntimeAttributes.WORKER_ID, workerId)
                set(RuntimeAttributes.WORKER_LAST_STEP, lastStep ?: "")
                set(RuntimeAttributes.WORKER_TIMEOUT_MS, timeoutMillis)
            },
        )
    }

    private fun recordEvent(event: RuntimeEvent) {
        activeWorkerSpan.get()?.addEvent(event.name, event.attributes().toOpenTelemetryAttributes())
    }

    private fun workerAttributes(
        workerId: String,
        extraAttributes: Map<String, Any?> = emptyMap(),
    ): Attributes = buildMap<String, Any?> {
        put(RuntimeAttributes.WORKER_ID.name, workerId)
        putAll(extraAttributes)
    }.toOpenTelemetryAttributes()
}

private class OpenTelemetryTramaiWorkerMetrics(
    meter: Meter,
) {
    val heartbeats: LongCounter = meter.counterBuilder(RuntimeMetrics.WORKER_HEARTBEATS.name)
        .setDescription("Worker heartbeat events")
        .setUnit("{heartbeat}")
        .build()

    val shutdowns: LongCounter = meter.counterBuilder(RuntimeMetrics.WORKER_SHUTDOWNS.name)
        .setDescription("Worker shutdown events")
        .setUnit("{shutdown}")
        .build()

    val leases: LongCounter = meter.counterBuilder(RuntimeMetrics.WORKER_LEASES.name)
        .setDescription("Worker lease operations (acquired, released, expired, renewed, contested, failed)")
        .setUnit("{lease}")
        .build()
}
