# Support Agent Example

A minimal TramAI standalone application demonstrating the core library features:
annotations, structured output, tool calling, and deterministic testing.

## Prerequisites

```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull the model
ollama pull gemma4:e2b
```

## Run

```bash
cd examples/support-agent
./gradlew run
```

## Test

```bash
cd examples/support-agent
./gradlew test
```

Tests use `tramai-testing` with `MockAiProvider` — no Ollama needed.
3 tests verify: happy path parsing, retry recovery, and exhausted-retry failure.

## What It Demonstrates

| Feature | How |
|---------|------|
| `@System` + `@User` annotations | System role prompt + user message with `{message}` interpolation |
| `@Operation(tools = [...])` | Tool registration and model-driven tool selection |
| Structured output | `Response` data class with `@AiDescription` fields, auto-parsed from JSON |
| Multiple tools | `lookupOrder` (parametrized) + `getCurrentTime` (no-input) |
| Failure/retry | `maxRetries = 2` with structured parse retry |
| Deterministic testing | `MockAiProvider` + `RecordingOperationObserver` + `TramaiAssertions` |
| Standalone | Consumes `tramai-standalone:0.4.0` from Maven — no composite build needed |
