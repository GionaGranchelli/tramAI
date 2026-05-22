# Observability

Tramai keeps observability optional at the dependency level.

If you do not add the observability module, Tramai runs without OpenTelemetry dependencies.

## What Exists Today

The current observability integrations are:

- `OpenTelemetryOperationObserver` for engine-level provider attempts
- `OpenTelemetryWorkflowObserver` for orchestration-level workflow execution

`OpenTelemetryOperationObserver` records one span per provider attempt and emits OpenTelemetry metrics when a meter provider is configured in your OpenTelemetry SDK.

## Basic Setup

```kotlin
val observer = OpenTelemetryOperationObserver(openTelemetry)

val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    model("gpt-4o", "openai")
    observer(observer)
}
```

## What Is Recorded

The observer currently records:

- one span per provider attempt
- provider id
- requested model
- response model, when available
- input tokens, when available
- output tokens, when available
- service interface name
- method name
- retry attempt index
- structured parse success flag for structured operations

When structured parsing fails, the observer also emits a parse-failure event.

It also emits collector-friendly metrics with these instrument names:

- `tramai.operation.attempts`
- `tramai.operation.duration`
- `tramai.operation.input_tokens`
- `tramai.operation.output_tokens`
- `tramai.operation.input_tokens.per_attempt`
- `tramai.operation.output_tokens.per_attempt`
- `tramai.operation.parse_failures`
- `tramai.engine.events`

The `tramai.engine.events` counter is used for retry scheduling, fallback routing, circuit-open transitions, token-budget warnings/failures, and other engine-owned resilience events. Event identity is attached through the `tramai.event.name` attribute.

The export path also has smoke coverage over OTLP HTTP, so the metrics path is validated against a collector-facing protocol rather than only through in-memory SDK assertions.

## GenAI Semantic Convention Compliance

Tramai aligns operation-level traces with OpenTelemetry GenAI semantic conventions when the provider exposes the necessary metadata.

The primary attributes are:

- `gen_ai.system`
- `gen_ai.request.model`
- `gen_ai.response.model`
- `gen_ai.usage.input_tokens`
- `gen_ai.usage.output_tokens`

Each provider attempt is represented as its own child span, which makes retries, fallback routing, and circuit-breaker behavior visible as separate timed units inside a single operation trace.

Structured parse failures emit a `tramai.parse.failure` span event on the attempt span that produced the invalid payload.

Textual span hierarchy example showing a retry:

```text
Method: SupportAgent.handle()
 └── [Span] ai.handle (gen_ai.system=ollama, gen_ai.request.model=gemma4:e2b)
      ├── [Span] Provider Call Attempt 1
      │    └── Event: tramai.parse.failure (validation_error=Invalid JSON)
      ├── [Span] Provider Call Attempt 2
      │    └── Event: tramai.parse.failure (validation_error=Missing required field)
      └── [Span] Provider Call Attempt 3
           └── Success (returns Response data class)
```

This layout reflects the engine's retry model: the method-level span represents the typed TramAI operation, each child span represents one provider call attempt, and parse-failure events explain why the next attempt was scheduled.

## Workflow Observability

When you use `tramai-orchestration`, `OpenTelemetryWorkflowObserver` adds a workflow-level trace layer above normal provider-attempt spans.

It records:

- one span per workflow run
- workflow start and completion
- checkpoint load/save events
- lease claim/renew/release events
- workflow step start, completion, and failure events
- workflow-level run counters and duration metrics through:
  - `tramai.workflow.runs`
  - `tramai.workflow.duration`
  - `tramai.workflow.events`

The stable workflow correlation model is the pair:

- `tramai.workflow.name`
- `tramai.workflow.id`

That pair is the active-run key used for workflow span/event attribution.
Reusing the same `workflowId` in different workflow definitions is supported.
Running two concurrent executions with the same workflow name and the same `workflowId` is still a caller error; use unique workflow ids or lease-based ownership when concurrency matters.

## Why The Observer Sits Outside Providers

Tramai keeps providers small. Providers should focus on:

- request creation
- HTTP transport
- response mapping

The engine wraps those calls with observation hooks so providers do not need tracing-specific logic.

## When To Use It

Add observability when you need:

- latency visibility
- provider attribution
- retry behavior visibility
- token usage insight
- debugging of malformed structured output

The observer does not configure exporters for you. You still set up your own OpenTelemetry SDK, meter provider, and exporter pipeline.

## Current Limits

Today the observability layer does not yet include:

- automatic exporter setup
- dashboards
- trace correlation helpers outside standard OpenTelemetry usage

It gives you the instrumentation seam, not an entire telemetry platform.
