package dev.tramai.observability

import dev.tramai.core.observation.event.DynamicAttributeNamespaces
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.observation.event.RuntimeMetrics
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
 *
 * Fixed workflow events and attributes come from the runtime event catalogue;
 * user-supplied workflow context attributes are emitted under the explicitly
 * declared dynamic namespace [DynamicAttributeNamespaces.WORKFLOW_CONTEXT].
 */
class OpenTelemetryWorkflowObserver(
    private val tracer: Tracer,
    private val meter: Meter = OpenTelemetry.noop().getMeter("dev.tramai.observability"),
) : WorkflowObserver {
    private val metrics = OpenTelemetryWorkflowMetrics(meter)
    private val activeWorkflows = ConcurrentHashMap<WorkflowRunKey, ActiveWorkflowSpan>()
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
        val runKey = WorkflowRunKey(
            workflowName = workflowName,
            workflowId = context.workflowId,
        )
        val span = tracer.spanBuilder("workflow.$workflowName").startSpan()
        val baseAttributes = mutableMapOf<String, Any?>(
            RuntimeAttributes.WORKFLOW_NAME.name to workflowName,
            RuntimeAttributes.WORKFLOW_ID.name to context.workflowId,
        ).apply {
            context.attributes.forEach { (key, value) ->
                put(DynamicAttributeNamespaces.WORKFLOW_CONTEXT.key(key), value)
            }
        }
        span.setAttribute(RuntimeAttributes.WORKFLOW_NAME.name, workflowName)
        span.setAttribute(RuntimeAttributes.WORKFLOW_ID.name, context.workflowId)
        context.attributes.forEach { (key, value) ->
            val attributeName = DynamicAttributeNamespaces.WORKFLOW_CONTEXT.key(key)
            when (value) {
                is String -> span.setAttribute(attributeName, value)
                is Boolean -> span.setAttribute(attributeName, value)
                is Int -> span.setAttribute(attributeName, value.toLong())
                is Long -> span.setAttribute(attributeName, value)
                is Double -> span.setAttribute(attributeName, value)
                is Float -> span.setAttribute(attributeName, value.toDouble())
                null -> Unit
                else -> span.setAttribute(attributeName, value.toString())
            }
        }
        activeWorkflows[runKey] = ActiveWorkflowSpan(
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
        // Workflow events are intentionally dynamic (user-defined names); they
        // are recorded under the fixed event-name attribute and counted on the
        // workflow events metric, but are not part of the fixed catalogue.
        activeWorkflows[WorkflowRunKey(workflowName, context.workflowId)]?.recordEvent(name, attributes)
        metrics.events.add(
            1,
            workflowAttributes(
                workflowName = workflowName,
                workflowId = context.workflowId,
                outcome = null,
                extraAttributes = mapOf(RuntimeAttributes.EVENT_NAME.name to name) + attributes,
            ),
        )
    }
    override fun onStepStarted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        activeWorkflows[WorkflowRunKey(workflowName, context.workflowId)]?.recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKFLOW_STEP_STARTED) {
                set(RuntimeAttributes.STEP_NAME, stepName)
            },
        )
    }
    override fun onStepCompleted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        activeWorkflows[WorkflowRunKey(workflowName, context.workflowId)]?.recordEvent(
            RuntimeEvent.of(RuntimeEvents.WORKFLOW_STEP_COMPLETED) {
                set(RuntimeAttributes.STEP_NAME, stepName)
            },
        )
    }
    override fun onStepFailed(
        workflowName: String,
        stepName: String,
        error: Throwable,
        context: WorkflowContext,
    ) {
        activeWorkflows[WorkflowRunKey(workflowName, context.workflowId)]?.apply {
            span.recordException(error)
            recordEvent(
                RuntimeEvent.of(RuntimeEvents.WORKFLOW_STEP_FAILED) {
                    set(RuntimeAttributes.STEP_NAME, stepName)
                    set(RuntimeAttributes.ERROR_TYPE, error::class.simpleName ?: "Throwable")
                },
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
        val active = activeWorkflows.remove(
            WorkflowRunKey(
                workflowName = workflowName,
                workflowId = context.workflowId,
            ),
        ) ?: return
        if (error != null) {
            active.span.recordException(error)
            active.span.setStatus(StatusCode.ERROR, error.message ?: "Workflow failed")
        }
        active.span.setAttribute(RuntimeAttributes.WORKFLOW_OUTCOME.name, outcome)
        val attributes = workflowAttributes(
            workflowName = workflowName,
            workflowId = context.workflowId,
            outcome = outcome,
            extraAttributes = error?.let {
                mapOf(RuntimeAttributes.ERROR_TYPE_FULL.name to (it::class.simpleName ?: "Throwable"))
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
        put(RuntimeAttributes.WORKFLOW_NAME.name, workflowName)
        put(RuntimeAttributes.WORKFLOW_ID.name, workflowId)
        outcome?.let { put(RuntimeAttributes.WORKFLOW_OUTCOME.name, it) }
        putAll(extraAttributes)
    }.toOpenTelemetryAttributes()
}
private data class WorkflowRunKey(
    val workflowName: String,
    val workflowId: String,
)
private class OpenTelemetryWorkflowMetrics(
    meter: Meter,
) {
    val runs: LongCounter = meter.counterBuilder(RuntimeMetrics.WORKFLOW_RUNS.name)
        .setDescription(RuntimeMetrics.WORKFLOW_RUNS.description)
        .setUnit(RuntimeMetrics.WORKFLOW_RUNS.unit)
        .build()
    val duration: DoubleHistogram = meter.histogramBuilder(RuntimeMetrics.WORKFLOW_DURATION.name)
        .setDescription(RuntimeMetrics.WORKFLOW_DURATION.description)
        .setUnit(RuntimeMetrics.WORKFLOW_DURATION.unit)
        .build()
    val events: LongCounter = meter.counterBuilder(RuntimeMetrics.WORKFLOW_EVENTS.name)
        .setDescription(RuntimeMetrics.WORKFLOW_EVENTS.description)
        .setUnit(RuntimeMetrics.WORKFLOW_EVENTS.unit)
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

    fun recordEvent(event: RuntimeEvent) {
        span.addEvent(event.name, (baseAttributes + event.attributes()).toOpenTelemetryAttributes())
    }
}
