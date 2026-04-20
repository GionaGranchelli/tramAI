# Observability

Tramai keeps observability optional at the dependency level.

If you do not add the observability module, Tramai runs without OpenTelemetry dependencies.

## What Exists Today

The current observability integration is `OpenTelemetryOperationObserver`.

It records one span per provider attempt and emits OpenTelemetry metrics when a meter provider is configured in your OpenTelemetry SDK.

## Basic Setup

```kotlin
val observer = OpenTelemetryOperationObserver(openTelemetry)

val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    model("gpt-5.1-chat-latest", "openai")
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
