# Streaming

TramAI supports raw text streaming through normal `@AiService` methods.

The streaming contract is intentionally narrow:

- streaming methods return `Flow<StreamChunk>`
- the first milestone is raw text streaming, not streamed structured output
- the engine owns retry, failover, cancellation, and terminal error semantics

---

## Basic Shape

Declare a streaming method by returning `Flow<StreamChunk>`:

```kotlin
@AiService
interface StreamingService {
    @Operation(
        prompt = "Stream a response",
        model = "claude-sonnet-4-20250514",
    )
    fun stream(invoiceId: String): Flow<StreamChunk>
}
```

Then consume the stream normally:

```kotlin
val chunks = runBlocking {
    service.stream("invoice-123").toList()
}
```

`StreamChunk` currently has three cases:

- `StreamChunk.Token(text)`: one incremental text fragment
- `StreamChunk.Complete(fullText, usage)`: successful terminal event
- `StreamChunk.Error(cause)`: terminal failure event

---

## Standalone Example

```kotlin
val tramai = Tramai {
    provider(AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")), name = "anthropic", default = true)
    model("claude-sonnet-4-20250514", "anthropic")
}

val service = tramai.create<StreamingService>()

runBlocking {
    service.stream("invoice-123").collect { chunk ->
        when (chunk) {
            is StreamChunk.Token -> print(chunk.text)
            is StreamChunk.Complete -> println("\ncomplete: ${chunk.fullText}")
            is StreamChunk.Error -> println("\nerror: ${chunk.cause.message}")
        }
    }
}
```

---

## Guarantees

### Startup Failover

If the selected provider fails before the first emitted token and a fallback route exists, the engine may retry or fail over before user-visible output begins.

This is the only point where stream failover is allowed.

### Mid-Stream Failure

Once a stream has emitted a visible token, TramAI does not attempt to splice together partial output from another provider.

Instead it emits a terminal `StreamChunk.Error`.

That is deliberate. Cross-provider stream stitching would invent correctness guarantees the API does not actually have.

### Cancellation

If the consumer stops collection, TramAI propagates cancellation to the underlying provider stream.

That means patterns like `take(1)` or coroutine cancellation stop provider work promptly when the provider implementation supports it.

### Terminal Usage

Usage metrics, when available, arrive on `StreamChunk.Complete`.

If token budget policy rejects the terminal usage, the engine emits a terminal `StreamChunk.Error` after the already-emitted tokens.

---

## Current Boundaries

Streaming is intentionally narrow today:

- only raw text streaming is supported
- streamed structured partials are not supported
- tool calling during a stream is not part of the current public contract
- Java-specific streaming wrappers are not a first-class surface yet

If you need typed structured extraction, use the standard request/response path instead of streaming.

---

## Common Failure Cases

Expect these behaviors:

- provider does not support streaming: TramAI fails with `ProviderCapabilityException`
- provider fails before first token and fallback exists: the engine may retry on a fallback route
- provider fails after first token: the stream ends with `StreamChunk.Error`
- consumer cancels collection: provider work is cancelled

---

## Design Intent

The streaming API exists to support low-latency text delivery without breaking TramAI's typed service model.

It is not intended to become:

- a separate chain DSL
- an event bus for every provider-native signal
- a justification for hidden mid-stream recovery rules

That boundary keeps the feature predictable and testable.
