package dev.tramai.observability

import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongHistogram
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

private const val ATTR_GEN_AI_SYSTEM = "gen_ai.system"
private const val ATTR_GEN_AI_REQUEST_MODEL = "gen_ai.request.model"
private const val ATTR_GEN_AI_RESPONSE_MODEL = "gen_ai.response.model"
private const val ATTR_TRAMAI_OP_INTERFACE = "tramai.operation.interface"
private const val ATTR_TRAMAI_OP_METHOD = "tramai.operation.method"
private const val ATTR_TRAMAI_RETRY_ATTEMPT = "tramai.retry.attempt"
private const val ATTR_TRAMAI_OUTCOME = "tramai.outcome"
private const val ATTR_TRAMAI_EVENT_NAME = "tramai.event.name"

/**
 * OpenTelemetry-backed [OperationObserver] implementation.
 */
class OpenTelemetryOperationObserver(
    private val tracer: Tracer,
    private val meter: Meter = OpenTelemetry.noop().getMeter("dev.tramai.observability"),
) : OperationObserver {
    private val metrics = OpenTelemetryMetrics(meter)

    /**
     * Creates an observer from an [OpenTelemetry] instance.
     */
    constructor(
        openTelemetry: OpenTelemetry,
        instrumentationName: String = "dev.tramai.observability",
    ) : this(
        tracer = openTelemetry.getTracer(instrumentationName),
        meter = openTelemetry.getMeter(instrumentationName),
    )

    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        val span = tracer.spanBuilder("ai.${context.methodName}").startSpan()
        span.setAttribute(ATTR_GEN_AI_SYSTEM, context.providerId)
        span.setAttribute(ATTR_GEN_AI_REQUEST_MODEL, context.requestedModel)
        span.setAttribute(ATTR_TRAMAI_OP_INTERFACE, context.serviceInterface)
        span.setAttribute(ATTR_TRAMAI_OP_METHOD, context.methodName)
        span.setAttribute(ATTR_TRAMAI_RETRY_ATTEMPT, context.attempt.toLong())

        return SpanBackedObservation(
            span = span,
            metrics = metrics,
            baseAttributes = mapOf(
                ATTR_GEN_AI_SYSTEM to context.providerId,
                ATTR_GEN_AI_REQUEST_MODEL to context.requestedModel,
                ATTR_TRAMAI_OP_INTERFACE to context.serviceInterface,
                ATTR_TRAMAI_OP_METHOD to context.methodName,
                ATTR_TRAMAI_RETRY_ATTEMPT to context.attempt.toLong(),
            ),
        )
    }
}

private class OpenTelemetryMetrics(
    meter: Meter,
) {
    val attempts: LongCounter = meter.counterBuilder("tramai.operation.attempts")
        .setDescription("Completed Tramai provider attempts")
        .setUnit("{attempt}")
        .build()

    val duration: DoubleHistogram = meter.histogramBuilder("tramai.operation.duration")
        .setDescription("Duration of Tramai provider attempts")
        .setUnit("ms")
        .build()

    val inputTokens: LongCounter = meter.counterBuilder("tramai.operation.input_tokens")
        .setDescription("Total provider input tokens observed by Tramai")
        .setUnit(TOKEN_MASK)
        .build()

    val outputTokens: LongCounter = meter.counterBuilder("tramai.operation.output_tokens")
        .setDescription("Total provider output tokens observed by Tramai")
        .setUnit(TOKEN_MASK)
        .build()

    val inputTokensPerAttempt: LongHistogram = meter.histogramBuilder("tramai.operation.input_tokens.per_attempt")
        .setDescription("Distribution of input tokens per Tramai provider attempt")
        .setUnit(TOKEN_MASK)
        .ofLongs()
        .build()

    val outputTokensPerAttempt: LongHistogram = meter.histogramBuilder("tramai.operation.output_tokens.per_attempt")
        .setDescription("Distribution of output tokens per Tramai provider attempt")
        .setUnit(TOKEN_MASK)
        .ofLongs()
        .build()

    val parseFailures: LongCounter = meter.counterBuilder("tramai.operation.parse_failures")
        .setDescription("Structured parse failures observed by Tramai")
        .setUnit("{failure}")
        .build()

    val engineEvents: LongCounter = meter.counterBuilder("tramai.engine.events")
        .setDescription("Engine-owned resilience and routing events emitted by Tramai")
        .setUnit("{event}")
        .build()
}

