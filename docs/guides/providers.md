# Providers and Model Routing

Tramai keeps provider routing explicit.

That is one of the core design rules of the project.

## Start With This

If you only need the shortest working rule:

1. register one provider
2. map one model to that provider
3. use that model name in `@Operation`

Example:

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai")
    model("gpt-4o", "openai")
}
```

## Currently Implemented Providers

- `AnthropicProvider`
- `OpenAiProvider`
- `OpenAiCompatibleProvider`
- `OllamaProvider`

## How Routing Works

Tramai resolves providers in this order:

1. explicit `provider` field on `@Operation`
2. explicit model-to-provider registration
3. default provider

That means the recommended setup is:

```kotlin
val tramai = Tramai {
    provider(AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")), name = "anthropic")
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai")
    provider(OllamaProvider("http://localhost:11434"), name = "ollama")

    model("claude-sonnet-4-20250514", "anthropic")
    model("gpt-4o", "openai")
    model("llama3.2", "ollama")
}
```

## Minimal Snippets By Provider

### OpenAI

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai")
    model("gpt-4o", "openai")
}
```

### Anthropic

```kotlin
val tramai = Tramai {
    provider(AnthropicProvider(System.getenv("ANTHROPIC_API_KEY")), name = "anthropic")
    model("claude-sonnet-4-20250514", "anthropic")
}
```

### Ollama

```kotlin
val tramai = Tramai {
    provider(OllamaProvider("http://localhost:11434"), name = "ollama")
    model("llama3.2", "ollama")
}
```

### OpenAI-Compatible

```kotlin
val tramai = Tramai {
    provider(
        OpenAiCompatibleProvider.bearerToken(
            bearerToken = System.getenv("COMPATIBLE_API_TOKEN"),
            baseUrl = "https://compatible.example.com/v1",
            providerName = "compatible",
        ),
        name = "compatible",
    )
    model("my-compatible-model", "compatible")
}
```

## Why Tramai Does Not Use Model Prefix Routing

Tramai does not infer provider choice from model names like:

- `gpt-*`
- `claude-*`
- `llama-*`

That approach looks convenient at first and becomes brittle later.

Explicit mapping gives you:

- deterministic routing
- less surprising upgrades
- cleaner support for aliases and compatible endpoints

## Anthropic

Use `AnthropicProvider` for Anthropic's Messages API.

```kotlin
val provider = AnthropicProvider(
    apiKey = System.getenv("ANTHROPIC_API_KEY"),
)
```

Typical models:

- `claude-sonnet-4-20250514`

## OpenAI

Use `OpenAiProvider` for the public OpenAI API.

```kotlin
val provider = OpenAiProvider(
    apiKey = System.getenv("OPENAI_API_KEY"),
)
```

Typical example model:

- `gpt-4o`

## OpenAI-Compatible APIs

Use `OpenAiCompatibleProvider` when a backend exposes an OpenAI-style `/chat/completions` interface but is not the public OpenAI service.

```kotlin
val provider = OpenAiCompatibleProvider.bearerToken(
    bearerToken = System.getenv("COMPATIBLE_API_TOKEN"),
    baseUrl = "https://compatible.example.com/v1",
    providerName = "compatible",
)
```

This is useful when:

- an internal gateway exposes an OpenAI-compatible edge
- you want to route through a proxy or policy layer
- you want to use a vendor that intentionally mirrors the OpenAI chat-completions shape

## Experimental Codex/ChatGPT Auth-File Path

The OpenAI module also supports:

- `OpenAiProvider.codexAuth(...)`
- `OpenAiCompatibleProvider.codexAuth(...)`
- `CodexAuthFileTokenSource`

This path reads a bearer token from the local Codex auth file. It is marked experimental because it is intended for:

- local testing
- internal experimentation
- wrapper integrations around a machine already logged into Codex

Prefer API keys or explicit bearer tokens for normal production integrations.

For Spring applications, provider credentials can also come from secret references through `SecretValueResolver` beans and built-in `env:` / `file:` reference schemes.

## Ollama

Use `OllamaProvider` for local development and self-hosted local-model experimentation.

```kotlin
val provider = OllamaProvider(
    baseUrl = "http://localhost:11434",
)
```

Typical models:

- `llama3.2`

## First Good Default

If you are new to TramAI, start with one provider only. Add multi-provider routing later.

That keeps initial setup simpler and makes failures easier to reason about.

## Provider Selection Patterns

### One Provider Per App

This is the simplest setup:

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai", default = true)
}
```

### Different Providers For Different Jobs

Use different providers when:

- one model is better for extraction
- another is better for local/offline development
- you want to compare cost or latency tradeoffs

Example:

```kotlin
@AiService
interface Extractor {
    @Operation(
        prompt = "Extract billing fields",
        model = "gpt-4o",
        provider = "openai",
    )
    suspend fun extract(input: String): BillingFields
}

@AiService
interface LocalDevSummarizer {
    @Operation(
        prompt = "Summarize the incident",
        model = "llama3.2",
        provider = "ollama",
    )
    suspend fun summarize(input: String): String
}
```

## Spring Configuration Shortcut

If you use Spring Boot, this is the minimum OpenAI setup:

```yaml
tramai:
  default-provider: openai
  models:
    gpt-4o: openai
  providers:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.openai.com/v1
```

## What Is Not Implemented Yet

Provider support does not yet include:

- externalized provider-level retry-policy configuration
- externalized provider-level timeout-policy configuration
- provider-native structured-output optimizations
- bundled cloud-specific secret-store resolvers

Tramai does already support engine-owned retries for retryable provider failures and per-operation timeout control through `@Operation`.

See [Current Limitations](../reference/limitations.md) for the current boundaries.

## Next Step

After provider wiring works:

- read [Structured Output](./structured-output.md) to move beyond raw strings
- read [Testing TramAI Code](./testing.md) to make provider-dependent code deterministic
- read [Production Hardening](./production-hardening.md) if you need budgets, retries, and secret handling
