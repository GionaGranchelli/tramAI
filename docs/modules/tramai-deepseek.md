# Module: `tramai-deepseek`

> **One-liner:** Provider for DeepSeek's OpenAI-compatible chat completion API — a thin wrapper around `OpenAiCompatibleProvider`.

---

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Provider adapter for DeepSeek's OpenAI-compatible API: `ModelProvider` implementation.

### Public entry points

- `DeepSeekProvider` — `ModelProvider` implementation (verify against `tramai-deepseek/api/tramai-deepseek.api`)

### Internal extension points

- OpenAI-compatible transport internals (reuses `tramai-openai` infrastructure)

### Significant dependencies

- `api(tramai-core)`, `api(tramai-openai)` (compatibility base); coroutines + Jackson (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Provider retains an injected/default `HttpClient` (constructor default `HttpClient.newHttpClient()`) and exposes no close contract; no engine state ownership

### Thread-safety and concurrency

- Provider must be safe for concurrent invocation by the engine

### Failure semantics

- Provider failures normalized to `ProviderException` per core contracts

### Contract tests / TCKs

- `DeepSeekProviderTckTest` — enrolled in the provider TCK

### Do not

- Do not fork OpenAI transport logic — reuse `tramai-openai`

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — provider-adapters layer

## L1: Quick Start (30-second read)

### What

`tramai-deepseek` is a `ModelProvider` + `StreamCapable` implementation that connects Tramai to DeepSeek's API. DeepSeek uses the standard OpenAI `/chat/completions` wire format, so this provider is a thin configuration wrapper around `OpenAiCompatibleProvider`.

### Why

DeepSeek offers competitive pricing and strong performance on coding and reasoning tasks. Because its API is fully OpenAI-compatible, the provider implementation is minimal — 63 lines, delegating everything to `OpenAiCompatibleProvider` with DeepSeek-specific defaults (`https://api.deepseek.com/v1`, provider ID `"deepseek"`). This follows TramAI's principle of thin provider adapters with zero duplicated transport logic.

### When to use

- **DeepSeek Chat** — use `DeepSeekProvider(apiKey = "...")` for the standard chat model
- **DeepSeek Reasoner** — pass `model = "deepseek-reasoner"` in your `@Operation` annotation
- Any DeepSeek model that speaks the OpenAI chat completions format

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-deepseek")  // version from the TramAI BOM
}
```

**Bill of Materials:**

```kotlin
val tramaiVersion: String by project

dependencies {
    implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))
    implementation("dev.tramai:tramai-deepseek")
}
```

### Where to go next

| If you want to... | Go here |
|---|---|
| Wire a provider into a working app | `docs/modules/tramai-standalone.md` |
| Use Spring Boot auto-configuration | `docs/modules/tramai-spring.md` |
| Understand the underlying transport | `docs/modules/tramai-openai.md` (L3) |

---

## L2: Usage Guide (5-minute read)

### Quick usage

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.deepseek.DeepSeekProvider
import dev.tramai.standalone.Tramai

@AiService
interface ChatService {
    @Operation(prompt = "Explain the CAP theorem", model = "deepseek-chat")
    suspend fun explain(): String
}

suspend fun main() {
    val chat = Tramai
        .builder()
        .provider(DeepSeekProvider(apiKey = System.getenv("DEEPSEEK_API_KEY")), default = true)
        .model("deepseek-chat", "deepseek")
        .build()
        .create<ChatService>()

    println(chat.explain())
}
```

### Advanced usage

DeepSeek supports all capabilities exposed by the OpenAI-compatible transport — text, streaming, structured output, tools, and vision (via multimodal models):

```kotlin
val provider = DeepSeekProvider(
    apiKey = System.getenv("DEEPSEEK_API_KEY"),
    baseUrl = "https://api.deepseek.com/v1",  // default, can override
)
```

For the DeepSeek Reasoner model:

```kotlin
@Operation(prompt = "Solve this logic puzzle", model = "deepseek-reasoner")
suspend fun reason(): String
```

### Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `apiKey` | `String` | *(required)* | DeepSeek API key |
| `baseUrl` | `String` | `https://api.deepseek.com/v1` | Base URL of the DeepSeek API |
| `httpClient` | `HttpClient` | `HttpClient.newHttpClient()` | Java HTTP client |
| `objectMapper` | `ObjectMapper` | `ObjectMapper()` | Jackson `ObjectMapper` |

Environment variable / system property support:
- `tramai.providers.deepseek.api-key` — API key
- `tramai.providers.deepseek.base-url` — base URL override
- `tramai.providers.deepseek.model` — default model (defaults to `"deepseek-chat"`)

---

## L3: Architecture & Mechanics (5-minute read)

### Design philosophy

`tramai-deepseek` follows the **delegation pattern**. It contains no transport logic, no JSON serialization, and no SSE parsing — all of that lives in `OpenAiCompatibleProvider`. The module exists purely to provide DeepSeek-specific defaults (base URL, provider ID) and a typed constructor.

### Class hierarchy

```
DeepSeekProvider (final)     ← implements ModelProvider, StreamCapable
    │
    └── delegates to OpenAiCompatibleProvider (from tramai-openai)
```

### Dependency graph

```
tramai-deepseek
  Depends on:
    - tramai-core (api)        — ModelProvider, ModelRequest, ModelResponse,
                                 StreamCapable, ProviderCapability
    - tramai-openai (impl)     — OpenAiCompatibleProvider, StaticOpenAiAccessTokenSource

  Depended on by:
    - tramai-standalone        — wired via Tramai.builder().provider()
    - tramai-spring            — auto-configuration discovers DeepSeekProvider beans
```

### Provider ID

`providerId()` returns `"deepseek"`.

### Capabilities

All four capabilities are supported: `VISION`, `TOOL_CALLING`, `STRUCTURED_OUTPUT`, `STREAMING`.
