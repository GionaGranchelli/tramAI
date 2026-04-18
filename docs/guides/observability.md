# Observability

Aurora keeps observability optional at the dependency level.

If you do not add the observability module, Aurora runs without OpenTelemetry dependencies.

## What Exists Today

The current observability integration is `OpenTelemetryOperationObserver`.

It records one span per provider attempt.

## Basic Setup

```kotlin
val observer = OpenTelemetryOperationObserver(openTelemetry)

val aurora = Aurora {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    model("gpt-5.1-chat-latest", "openai")
    observer(observer)
}
```

## What Is Recorded

The observer currently records:

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

## Why The Observer Sits Outside Providers

Aurora keeps providers small. Providers should focus on:

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

## Current Limits

Today the observability layer does not yet include:

- a richer metrics layer
- automatic exporter setup
- dashboards
- trace correlation helpers outside standard OpenTelemetry usage

It gives you the instrumentation seam, not an entire telemetry platform.
