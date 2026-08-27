# Module: `tramai-ollama`

> **One-liner:** Provider for locally-hosted Ollama models via the Ollama chat API.

---

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Provider adapter for Ollama (local models): `ModelProvider` implementation.

### Public entry points

- `OllamaProvider` — `ModelProvider` implementation (verify against `tramai-ollama/api/tramai-ollama.api`)

### Internal extension points

- Provider transport internals (HTTP client, JSON mapping)

### Significant dependencies

- `api(tramai-core)`; coroutines + Jackson (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Provider owns its HTTP client lifecycle; no engine state ownership

### Thread-safety and concurrency

- Provider must be safe for concurrent invocation by the engine

### Failure semantics

- Provider failures normalized to `ProviderException` per core contracts

### Contract tests / TCKs

- `OllamaProviderTckTest` — enrolled in the provider TCK

### Do not

- Do not implement retry/fallback/circuit logic here — the engine owns that

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — provider-adapters layer

## L1: Quick Start (30-second read)

### What
`tramai-ollama` is a `ModelProvider` implementation that connects TramAI to any Ollama instance via the Ollama `/api/chat` endpoint. It supports both synchronous `complete()` and streaming `stream()` operations. A single class: `OllamaProvider`.

### Why
Separating the Ollama provider into its own module means:
- No runtime dependency on Ollama libraries when using other providers
- Clear module boundary — provider code doesn't pollute core/engine
- Easy to test and replace

### When to use
```
Use this module when:
- You run local models via Ollama (Llama, Gemma, Qwen, Mistral, CodeGemma, etc.)
- You're developing/testing offline without cloud API keys
- You need privacy-sensitive AI (data never leaves your machine)
- You want zero-cost inference during development

Don't use this module when:
- You need a cloud-hosted proprietary model (use tramai-openai or tramai-anthropic)
- Ollama is not installed on your machine or network
```

### How to add
```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-ollama")  // version from the TramAI BOM
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-ollama</artifactId>
    <version>${tramai.version}</version>
</dependency>
```

### Where to go next
- [Module Guide](../module-guide.md#recipe-1-chat-with-a-local-model-in-5-minutes) — Recipe 1: Chat with a local model
- [Guide: Providers](../guides/providers.md) — Provider configuration and registration
- [tramai-core](./tramai-core.md) — The SPI contracts this module implements
- [tramai-standalone](./tramai-standalone.md) — How to wire it all together without Spring

---

## L2: Usage Guide (5-minute read)

### Quick usage

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.ollama.OllamaProvider
import dev.tramai.standalone.Tramai

@AiService
interface LocalChat {
    @Operation(prompt = "What is the capital of Japan?")
    suspend fun ask(): String
}

suspend fun main() {
    val chat = Tramai
        .builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma4:e2b", "ollama")
        .build()
        .create<LocalChat>()

    println(chat.ask()) // "Tokyo"
}
```

**Prerequisites:** Ollama running on `localhost:11434` with the `gemma4:e2b` model pulled.

### Advanced usage

**Custom base URL (remote Ollama):**
```kotlin
val provider = OllamaProvider(
    baseUrl = "http://ollama-server.internal:11434",
)
```

**Streaming responses:**
```kotlin
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.provider.StreamCapable

@AiService
interface StreamingChat {
    @Operation(prompt = "Count from 1 to 5")
    suspend fun streaming(): String
}

suspend fun main() {
    val provider = OllamaProvider()
    val streamCapable = provider as StreamCapable
    val request = ModelRequest(model = "gemma4:e2b", messages = listOf(
        Message(role = MessageRole.USER, content = "Count from 1 to 5")
    ))

    streamCapable.stream(request).collect { chunk ->
        when (chunk) {
            is StreamChunk.Token -> print(chunk.text)
            is StreamChunk.Complete -> println("\n[Done: ${chunk.usage}]")
            is StreamChunk.Error -> System.err.println("\n[Error: ${chunk.cause}]")
        }
    }
}
```

**Multiple models with the same provider:**
```kotlin
val service = Tramai
    .builder()
    .provider(OllamaProvider("http://localhost:11434"), name = "local", default = true)
    .model("gemma4:e2b", "local")
    .model("qwen2.5:7b", "local")
    .build()
    .create<MultiModelService>()
```

### Expert usage

**Custom HTTP client configuration:**
```kotlin
val customClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

val provider = OllamaProvider(
    baseUrl = "http://localhost:11434",
    httpClient = customClient,
)
```

**Custom ObjectMapper with registered modules:**
```kotlin
val mapper = JsonMapper.builder()
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    .build()

val provider = OllamaProvider(
    objectMapper = mapper,
)
```

### Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `baseUrl` | `String` | `http://localhost:11434` | Ollama server base URL |
| `httpClient` | `HttpClient` | `HttpClient.newHttpClient()` | Java HTTP client instance |
| `objectMapper` | `ObjectMapper` | `ObjectMapper()` | Jackson ObjectMapper for JSON processing |

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy
`OllamaProvider` is deliberately minimal — a single file with no abstractions beyond what `ModelProvider` and `StreamCapable` require. The philosophy is: **one provider, one endpoint, one file**. No factory pattern, no plugin system, no configuration framework.

### Module boundary
```
Public:
  dev.tramai.ollama.OllamaProvider — ModelProvider + StreamCapable
    constructor(baseUrl, httpClient, objectMapper)
    complete(request): ModelResponse
    stream(request): Flow<StreamChunk>
    providerId(): String -> always "ollama"

Package: dev.tramai.ollama
  1 source file, no sub-packages.
```

### Dependency graph
```
tramai-ollama
  Depends on:
    - tramai-core (required) — ModelProvider, ModelRequest, ModelResponse, StreamCapable, StreamChunk
    - jackson-databind (transitive) — JSON serialization/deserialization
    - kotlinx-coroutines-core (transitive) — Flow-based streaming

  Depended on by:
    - tramai-standalone (wires via Tramai.builder().provider())
    - tramai-spring (auto-configuration discovers OllamaProvider beans)
    - tramai-spring (via provider registration in application.yml)
```

### Inner mechanics

```
1. User calls service.method()
2. Engine builds ModelRequest (model name, messages list, parameters)
3. Engine resolves provider via ProviderRegistry → finds "ollama"
4. Engine calls OllamaProvider.complete(request) or .stream(request)
5. Provider serializes payload:
   {
     "model": "gemma4:e2b",
     "stream": false,
     "messages": [
       {"role": "user", "content": "..."}
     ]
   }
6. HTTP POST to {baseUrl}/api/chat
7. Response parsed:
   - message.content → String output
   - prompt_eval_count → inputTokens
   - eval_count → outputTokens
   - done_reason → FinishReason
8. ModelResponse returned to engine
9. Engine dispatches to StructuredOutputHandler if return type != String
```

### Streaming mechanics
For streaming requests (`stream: true`), the provider uses `HttpResponse.BodyHandlers.ofLines()` to consume the NDJSON response line-by-line. Each line is parsed as a JSON object. Token-by-token emission via `Flow<StreamChunk>`:
- Each non-empty `message.content` → `StreamChunk.Token`
- When `done: true` → `StreamChunk.Complete` with final usage
- On error → `StreamChunk.Error`

### Error model

| Exception | Trigger | Recovery |
|-----------|---------|----------|
| `ProviderException` | Unexpected response role (not `assistant`) | Check Ollama response format compatibility |
| `ProviderHttpException` (via `providerHttpFailure`) | Non-2xx HTTP status | Check Ollama is running and model exists |
| `ProviderTransportException` (via `providerTransportFailure`) | Network error, timeout, connection refused | Verify Ollama is reachable at `baseUrl` |

### Testing strategy
- `OllamaProviderTest.kt` — Tests against a real Ollama instance (requires Ollama running)
- `NativeImageSmokeTest.kt` — Verifies GraalVM native-image compatibility
- Tests use the actual `OllamaProvider` class with no mocking — integration style
