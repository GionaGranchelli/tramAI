# Standalone Usage

Use `tramai-standalone` when you want a small runtime without framework integration.

## What The Standalone Module Does

The standalone module composes:

- core annotations and contracts
- the execution engine
- the Jackson-based structured output handler

It does not bring observability transitively. Observability remains opt-in.

## Basic Builder Pattern

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    model("gpt-5.1-chat-latest", "openai")
}
```

Then create services:

```kotlin
val service = tramai.create<MyService>()
```

## Kotlin DSL

The recommended style is:

```kotlin
val tramai = Tramai {
    provider(AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")), name = "anthropic")
    provider(OllamaProvider("http://localhost:11434"), name = "ollama")

    model("claude-sonnet-4-20250514", "anthropic")
    model("llama3.2", "ollama")

    defaultProvider("anthropic")
}
```

## Resilience Controls

The standalone builder exposes explicit fallback routing and engine-owned resilience settings:

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    provider(OllamaProvider("http://localhost:11434"), name = "ollama")

    model("gpt-5.1-chat-latest", "openai")
    model("gpt-5.1-mini", "openai")
    model("llama3.2", "ollama")

    fallbackModel("gpt-5.1-chat-latest", "gpt-5.1-mini", "openai")
    fallbackProvider("gpt-5.1-chat-latest", "ollama")

    circuitBreaker(
        CircuitBreakerSettings(
            enabled = true,
            failureThreshold = 3,
            openDurationMillis = 30_000,
        ),
    )
    retryPolicy(
        RetryPolicySettings(
            maxRetryAfterMillis = 20_000,
            jitterRatio = 0.1,
        ),
    )
    tokenBudget(
        TokenBudgetSettings(
            hardMaxTokensPerAttempt = 4_000,
            hardMaxTokensPerOperation = 12_000,
            softMaxTokensPerOperation = 8_000,
        ),
    )
}
```

These controls are explicit on purpose:

- fallbacks are configured routes, not hidden model-prefix heuristics
- `Retry-After` hints are honored when providers expose them
- circuit breaking is engine-owned, so providers stay focused on transport and mapping
- token budget policy is engine-owned and uses provider-reported usage across retries and tool loops

## Response Caching

Successful non-streaming operations can be cached explicitly per operation.

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    model("gpt-5.1-chat-latest", "openai")
    cache(InMemoryOperationResponseCache(maxEntries = 1_000))
}

@AiService
interface Analyzer {
    @Operation(
        prompt = "Analyze the invoice",
        model = "gpt-5.1-chat-latest",
        cacheable = true,
        cacheTtlMillis = 60_000,
    )
    suspend fun analyze(invoiceId: String): String
}
```

Current cache boundary:

- caching is opt-in per operation
- only successful non-streaming operations are cached
- tool-enabled operations are not cached automatically

## Java-Style Builder

The same setup can be built with the explicit builder:

```kotlin
val tramai = Tramai.builder()
    .provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), "openai", true)
    .model("gpt-5.1-chat-latest", "openai")
    .build()
```

## Raw String Operations

Raw strings are the simplest starting point.

```kotlin
@AiService
interface Summarizer {
    @Operation(
        prompt = "Summarize the input in three bullet points",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun summarize(text: String): String
}
```

## Structured Operations

If the method returns a Kotlin type instead of `String`, Tramai activates structured parsing automatically.

```kotlin
data class Summary(
    val sentiment: String,
    val keyPoints: List<String>,
)

@AiService
interface Analyzer {
    @Operation(
        prompt = "Analyze the customer feedback and return a structured summary",
        model = "gpt-5.1-chat-latest",
    )
    suspend fun analyze(feedback: String): Summary
}
```

Tramai will:

1. generate a schema-like prompt fragment from the return type
2. ask the provider for JSON
3. parse the JSON
4. validate it
5. retry with feedback if parsing or validation fails

## Suspend And Blocking Methods

Tramai supports both:

```kotlin
@AiService
interface SuspendService {
    @Operation(prompt = "Reply briefly", model = "gpt-5.1-chat-latest")
    suspend fun respond(input: String): String
}

@AiService
interface BlockingService {
    @Operation(prompt = "Reply briefly", model = "gpt-5.1-chat-latest")
    fun respond(input: String): String
}
```

Current rule:

- Kotlin `suspend fun` is the preferred style
- blocking methods are supported for Java-friendly and non-coroutine call sites
- automatic generation of blocking companion APIs is not implemented; use explicit blocking interfaces

## Operation Design Advice

Good Tramai operations are:

- narrow in purpose
- explicit in output shape
- stable in model choice
- easy to test in isolation

Bad Tramai operations tend to combine multiple unrelated tasks into a single prompt and return a vague string blob.

## Add Observability

Standalone usage can attach an observer:

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    model("gpt-5.1-chat-latest", "openai")
    observer(OpenTelemetryOperationObserver(openTelemetry))
}
```

See [Observability](./observability.md) for details.
