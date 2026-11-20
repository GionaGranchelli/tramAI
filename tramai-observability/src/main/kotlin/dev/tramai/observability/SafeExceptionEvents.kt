package dev.tramai.observability

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span

/**
 * Records a failure on a span without exporting its message or stack trace.
 *
 * `Span.recordException(Throwable)` writes `exception.message` and `exception.stacktrace`. A message is
 * upstream-controlled and routinely carries a response body, a URL with a query string, a file path or a
 * request fragment, and a stack trace carries a good deal more. None of that belongs in exported telemetry,
 * whose job is to say *what kind* of failure happened.
 *
 * `exception.type` (the class name) plus `exception.escaped=false` keeps that diagnostic signal and nothing
 * else. The status description is the caller's choice and must be a fixed string, never `error.message`.
 *
 * One implementation, three observers. This lived only in `OpenTelemetryOperationObserver` after Epic
 * 0.7.5g fixed the provider path there; the workflow and worker observers kept calling `recordException`
 * until Epic 0.7.0's privacy follow-up (issue #551). Sharing the helper is what stops the next observer
 * from re-introducing the leak.
 */
internal fun Span.recordSafeException(error: Throwable) {
    addEvent(
        "exception",
        Attributes.of(
            AttributeKey.stringKey("exception.type"),
            error.javaClass.name,
            AttributeKey.booleanKey("exception.escaped"),
            false,
        ),
    )
}
