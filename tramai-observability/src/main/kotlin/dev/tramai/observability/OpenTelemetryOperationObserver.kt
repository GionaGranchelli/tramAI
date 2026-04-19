package dev.tramai.observability

import dev.tramai.core.model.ModelResponse
import dev.tramai.core.observation.OperationCallContext
import dev.tramai.core.observation.OperationObservation
import dev.tramai.core.observation.OperationObserver
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

/**
 * OpenTelemetry-backed [OperationObserver] implementation.
 */
class OpenTelemetryOperationObserver(
    private val tracer: Tracer,
) : OperationObserver {

    /**
     * Creates an observer from an [OpenTelemetry] instance.
     */
    constructor(
        openTelemetry: OpenTelemetry,
        instrumentationName: String = "dev.tramai.observability",
    ) : this(openTelemetry.getTracer(instrumentationName))

    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        val span = tracer.spanBuilder("ai.${context.methodName}").startSpan()
        span.setAttribute("gen_ai.system", context.providerId)
        span.setAttribute("gen_ai.request.model", context.requestedModel)
        span.setAttribute("tramai.operation.interface", context.serviceInterface)
        span.setAttribute("tramai.operation.method", context.methodName)
        span.setAttribute("tramai.retry.attempt", context.attempt.toLong())

        return SpanBackedObservation(span)
    }
}

private class SpanBackedObservation(
    private val span: Span,
) : OperationObservation {
    override fun onProviderResponse(response: ModelResponse) {
        response.modelUsed?.let { span.setAttribute("gen_ai.response.model", it) }
        response.inputTokens?.let { span.setAttribute("gen_ai.usage.input_tokens", it.toLong()) }
        response.outputTokens?.let { span.setAttribute("gen_ai.usage.output_tokens", it.toLong()) }
    }

    override fun onProviderFailure(error: Throwable) {
        span.recordException(error)
        span.setStatus(StatusCode.ERROR, error.message ?: "Provider call failed")
        span.end()
    }

    override fun onStructuredParseFailure(
        rawResponse: String,
        errorSummary: String,
    ) {
        span.addEvent(
            "tramai.parse.failure",
            io.opentelemetry.api.common.Attributes.of(
                AttributeKey.stringKey("tramai.raw_response"), rawResponse,
                AttributeKey.stringKey("tramai.validation_error"), errorSummary,
            ),
        )
    }

    override fun onCallCompleted(parseSuccess: Boolean?) {
        parseSuccess?.let { span.setAttribute("tramai.structured.parse_success", it) }
        span.end()
    }
}
