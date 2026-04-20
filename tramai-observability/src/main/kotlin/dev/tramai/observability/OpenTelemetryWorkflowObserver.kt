@file:OptIn(ExperimentalTramAIOrchestration::class)

package dev.tramai.observability

import dev.tramai.orchestration.ExperimentalTramAIOrchestration
import dev.tramai.orchestration.WorkflowContext
import dev.tramai.orchestration.WorkflowObserver
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.ConcurrentHashMap

/**
 * OpenTelemetry-backed [WorkflowObserver] implementation.
 */
@ExperimentalTramAIOrchestration
class OpenTelemetryWorkflowObserver(
    private val tracer: Tracer,
    private val meter: Meter = OpenTelemetry.noop().getMeter("dev.tramai.observability"),
) : WorkflowObserver {
    private val metrics = OpenTelemetryWorkflowMetrics(meter)
    private val activeWorkflows = ConcurrentHashMap<String, ActiveWorkflowSpan>()

    constructor(
        openTelemetry: OpenTelemetry,
        instrumentationName: String = "dev.tramai.observability",
    ) : this(
        tracer = openTelemetry.getTracer(instrumentationName),
        meter = openTelemetry.getMeter(instrumentationName),
    )

    override fun onWorkflowStarted(
        workflowName: String,
        context: WorkflowContext,
    ) {
        val span = tracer.spanBuilder("workflow.$workflowName").startSpan()
        val baseAttributes = mutableMapOf<String, Any?>(
            "tramai.workflow.name" to workflowName,
            "tramai.workflow.id" to context.workflowId,
        ).apply {
            putAll(context.attributes.mapKeys { "tramai.workflow.context.${it.key}" })
        }

        span.setAttribute("tramai.workflow.name", workflowName)
        span.setAttribute("tramai.workflow.id", context.workflowId)
        context.attributes.forEach { (key, value) ->
            when (value) {
                is String -> span.setAttribute("tramai.workflow.context.$key", value)
                is Boolean -> span.setAttribute("tramai.workflow.context.$key", value)
                is Int -> span.setAttribute("tramai.workflow.context.$key", value.toLong())
                is Long -> span.setAttribute("tramai.workflow.context.$key", value)
                is Double -> span.setAttribute("tramai.workflow.context.$key", value)
                is Float -> span.setAttribute("tramai.workflow.context.$key", value.toDouble())
                null -> Unit
                else -> span.setAttribute("tramai.workflow.context.$key", value.toString())
            }
        }

        activeWorkflows[context.workflowId] = ActiveWorkflowSpan(
            span = span,
            startedAtNanos = System.nanoTime(),
            baseAttributes = baseAttributes,
        )
    }

    override fun onWorkflowEvent(
        workflowName: String,
        name: String,
        attributes: Map<String, Any?>,
        context: WorkflowContext,
    ) {
        activeWorkflows[context.workflowId]?.recordEvent(name, attributes)
        metrics.events.add(
            1,
            workflowAttributes(
                workflowName = workflowName,
                workflowId = context.workflowId,
                outcome = null,
                extraAttributes = mapOf("tramai.event.name" to name) + attributes,
            ),
        )
    }

    override fun onStepStarted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        activeWorkflows[context.workflowId]?.recordEvent(
            name = "tramai.workflow.step.started",
            attributes = mapOf("step_name" to stepName),
        )
    }

    override fun onStepCompleted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        activeWorkflows[context.workflowId]?.recordEvent(
            name = "tramai.workflow.step.completed",
            attributes = mapOf("step_name" to stepName),
        )
    }

    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        activeWorkflows[context.workflowId]?.apply {
            span.recordException(error)
            recordEvent(
                name = "tramai.workflow.step.failed",
                attributes = mapOf(
                    "step_name" to stepName,
                    "error_type" to (error::class.simpleName ?: "Throwable"),
                ),
            )
        }
    }

    override fun onWorkflowCompleted(
        workflowName: String,
        context: WorkflowContext,
    ) {
        completeWorkflow(
            workflowName = workflowName,
            context = context,
            outcome = "success",
            error = null,
        )
    }

    override fun onWorkflowFailed(
        workflowName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        completeWorkflow(
            workflowName = workflowName,
            context = context,
            outcome = "failure",
            error = error,
        )
    }

    private fun completeWorkflow(
        workflowName: String,
        context: WorkflowContext,
        outcome: String,
        error: Throwable?,
    ) {
        val active = activeWorkflows.remove(context.workflowId) ?: return
        if (error != null) {
            active.span.recordException(error)
            active.span.setStatus(StatusCode.ERROR, error.message ?: "Workflow failed")
        }
        active.span.setAttribute("tramai.workflow.outcome", outcome)
        val attributes = workflowAttributes(
            workflowName = workflowName,
            workflowId = context.workflowId,
            outcome = outcome,
            extraAttributes = error?.let {
                mapOf("tramai.error.type" to (it::class.simpleName ?: "Throwable"))
            }.orEmpty(),
        )
        metrics.runs.add(1, attributes)
        metrics.duration.record(
            (System.nanoTime() - active.startedAtNanos) / 1_000_000.0,
            attributes,
        )
        active.span.end()
    }

    private fun workflowAttributes(
        workflowName: String,
        workflowId: String,
        outcome: String?,
        extraAttributes: Map<String, Any?>,
    ): Attributes = buildMap<String, Any?> {
        put("tramai.workflow.name", workflowName)
        put("tramai.workflow.id", workflowId)
        outcome?.let { put("tramai.workflow.outcome", it) }
        putAll(extraAttributes)
    }.toOpenTelemetryAttributes()
}

private class OpenTelemetryWorkflowMetrics(
    meter: Meter,
) {
    val runs: LongCounter = meter.counterBuilder("tramai.workflow.runs")
        .setDescription("Completed Tramai workflows")
        .setUnit("{workflow}")
        .build()

    val duration: DoubleHistogram = meter.histogramBuilder("tramai.workflow.duration")
        .setDescription("Duration of Tramai workflows")
        .setUnit("ms")
        .build()

    val events: LongCounter = meter.counterBuilder("tramai.workflow.events")
        .setDescription("Workflow-level checkpoint, lease, and step events emitted by Tramai")
        .setUnit("{event}")
        .build()
}

private data class ActiveWorkflowSpan(
    val span: Span,
    val startedAtNanos: Long,
    val baseAttributes: Map<String, Any?>,
) {
    fun recordEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) {
        span.addEvent(name, (baseAttributes + attributes).toOpenTelemetryAttributes())
    }
}
