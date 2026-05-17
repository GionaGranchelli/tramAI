package dev.tramai.observability
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

private const val ATTR_WORKFLOW_NAME = "tramai.workflow.name"
private const val ATTR_WORKFLOW_ID = "tramai.workflow.id"
private const val ATTR_WORKFLOW_OUTCOME = "tramai.workflow.outcome"
private const val ATTR_WORKFLOW_CONTEXT_PREFIX = "tramai.workflow.context."
private const val ATTR_EVENT_NAME = "tramai.event.name"

/**
 * OpenTelemetry-backed [WorkflowObserver] implementation.
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
            ATTR_WORKFLOW_NAME to workflowName,
            ATTR_WORKFLOW_ID to context.workflowId,
        ).apply {
            putAll(context.attributes.mapKeys { "$ATTR_WORKFLOW_CONTEXT_PREFIX${it.key}" })
        }
        span.setAttribute(ATTR_WORKFLOW_NAME, workflowName)
        span.setAttribute(ATTR_WORKFLOW_ID, context.workflowId)
        context.attributes.forEach { (key, value) ->
            when (value) {
                is String -> span.setAttribute("$ATTR_WORKFLOW_CONTEXT_PREFIX$key", value)
                is Boolean -> span.setAttribute("$ATTR_WORKFLOW_CONTEXT_PREFIX$key", value)
                is Int -> span.setAttribute("$ATTR_WORKFLOW_CONTEXT_PREFIX$key", value.toLong())
                is Long -> span.setAttribute("$ATTR_WORKFLOW_CONTEXT_PREFIX$key", value)
                is Double -> span.setAttribute("$ATTR_WORKFLOW_CONTEXT_PREFIX$key", value)
                is Float -> span.setAttribute("$ATTR_WORKFLOW_CONTEXT_PREFIX$key", value.toDouble())
                null -> Unit
                else -> span.setAttribute("$ATTR_WORKFLOW_CONTEXT_PREFIX$key", value.toString())
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
        activeWorkflows[WorkflowRunKey(workflowName, context.workflowId)]?.recordEvent(name, attributes)
        metrics.events.add(
            1,
            workflowAttributes(
                workflowName = workflowName,
                workflowId = context.workflowId,
                outcome = null,
                extraAttributes = mapOf(ATTR_EVENT_NAME to name) + attributes,
            ),
        )
    }
    override fun onStepStarted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        activeWorkflows[WorkflowRunKey(workflowName, context.workflowId)]?.recordEvent(
            name = "tramai.workflow.step.started",
            attributes = mapOf("step_name" to stepName),
        )
    }
    override fun onStepCompleted(
        workflowName: String,
        stepName: String,
        context: WorkflowContext,
    ) {
        activeWorkflows[WorkflowRunKey(workflowName, context.workflowId)]?.recordEvent(
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
        activeWorkflows[WorkflowRunKey(workflowName, context.workflowId)]?.apply {
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
        active.span.setAttribute(ATTR_WORKFLOW_OUTCOME, outcome)
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
        put(ATTR_WORKFLOW_NAME, workflowName)
        put(ATTR_WORKFLOW_ID, workflowId)
        outcome?.let { put(ATTR_WORKFLOW_OUTCOME, it) }
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