private class SpanBackedObservation(
    private val span: Span,
    private val metrics: OpenTelemetryMetrics,
    private val baseAttributes: Map<String, Any?>,
) : OperationObservation {
    private val startedAtNanos: Long = System.nanoTime()
    private var latestResponse: ModelResponse? = null
    private var failure: Throwable? = null
    private var parseFailureRecorded: Boolean = false

    override fun onProviderResponse(response: ModelResponse) {
        latestResponse = response
        response.modelUsed?.let { span.setAttribute(ATTR_GEN_AI_RESPONSE_MODEL, it) }
        response.inputTokens?.let { span.setAttribute("gen_ai.usage.input_tokens", it.toLong()) }
        response.outputTokens?.let { span.setAttribute("gen_ai.usage.output_tokens", it.toLong()) }

        val attributes = responseAttributes(response)
        response.inputTokens?.let { tokens ->
            metrics.inputTokens.add(tokens.toLong(), attributes)
            metrics.inputTokensPerAttempt.record(tokens.toLong(), attributes)
        }
        response.outputTokens?.let { tokens ->
            metrics.outputTokens.add(tokens.toLong(), attributes)
            metrics.outputTokensPerAttempt.record(tokens.toLong(), attributes)
        }
    }

    override fun onProviderFailure(error: Throwable) {
        failure = error
        span.recordException(error)
        span.setStatus(StatusCode.ERROR, error.message ?: "Provider call failed")
    }

    override fun onStructuredParseFailure(
        rawResponse: String,
        errorSummary: String,
    ) {
        parseFailureRecorded = true
        span.addEvent(
            "tramai.parse.failure",
            io.opentelemetry.api.common.Attributes.of(
                AttributeKey.longKey("tramai.raw_response_length"), rawResponse.length.toLong(),
                AttributeKey.stringKey("tramai.structured.failure_code"), "output_rejected",
                AttributeKey.booleanKey("tramai.structured.parse_success"), false,
            ),
        )
        metrics.parseFailures.add(1, completionAttributes(parseSuccess = false))
    }

    override fun onEngineEvent(
        name: String,
        attributes: Map<String, Any?>,
    ) {
        span.addEvent(name, attributes.toOpenTelemetryAttributes())
        metrics.engineEvents.add(
            1,
            (baseAttributes + mapOf(ATTR_TRAMAI_EVENT_NAME to name) + attributes).toOpenTelemetryAttributes(),
        )
    }

    override fun onCallCompleted(parseSuccess: Boolean?) {
        parseSuccess?.let { span.setAttribute("tramai.structured.parse_success", it) }
        completeCall(completionAttributes(parseSuccess))
    }

    override fun onCallCancelled() {
        span.setAttribute(ATTR_TRAMAI_OUTCOME, "cancelled")
        completeCall(completionAttributes(parseSuccess = null, outcomeOverride = "cancelled"))
    }

    private fun completeCall(attributes: Attributes) {
        metrics.attempts.add(1, attributes)
        metrics.duration.record(
            (System.nanoTime() - startedAtNanos) / 1_000_000.0,
            attributes,
        )
        span.end()
    }

    private fun responseAttributes(response: ModelResponse): Attributes {
        val attributes = mutableMapOf<String, Any?>()
        attributes.putAll(baseAttributes)
        attributes[ATTR_TRAMAI_OUTCOME] = currentOutcome()
        response.modelUsed?.let { attributes[ATTR_GEN_AI_RESPONSE_MODEL] = it }
        return attributes.toOpenTelemetryAttributes()
    }

    private fun completionAttributes(parseSuccess: Boolean?, outcomeOverride: String? = null): Attributes {
        val attributes = mutableMapOf<String, Any?>()
        attributes.putAll(baseAttributes)
        attributes[ATTR_TRAMAI_OUTCOME] = outcomeOverride ?: currentOutcome()
        latestResponse?.modelUsed?.let { attributes[ATTR_GEN_AI_RESPONSE_MODEL] = it }
        parseSuccess?.let { attributes["tramai.structured.parse_success"] = it }
        failure?.let { attributes["tramai.error.type"] = it::class.simpleName ?: "Throwable" }
        return attributes.toOpenTelemetryAttributes()
    }

    private fun currentOutcome(): String = when {
        failure != null -> "failure"
        parseFailureRecorded -> "parse_failure"
        latestResponse != null -> "success"
        else -> "unknown"
    }
}

/** @see OpenTelemetryOperationObserver */
private const val TOKEN_MASK = "{token}"
