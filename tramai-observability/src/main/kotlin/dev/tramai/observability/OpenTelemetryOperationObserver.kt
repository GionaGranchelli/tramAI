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
        span.setAttribute("gen_ai.system", context.providerId)
        span.setAttribute("gen_ai.request.model", context.requestedModel)
        span.setAttribute("tramai.operation.interface", context.serviceInterface)
        span.setAttribute("tramai.operation.method", context.methodName)
        span.setAttribute("tramai.retry.attempt", context.attempt.toLong())

        return SpanBackedObservation(
            span = span,
            metrics = metrics,
            baseAttributes = mapOf(
                "gen_ai.system" to context.providerId,
                "gen_ai.request.model" to context.requestedModel,
                "tramai.operation.interface" to context.serviceInterface,
                "tramai.operation.method" to context.methodName,
                "tramai.retry.attempt" to context.attempt.toLong(),
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
        .setUnit("{token}")
        .build()

    val outputTokens: LongCounter = meter.counterBuilder("tramai.operation.output_tokens")
        .setDescription("Total provider output tokens observed by Tramai")
        .setUnit("{token}")
        .build()

    val inputTokensPerAttempt: LongHistogram = meter.histogramBuilder("tramai.operation.input_tokens.per_attempt")
        .setDescription("Distribution of input tokens per Tramai provider attempt")
        .setUnit("{token}")
        .ofLongs()
        .build()

    val outputTokensPerAttempt: LongHistogram = meter.histogramBuilder("tramai.operation.output_tokens.per_attempt")
        .setDescription("Distribution of output tokens per Tramai provider attempt")
        .setUnit("{token}")
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
        response.modelUsed?.let { span.setAttribute("gen_ai.response.model", it) }
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
                AttributeKey.stringKey("tramai.raw_response"), rawResponse,
                AttributeKey.stringKey("tramai.validation_error"), errorSummary,
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
            (baseAttributes + mapOf("tramai.event.name" to name) + attributes).toOpenTelemetryAttributes(),
        )
    }

    override fun onCallCompleted(parseSuccess: Boolean?) {
        parseSuccess?.let { span.setAttribute("tramai.structured.parse_success", it) }
        val attributes = completionAttributes(parseSuccess)
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
        attributes["tramai.outcome"] = currentOutcome()
        response.modelUsed?.let { attributes["gen_ai.response.model"] = it }
        return attributes.toOpenTelemetryAttributes()
    }

    private fun completionAttributes(parseSuccess: Boolean?): Attributes {
        val attributes = mutableMapOf<String, Any?>()
        attributes.putAll(baseAttributes)
        attributes["tramai.outcome"] = currentOutcome()
        latestResponse?.modelUsed?.let { attributes["gen_ai.response.model"] = it }
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
