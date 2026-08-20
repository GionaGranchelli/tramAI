package dev.tramai.core.observation.event

/**
 * Centrally declared runtime metrics. Adapters translate these descriptors
 * into their own instrument types; the catalogue never depends on a
 * telemetry backend.
 */
object RuntimeMetrics {
    val OPERATION_ATTEMPTS = RuntimeMetricDefinition(
        name = "tramai.operation.attempts",
        description = "Completed Tramai provider attempts",
        unit = "{attempt}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val OPERATION_DURATION = RuntimeMetricDefinition(
        name = "tramai.operation.duration",
        description = "Duration of Tramai provider attempts",
        unit = "ms",
        instrumentType = RuntimeMetricInstrumentType.HISTOGRAM,
        valueType = RuntimeMetricValueType.DOUBLE,
    )
    val OPERATION_INPUT_TOKENS = RuntimeMetricDefinition(
        name = "tramai.operation.input_tokens",
        description = "Total provider input tokens observed by Tramai",
        unit = "{token}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val OPERATION_OUTPUT_TOKENS = RuntimeMetricDefinition(
        name = "tramai.operation.output_tokens",
        description = "Total provider output tokens observed by Tramai",
        unit = "{token}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val OPERATION_INPUT_TOKENS_PER_ATTEMPT = RuntimeMetricDefinition(
        name = "tramai.operation.input_tokens.per_attempt",
        description = "Distribution of input tokens per Tramai provider attempt",
        unit = "{token}",
        instrumentType = RuntimeMetricInstrumentType.HISTOGRAM,
        valueType = RuntimeMetricValueType.LONG,
    )
    val OPERATION_OUTPUT_TOKENS_PER_ATTEMPT = RuntimeMetricDefinition(
        name = "tramai.operation.output_tokens.per_attempt",
        description = "Distribution of output tokens per Tramai provider attempt",
        unit = "{token}",
        instrumentType = RuntimeMetricInstrumentType.HISTOGRAM,
        valueType = RuntimeMetricValueType.LONG,
    )
    val OPERATION_PARSE_FAILURES = RuntimeMetricDefinition(
        name = "tramai.operation.parse_failures",
        description = "Structured parse failures observed by Tramai",
        unit = "{failure}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val ENGINE_EVENTS = RuntimeMetricDefinition(
        name = "tramai.engine.events",
        description = "Engine-owned resilience and routing events emitted by Tramai",
        unit = "{event}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val WORKFLOW_RUNS = RuntimeMetricDefinition(
        name = "tramai.workflow.runs",
        description = "Completed Tramai workflows",
        unit = "{workflow}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val WORKFLOW_DURATION = RuntimeMetricDefinition(
        name = "tramai.workflow.duration",
        description = "Duration of Tramai workflows",
        unit = "ms",
        instrumentType = RuntimeMetricInstrumentType.HISTOGRAM,
        valueType = RuntimeMetricValueType.DOUBLE,
    )
    val WORKFLOW_EVENTS = RuntimeMetricDefinition(
        name = "tramai.workflow.events",
        description = "Workflow-level checkpoint, lease, and step events emitted by Tramai",
        unit = "{event}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val WORKER_HEARTBEATS = RuntimeMetricDefinition(
        name = "tramai.worker.heartbeats",
        description = "Worker heartbeat events",
        unit = "{heartbeat}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val WORKER_SHUTDOWNS = RuntimeMetricDefinition(
        name = "tramai.worker.shutdowns",
        description = "Worker shutdown events",
        unit = "{shutdown}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val WORKER_LEASES = RuntimeMetricDefinition(
        name = "tramai.worker.leases",
        description = "Worker lease operations (acquired, released, expired, renewed, contested, failed)",
        unit = "{lease}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val DLP_TOOL_REJECTED = RuntimeMetricDefinition(
        name = "tramai.dlp.tool_result_rejected",
        description = "Tool results rejected by DLP inspection",
        unit = "{rejection}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )

    val SOVEREIGN_OPS_OUTBOX_WORKER_CYCLES = RuntimeMetricDefinition(
        name = "tramai.sovereign.ops.outbox.worker.cycles",
        description = "Outbox worker cycles completed per action and outcome",
        unit = "{cycle}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val SOVEREIGN_OPS_OUTBOX_WORKER_DURATION = RuntimeMetricDefinition(
        name = "tramai.sovereign.ops.outbox.worker.duration",
        description = "Duration of each outbox worker cycle",
        unit = "ms",
        instrumentType = RuntimeMetricInstrumentType.HISTOGRAM,
        valueType = RuntimeMetricValueType.DOUBLE,
    )
    val SOVEREIGN_OPS_OUTBOX_WORKER_FAILURES = RuntimeMetricDefinition(
        name = "tramai.sovereign.ops.outbox.worker.failures",
        description = "Failure notifications emitted by the sovereign ops audit outbox worker",
        unit = "{failure}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val SOVEREIGN_OPS_OUTBOX_WORKER_RECOVERED_RECORDS = RuntimeMetricDefinition(
        name = "tramai.sovereign.ops.outbox.worker.recovered.records",
        description = "Records affected by PREPARED recovery per result type",
        unit = "{record}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )
    val SOVEREIGN_OPS_OUTBOX_WORKER_DISPATCHED_RECORDS = RuntimeMetricDefinition(
        name = "tramai.sovereign.ops.outbox.worker.dispatched.records",
        description = "Records affected by dispatch per result type",
        unit = "{record}",
        instrumentType = RuntimeMetricInstrumentType.COUNTER,
        valueType = RuntimeMetricValueType.LONG,
    )

    val all: List<RuntimeMetricDefinition> = listOf(
        OPERATION_ATTEMPTS,
        OPERATION_DURATION,
        OPERATION_INPUT_TOKENS,
        OPERATION_OUTPUT_TOKENS,
        OPERATION_INPUT_TOKENS_PER_ATTEMPT,
        OPERATION_OUTPUT_TOKENS_PER_ATTEMPT,
        OPERATION_PARSE_FAILURES,
        ENGINE_EVENTS,
        WORKFLOW_RUNS,
        WORKFLOW_DURATION,
        WORKFLOW_EVENTS,
        WORKER_HEARTBEATS,
        WORKER_SHUTDOWNS,
        WORKER_LEASES,
        DLP_TOOL_REJECTED,
        SOVEREIGN_OPS_OUTBOX_WORKER_CYCLES,
        SOVEREIGN_OPS_OUTBOX_WORKER_DURATION,
        SOVEREIGN_OPS_OUTBOX_WORKER_FAILURES,
        SOVEREIGN_OPS_OUTBOX_WORKER_RECOVERED_RECORDS,
        SOVEREIGN_OPS_OUTBOX_WORKER_DISPATCHED_RECORDS,
    )
}
