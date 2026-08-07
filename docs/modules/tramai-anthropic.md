# Module: `tramai-anthropic`

> **One-liner:** Provider for Anthropic's Messages API (Claude models).
> **Module type:** `provider`
> **Source files:** 1 — `AnthropicProvider.kt` (192 LOC)
> **Test files:** 1
> **Group:** `dev.tramai`, **Version:** `0.3.1`

---

## L1: Quick Start (30-second read)

### What

`tramai-anthropic` is a Tramai provider module that implements `ModelProvider` and `StreamCapable` over Anthropic's [Messages API](https://docs.anthropic.com/en/api/messages). It translates normalized Tramai `ModelRequest` objects into Anthropic API calls and maps the responses back into `ModelResponse` and `StreamChunk` values.

The module exposes a single public class:

| Type | Role |
|------|------|
| `AnthropicProvider` | `ModelProvider` + `StreamCapable` for Claude models |

### Why

Anthropic's API has a different shape from OpenAI's. Messages are split into a separate `system` parameter rather than a `system` role message, the content block model uses typed arrays (`text`, `tool_use`, etc.), and streaming follows a server-sent event (SSE) protocol with named events (`content_block_delta`, `message_delta`, `message_start`). This module encapsulates all of that so that the rest of Tramai — and your application code — never sees Anthropic-specific wire details.

It also populates the provider failure model with correct `retryable` flags, parses `Retry-After` headers, and maps Anthropic stop reasons to the normalized `FinishReason` enum.

### When

Use `tramai-anthropic` when:

- Your application uses Claude models (sonnet, haiku, opus) and you want typed, structured interactions through Tramai's `@AiService` / `@Operation` path.
- You want streaming token-by-token output from Claude without manually consuming Anthropic's SSE endpoint.
- You already have Anthropic API access and want a consistent provider abstraction alongside other models (OpenAI, Ollama).

Do **not** use `tramai-anthropic` if:

