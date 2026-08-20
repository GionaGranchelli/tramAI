package dev.tramai.observability

import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import dev.tramai.core.observation.event.RuntimeAttributes
import dev.tramai.core.observation.event.RuntimeEvent
import dev.tramai.core.observation.event.RuntimeEvents
import dev.tramai.core.observation.event.RuntimeMetrics
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
 * OpenTelemetry-backed [OperationObserver] implementation. Event/attribute/
 * metric identities come from the runtime event catalogue.
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
        span.setAttribute(RuntimeAttributes.GEN_AI_SYSTEM.name, context.providerId)
        span.setAttribute(RuntimeAttributes.GEN_AI_REQUEST_MODEL.name, context.requestedModel)
        span.setAttribute(RuntimeAttributes.OPERATION_INTERFACE.name, context.serviceInterface)
        span.setAttribute(RuntimeAttributes.OPERATION_METHOD.name, context.methodName)
        span.setAttribute(RuntimeAttributes.RETRY_ATTEMPT.name, context.attempt.toLong())

        return SpanBackedObservation(
            span = span,
            metrics = metrics,
            baseAttributes = mapOf(
                RuntimeAttributes.GEN_AI_SYSTEM.name to context.providerId,
                RuntimeAttributes.GEN_AI_REQUEST_MODEL.name to context.requestedModel,
                RuntimeAttributes.OPERATION_INTERFACE.name to context.serviceInterface,
                RuntimeAttributes.OPERATION_METHOD.name to context.methodName,
                RuntimeAttributes.RETRY_ATTEMPT.name to context.attempt.toLong(),
            ),
        )
    }
}

private class OpenTelemetryMetrics(
    meter: Meter,
) {
    val attempts: LongCounter = meter.counterBuilder(RuntimeMetrics.OPERATION_ATTEMPTS.name)
        .setDescription(RuntimeMetrics.OPERATION_ATTEMPTS.description)
        .setUnit(RuntimeMetrics.OPERATION_ATTEMPTS.unit)
        .build()

    val duration: DoubleHistogram = meter.histogramBuilder(RuntimeMetrics.OPERATION_DURATION.name)
        .setDescription(RuntimeMetrics.OPERATION_DURATION.description)
        .setUnit(RuntimeMetrics.OPERATION_DURATION.unit)
        .build()

    val inputTokens: LongCounter = meter.counterBuilder(RuntimeMetrics.OPERATION_INPUT_TOKENS.name)
        .setDescription(RuntimeMetrics.OPERATION_INPUT_TOKENS.description)
        .setUnit(RuntimeMetrics.OPERATION_INPUT_TOKENS.unit)
        .build()

    val outputTokens: LongCounter = meter.counterBuilder(RuntimeMetrics.OPERATION_OUTPUT_TOKENS.name)
        .setDescription(RuntimeMetrics.OPERATION_OUTPUT_TOKENS.description)
        .setUnit(RuntimeMetrics.OPERATION_OUTPUT_TOKENS.unit)
        .build()

    val inputTokensPerAttempt: LongHistogram = meter.histogramBuilder(RuntimeMetrics.OPERATION_INPUT_TOKENS_PER_ATTEMPT.name)
        .setDescription(RuntimeMetrics.OPERATION_INPUT_TOKENS_PER_ATTEMPT.description)
        .setUnit(RuntimeMetrics.OPERATION_INPUT_TOKENS_PER_ATTEMPT.unit)
        .ofLongs()
        .build()

    val outputTokensPerAttempt: LongHistogram = meter.histogramBuilder(RuntimeMetrics.OPERATION_OUTPUT_TOKENS_PER_ATTEMPT.name)
        .setDescription(RuntimeMetrics.OPERATION_OUTPUT_TOKENS_PER_ATTEMPT.description)
        .setUnit(RuntimeMetrics.OPERATION_OUTPUT_TOKENS_PER_ATTEMPT.unit)
        .ofLongs()
        .build()

    val parseFailures: LongCounter = meter.counterBuilder(RuntimeMetrics.OPERATION_PARSE_FAILURES.name)
        .setDescription(RuntimeMetrics.OPERATION_PARSE_FAILURES.description)
        .setUnit(RuntimeMetrics.OPERATION_PARSE_FAILURES.unit)
        .build()

    val engineEvents: LongCounter = meter.counterBuilder(RuntimeMetrics.ENGINE_EVENTS.name)
        .setDescription(RuntimeMetrics.ENGINE_EVENTS.description)
        .setUnit(RuntimeMetrics.ENGINE_EVENTS.unit)
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
        response.modelUsed?.let { span.setAttribute(RuntimeAttributes.GEN_AI_RESPONSE_MODEL.name, it) }
        response.inputTokens?.let { span.setAttribute(RuntimeAttributes.GEN_AI_USAGE_INPUT_TOKENS.name, it.toLong()) }
        response.outputTokens?.let { span.setAttribute(RuntimeAttributes.GEN_AI_USAGE_OUTPUT_TOKENS.name, it.toLong()) }

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
        val event = RuntimeEvent.of(RuntimeEvents.PARSE_FAILURE) {
            set(RuntimeAttributes.STRUCTURED_FAILURE_CODE, "output_rejected")
            set(RuntimeAttributes.STRUCTURED_PARSE_SUCCESS, false)
        }
        span.addEvent(
            event.name,
            io.opentelemetry.api.common.Attributes.of(
                AttributeKey.stringKey(RuntimeAttributes.STRUCTURED_FAILURE_CODE.name), "output_rejected",
                AttributeKey.booleanKey(RuntimeAttributes.STRUCTURED_PARSE_SUCCESS.name), false,
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
            (baseAttributes + mapOf(RuntimeAttributes.EVENT_NAME.name to name) + attributes).toOpenTelemetryAttributes(),
        )
    }

    override fun onCallCompleted(parseSuccess: Boolean?) {
        parseSuccess?.let { span.setAttribute(RuntimeAttributes.STRUCTURED_PARSE_SUCCESS.name, it) }
        completeCall(completionAttributes(parseSuccess))
    }

    override fun onCallCancelled() {
        span.setAttribute(RuntimeAttributes.OUTCOME.name, "cancelled")
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
        attributes[RuntimeAttributes.OUTCOME.name] = currentOutcome()
        response.modelUsed?.let { attributes[RuntimeAttributes.GEN_AI_RESPONSE_MODEL.name] = it }
        return attributes.toOpenTelemetryAttributes()
    }

    private fun completionAttributes(parseSuccess: Boolean?, outcomeOverride: String? = null): Attributes {
        val attributes = mutableMapOf<String, Any?>()
        attributes.putAll(baseAttributes)
        attributes[RuntimeAttributes.OUTCOME.name] = outcomeOverride ?: currentOutcome()
        latestResponse?.modelUsed?.let { attributes[RuntimeAttributes.GEN_AI_RESPONSE_MODEL.name] = it }
        parseSuccess?.let { attributes[RuntimeAttributes.STRUCTURED_PARSE_SUCCESS.name] = it }
        failure?.let { attributes[RuntimeAttributes.ERROR_TYPE_FULL.name] = it::class.simpleName ?: "Throwable" }
        return attributes.toOpenTelemetryAttributes()
    }

    private fun currentOutcome(): String = when {
        failure != null -> "failure"
        parseFailureRecorded -> "parse_failure"
        latestResponse != null -> "success"
        else -> "unknown"
    }
}
