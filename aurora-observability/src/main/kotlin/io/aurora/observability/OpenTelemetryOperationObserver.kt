package io.aurora.observability

import io.aurora.core.model.ModelResponse
import io.aurora.core.observation.OperationCallContext
import io.aurora.core.observation.OperationObservation
import io.aurora.core.observation.OperationObserver
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

class OpenTelemetryOperationObserver(
    private val tracer: Tracer,
) : OperationObserver {

    constructor(
        openTelemetry: OpenTelemetry,
        instrumentationName: String = "io.aurora.observability",
    ) : this(openTelemetry.getTracer(instrumentationName))

    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        val span = tracer.spanBuilder("ai.${context.methodName}").startSpan()
        span.setAttribute("gen_ai.system", context.providerId)
        span.setAttribute("gen_ai.request.model", context.requestedModel)
        span.setAttribute("aurora.operation.interface", context.serviceInterface)
        span.setAttribute("aurora.operation.method", context.methodName)
        span.setAttribute("aurora.retry.attempt", context.attempt.toLong())

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
            "aurora.parse.failure",
            io.opentelemetry.api.common.Attributes.of(
                AttributeKey.stringKey("aurora.raw_response"), rawResponse,
                AttributeKey.stringKey("aurora.validation_error"), errorSummary,
            ),
        )
    }

    override fun onCallCompleted(parseSuccess: Boolean?) {
        parseSuccess?.let { span.setAttribute("aurora.structured.parse_success", it) }
        span.end()
    }
}