- You need native Anthropic-specific features not exposed by the provider (e.g., custom `/v1/complete` endpoints, pre-2023 API versions, or Anthropic-specific tools/metadata that the normalized `ModelRequest`/`ModelResponse` contract doesn't carry).

---

## L2: Usage Guide (5-minute read)

### Minimum: standalone completion

```kotlin
import dev.tramai.anthropic.AnthropicProvider
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest

val provider = AnthropicProvider(
    apiKey = System.getenv("ANTHROPIC_API_KEY"),
)

val response = provider.complete(
    ModelRequest(
        model = "claude-sonnet-4-20250514",
        messages = listOf(
            Message(MessageRole.SYSTEM, "You are a concise assistant."),
            Message(MessageRole.USER, "What is the capital of France?"),
        ),
        maxTokens = 512,
    ),
)

println(response.content)        // "Paris."
println(response.modelUsed)      // "claude-sonnet-4-20250514"
println(response.finishReason)   // STOP
```

The provider accepts system instructions through the standard `SYSTEM`-role `Message` — it automatically extracts it from the conversation list and sends it in the dedicated `system` parameter.

### Advanced: custom transport and version pinning

```kotlin
import dev.tramai.anthropic.AnthropicProvider
import dev.tramai.core.model.*
import java.net.http.HttpClient

val provider = AnthropicProvider(
    apiKey = System.getenv("ANTHROPIC_API_KEY"),
    baseUrl = "https://api.anthropic.com",          // default; change for proxies
    anthropicVersion = "2023-06-01",                // pin the API version header
    httpClient = HttpClient.newBuilder()             // custom HTTP config
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build(),
)

val result = provider.complete(
    ModelRequest(
        model = "claude-sonnet-4-20250514",
        messages = listOf(Message(MessageRole.USER, "Hello")),
        temperature = 0.7,
        maxTokens = 2048,
        timeoutMillis = 30_000,
    ),
)
```

### Registration with Tramai engine

```kotlin
import dev.tramai.standalone.Tramai

val tramai = Tramai.create {
    provider(AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")), name = "anthropic")
    model("claude-sonnet-4-20250514", "anthropic")
}
```

All the usual Tramai configuration paths work — YAML config, programmatic registry, Spring auto-configuration.

---

## L3: Architecture & Mechanics (15-minute read)

### Messages API integration

`AnthropicProvider` maps the Tramai `ModelRequest` to the Anthropic Messages API (`POST /v1/messages`) with the following mapping:

| Tramai field | Anthropic API location |
|---|---|
| `request.model` | `model` in JSON body |
| `request.maxTokens` | `max_tokens` (default: 1024) |
| `request.temperature` | `temperature` |
| `request.timeoutMillis` | `java.net.http.HttpRequest` timeout via `applyTramaiTimeout()` |
| `messages` (non-system) | `messages[]` array with `role` and `content` |
| `messages` (system role) | `system` top-level parameter (extracted, not sent in `messages[]`) |

HTTP headers:

- `x-api-key` — the API key passed at construction
- `anthropic-version` — defaults to `2023-06-01`, configurable via the `anthropicVersion` constructor parameter
- `Content-Type: application/json`

The response is parsed from the JSON body:

```json
{
  "content": [{ "type": "text", "text": "..." }],
  "usage": { "input_tokens": 19, "output_tokens": 9 },
  "stop_reason": "end_turn",
  "model": "claude-sonnet-4-20250514"
}
```

If the response contains no `type: "text"` content block (e.g., only `tool_use` blocks), a `ProviderException` is thrown with a clear message: *"Anthropic response did not contain a text content block"*.

Stop reason mapping:

| Anthropic `stop_reason` | Tramai `FinishReason` |
|---|---|
| `end_turn` | `STOP` |
| `max_tokens` | `LENGTH` |
| any other value | `OTHER` |

### Streaming support

`AnthropicProvider` implements `StreamCapable`, so it can be used both for blocking completions and for streaming. Streaming is enabled automatically when the engine's operation level or calling code requests streaming semantics.

Wire protocol:

1. A `"stream": true` flag is injected into the request payload.
2. The SSE endpoint returns `text/event-stream` lines.
3. The response is read with `HttpResponse.BodyHandlers.ofInputStream()`; successful streams are consumed through an explicitly owned `bufferedReader(UTF_8).use { reader -> readLine() loop }` so the response body is always closed.

Event processing:

| SSE event | StreamChunk emitted | Action |
|---|---|---|
| `event: content_block_delta` | `StreamChunk.Token(text)` | Appends the delta text, emits a token chunk |
| `event: message_start` | _(none)_ | Captures initial `input_tokens` / `output_tokens` usage from the nested `message.usage` |
| `event: message_delta` | _(none)_ | Updates `output_tokens` from the event's `usage` block |
| Stream end | `StreamChunk.Complete(fullText, usage)` | Emitted after the SSE stream is exhausted |
| Transport error | `StreamChunk.Error(cause)` | Emitted if the line iterator throws |

The provider accumulates all delta text fragments into a `StringBuilder` so the terminal `Complete` chunk carries the full response alongside final usage metrics.

Cancellation propagates: because the stream is a `kotlinx.coroutines.flow.Flow`, upstream cancellation (e.g., timeout or consumer cancellation) closes the underlying SSE connection.

### Error model

`AnthropicProvider` uses the shared Tramai failure helpers from `tramai-core`:

#### HTTP error responses

Non-2xx status codes are handled by `providerHttpFailureObserved()`:

```kotlin
logProviderHttpFailureDebug(
    logger = providerLogger,
    providerName = "anthropic",
    statusCode = response.statusCode(),
    body = errorBody.text,
)
throw providerHttpFailureObserved(
    providerId = "anthropic",
    statusCode = response.statusCode(),
    body = errorBody.text,
    bodyTruncated = errorBody.truncated,
    retryAfterHeader = response.headers().firstValue("Retry-After").orElse(null),
    observer = providerFailureDiagnosticObserver,
)
```

Non-2xx bodies are read via `readErrorBodyPreview()` (at most 8 KiB plus one sentinel byte); only the retained bytes are decoded.

This produces a `ProviderException` with:

| Property | Value |
|---|---|
| `statusCode` | The HTTP status from the API (e.g., 429, 500, 503) |
| `retryable` | `true` for 408, 425, 429, 500, 502, 503, 504; `false` otherwise |
| `retryAfterMillis` | Parsed from the `Retry-After` header (supports both seconds and RFC 1123 dates) |
| `failureCode` | `HTTP_REJECTED` |
| `message` | `"Provider request failed with HTTP {status}"` |
| `cause` | `null` |

The body is never copied into the public exception, standard logs, or telemetry. `ProviderFailureDiagnosticObserver` is the only diagnostic channel: it receives a preview capped at 8 KiB plus a truncation flag. The debug log shown above records trusted metadata only and never includes the body, headers, credentials, or cause text.

#### Transport errors

Transport-layer failures are caught and mapped by `providerTransportFailureObserved()` to fixed cause-free public exceptions. The original throwable is delivered only to `ProviderFailureDiagnosticObserver`:

| Root cause | `failureCode` | Public message | `retryable` |
|---|---|---|---|
| `HttpTimeoutException` | `TIMEOUT` | `Provider request timed out` | `true` |
| `ConnectException` | `CONNECTION_FAILED` | `Provider connection failed` | `true` |
| `IOException` | `TRANSPORT_FAILED` | `Provider transport failed` | `true` |
| Other `Throwable` | `UNEXPECTED_FAILURE` | `Provider request failed` | `false` |

#### Response parsing errors

If the API returns a successful HTTP status but the content array contains no `type: "text"` block (for example, a `tool_use`-only response), the provider throws a `ProviderException` with message *"Anthropic response did not contain a text content block"* and `retryable = false`.

#### Streaming error propagation

Streaming errors are **not** thrown — they are emitted as `StreamChunk.Error(cause)` terminal events on the flow. This lets consumers decide how to handle partial responses. The `cause` is always a `TramaiException` (usually a `ProviderException`).

#### Exception hierarchy (from tramai-core)

```
TramaiException (RuntimeException)
 ├── ProviderException       ← raised by AnthropicProvider
 ├── StructuredOutputException
 ├── ConfigurationException
 ├── TimeoutException
 ├── ProviderCapabilityException
 ├── CircuitBreakerOpenException
 └── TokenBudgetExceededException
```

All `ProviderException` instances raised by this module carry `retryable` semantics that the Tramai engine respects when computing retry policy.
