# Standalone Builder Reference

This page documents the current standalone builder API exposed by `dev.tramai.standalone.Tramai`.

Use this page when you want exact builder methods and their current behavior.

## Entry Points

Kotlin DSL:

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai")
    model("gpt-4o", "openai")
}
```

Java/Kotlin explicit builder:

```kotlin
val tramai = Tramai.builder()
    .provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), "openai", true)
    .model("gpt-4o", "openai")
    .build()
```

Service creation:

```kotlin
val service = tramai.create<MyService>()
```

## Mental Model

The standalone builder configures:

- providers
- model routing
- fallback routing
- tools
- observation and interception hooks
- cache
- circuit breaker settings
- retry policy settings
- token budget settings

It does not require Spring and it does not pull observability transitively.

## Provider Registration

### `provider(provider, name = ..., default = false)`

Registers a `ModelProvider`.

Example:

```kotlin
provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai")
provider(OllamaProvider("http://localhost:11434"), name = "ollama")
```

Use `default = true` when you want a provider to act as the fallback default.

## Model Routing

### `model(modelName, providerName)`

Maps a logical model name to a registered provider name.

Example:

```kotlin
model("gpt-4o", "openai")
model("llama3.2", "ollama")
```

This is the main routing table used by TramAI.

## Fallback Routing

### `fallbackModel(requestedModelName, fallbackModelName, providerName)`

Adds an explicit fallback route for a requested model.

Example:

```kotlin
fallbackModel("gpt-4o", "gpt-4o-mini", "openai")
```

### `fallbackProvider(modelName, providerName)`

Adds a fallback route that keeps the same model name but tries another provider.

Example:

```kotlin
fallbackProvider("gpt-4o", "compatible-gateway")
```

### `defaultProvider(providerName)`

Selects the default provider used when no more specific mapping applies.

Example:

```kotlin
defaultProvider("openai")
```

## Tools

### `tools(vararg tools)`

Registers one or more `TramaiTool` definitions.

Example:

```kotlin
tools(
    VendorLookupTool(),
    CurrencyLookupTool(),
)
```

Current behavior:

- duplicate tool names fail fast
- tool input schema is generated from the declared input type
- idempotent tools are treated differently on transient failure paths

## Observation And Interception

### `observer(observer)`

Configures the `OperationObserver` used for engine attempts.

Use it for tracing, metrics, or recording test behavior.

### `interceptor(interceptor)`

Configures the `OperationInterceptor`.

Use it when you need request or response shaping hooks around execution.

## Cache

### `cache(cache)`

Configures the `OperationResponseCache`.

Example:

```kotlin
cache(InMemoryOperationResponseCache(maxEntries = 1_000))
```

This affects operations that opt in through `@Operation(cacheable = true)`.

## Resilience Controls

### `circuitBreaker(settings)`

Configures engine-owned provider health protection.

Type:

```kotlin
CircuitBreakerSettings(
    enabled = true,
    failureThreshold = 3,
    openDurationMillis = 30_000,
)
```

### `retryPolicy(settings)`

Configures retry pacing for retryable provider failures.

Type:

```kotlin
RetryPolicySettings(
    maxRetryAfterMillis = 20_000,
    jitterRatio = 0.1,
)
```

### `tokenBudget(settings)`

Configures engine-owned token budget controls.

Type:

```kotlin
TokenBudgetSettings(
    hardMaxTokensPerAttempt = 4_000,
    hardMaxTokensPerOperation = 12_000,
    softMaxTokensPerOperation = 8_000,
)
```

## Build

### `build()`

Builds an immutable `Tramai` instance from the builder state.

The Kotlin DSL helper calls this for you.

## Create

### `create(serviceType)`

Creates an AI-backed proxy for the service interface.

### `create<T>()`

Reified Kotlin convenience overload.

Example:

```kotlin
val analyzer = tramai.create<InvoiceAnalyzer>()
```

## First Good Default

Most standalone applications should start with:

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
    model("gpt-4o", "openai")
}
```

Then add:

1. structured output
2. tests
3. observability
4. fallback routing
5. token budgets

in that order as needed.

## Related Pages

- [Getting Started](../guides/getting-started.md)
- [Standalone Usage](../guides/standalone-usage.md)
- [Providers and Model Routing](../guides/providers.md)
- [Configuration Reference](./configuration.md)
