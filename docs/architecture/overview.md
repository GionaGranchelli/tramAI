# Architecture Overview

TramAI is a Kotlin-first JVM runtime for governed AI workflows. The core product goal is to let application code interact with AI through typed interface methods while runtime layers handle structured output, provider dispatch, policy enforcement, routing, replay safety, and auditability — not through framework-specific chains, agents, or scattered prompt orchestration.

## Core Flow

1. Application code defines an `@AiService` interface.
2. TramAI generates a proxy for that interface at startup.
3. A method call is translated into an AI operation.
4. Inputs are rendered into model messages.
5. If the return type is structured, TramAI generates or loads a cached schema and validates the response.
6. Provider resolution selects the primary route and any configured fallbacks.
7. The provider call is wrapped in observability instrumentation when OpenTelemetry is available.
8. The provider returns raw model output plus metadata.
9. TramAI either returns raw text, streams chunks, or parses and validates structured output.
10. Parse failures enter a retry loop with validation feedback.

### Structured Output Retry

When a structured operation fails to parse, `JacksonStructuredOutputHandler.analyze()` returns a `StructuredOutputResult.Failure` with a `feedbackMessage` that explains the problem in a form suitable for retry.

The retry path distinguishes three failure classes:

- the model response is not JSON
- the response is JSON but has the wrong structure
- the response structure is present but validation fails

For each retry, the engine appends the assistant's broken response and the generated user feedback message, then resubmits the full conversation history on the next attempt.

If the retry limit is exhausted, TramAI throws `StructuredOutputException` with `originalPrompt`, `lastRawResponse`, `validationError`, and `attemptCount`.

## Proxy Dispatch

TramAI uses standard JDK dynamic proxies through `java.lang.reflect.Proxy` to implement `@AiService` interfaces at runtime.

`TramaiInvocationHandler` intercepts interface method calls and converts `@Operation`, `@System`, and `@User` annotations into a `ModelRequest` for the engine.

Suspend functions are detected from the JVM method signature by checking for a trailing `Continuation` parameter. Those calls are dispatched through `invokeSuspend()`, which launches a coroutine with the continuation's context.

Non-suspend methods are wrapped in `runBlocking { execute(...) }`, so synchronous Java and Kotlin calls still use the same execution pipeline.

The current runtime strategy favors portability and low startup complexity. In `0.4.0`, the planned direction is KSP compile-time code generation for GraalVM `native-image` support.

## Major Layers

- Application layer: consumer-defined `@AiService` interfaces
- Proxy layer: runtime-generated implementations that intercept method calls
- Operation engine: prompt rendering, provider dispatch, streaming, tool calling, parsing, retry, and error handling
- Observability layer: OpenTelemetry spans, metrics, and semantic attributes
- Provider layer: Anthropic, Ollama, OpenAI, and OpenAI-compatible providers
- Optional orchestration layer: typed workflow coordination above `tramai-engine`
- Optional retrieval and memory layer: RAG, embeddings, vector stores, and bounded chat memory
- Optional runtime/platform layer: scheduling, HTTP APIs, MCP exposure, tenancy, and dashboard operations

### Operation Interceptor/Observer SPI

The operation engine exposes two opt-in extension points for cross-cutting behavior.

`OperationInterceptor` provides `interceptRequest` and `interceptResponse` hooks. Typical uses include memory injection before dispatch and PII redaction before the result leaves the engine.

`OperationObserver` provides callbacks for route selection, provider response receipt, parse failure, and circuit-breaker events.

Both default to `NoOp` implementations and are enabled explicitly via `Tramai.builder()`.

### Provider Resolution

`ProviderRegistry` maps model names to provider routes and can define explicit fallback chains for each route.

Resolution order is:

1. `@Operation(provider = ...)`
2. model-to-provider mapping
3. default provider

Each provider attempt is wrapped with circuit-breaker and retry policy logic. When the primary route fails, fallback routes are tried in order.

### Observability

When OpenTelemetry is available, TramAI records spans and metrics using GenAI-oriented attributes such as `gen_ai.system`, `gen_ai.request.model`, `gen_ai.response.model`, `gen_ai.usage.input_tokens`, and `gen_ai.usage.output_tokens`.

Each provider call attempt gets its own child span. Structured parse failures emit a `tramai.parse.failure` span event on the relevant attempt span.

The core metrics surface includes:

- `tramai.operation.attempts`
- `tramai.operation.parse_failures`
- `tramai.engine.events`

## Architectural Priorities

- Typed contracts over prompt-heavy application code
- Framework-agnostic core with optional adapters
- Automatic observability with OpenTelemetry semantic conventions
- Kotlin-first APIs with Java-compatible entry points
- Explicit module boundaries so higher-level runtime features do not leak into the core library contract

## Core Library Boundaries

The core `@AiService` runtime deliberately does not turn application code into an autonomous agent framework. In particular, TramAI does **not** handle:

- autonomous agent loops
- hidden cross-step orchestration
- peer-to-peer agent chat
- fine-tuning workflows

Those concerns, where they exist in this repository, live in optional modules above the core library surface. The design goal is composability: application teams can use typed AI services alone, or add memory, RAG, orchestration, scheduling, server, MCP, or platform layers without changing the core execution model.
