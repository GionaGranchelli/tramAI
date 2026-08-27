# Module: `tramai-openai`

> **One-liner:** OpenAI-compatible model provider with support for OpenAI API, Together AI, Groq, vLLM, and any `/chat/completions` endpoint.

---

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Provider adapter for OpenAI and OpenAI-compatible APIs: `OpenAiProvider`, `OpenAiCompatibleProvider`, access-token sources (static, Codex auth file).

### Public entry points

- `OpenAiProvider`, `OpenAiCompatibleProvider` — `ModelProvider` implementations
- `StaticOpenAiAccessTokenSource`, `CodexAuthFileTokenSource` — access-token sources
- `ExperimentalCodexAuth` annotation

Verify against `tramai-openai/api/tramai-openai.api`.

### Internal extension points

- Access-token source seam; OpenAI-compatible base transport (reused by `tramai-deepseek`)

### Significant dependencies

- `api(tramai-core)`; coroutines + Jackson (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Provider owns its HTTP client lifecycle; no engine state ownership

### Thread-safety and concurrency

- Provider must be safe for concurrent invocation by the engine

### Failure semantics

- Provider failures normalized to `ProviderException` per core contracts

### Contract tests / TCKs

- `OpenAiProviderTckTest`, `OpenAiCompatibleProviderTckTest`, `OpenAiStreamingTest` — enrolled in the provider TCK

### Do not

- Do not implement retry/fallback/circuit logic here — the engine owns that

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — provider-adapters layer

## L1: Quick Start (30-second read)

### What

`tramai-openai` is a `ModelProvider` + `StreamCapable` implementation that connects Tramai to any OpenAI-compatible `/chat/completions` REST endpoint. It ships two provider classes in a class-hierarchy:

- **`OpenAiCompatibleProvider`** — generic provider for *any* OpenAI-compatible API (Together AI, Groq, vLLM, Ollama's OpenAI compatibility layer, Azure OpenAI, etc.)
- **`OpenAiProvider`** — subclass pre-configured with `https://api.openai.com/v1` as the default `baseUrl`, plus `organization` and `project` header support for OpenAI's public API

### Why

OpenAI's `/chat/completions` format has become the de facto standard for LLM APIs. Virtually every major inference provider — Together AI, Groq, Fireworks, DeepSeek, vLLM, llama.cpp, Ollama — exposes an OpenAI-compatible endpoint. By targeting this single wire format, `tramai-openai` covers dozens of providers with one module. Separating it from `tramai-core` keeps provider-specific transport and JSON payload code out of the core SPI module.

### When to use

- **OpenAI API** — use `OpenAiProvider(apiKey = "...")` with the public OpenAI endpoint
- **OpenAI-compatible third parties** — use `OpenAiCompatibleProvider.bearerToken(...)` with a custom `baseUrl` (Together AI, Groq, Fireworks, DeepSeek, etc.)
- **Self-hosted vLLM / llama.cpp** — use `OpenAiCompatibleProvider.bearerToken(...)` pointed at your local server
- **Local testing without an API key** — use `OpenAiCompatibleProvider.codexAuth()` to reuse your Codex CLI's ChatGPT OAuth token (experimental)

Don't use this module when you need a non-OpenAI-compatible provider — use `tramai-anthropic` (Claude's Messages API) or `tramai-ollama` (Ollama's native `/api/chat` endpoint) instead.

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-openai")  // version from the TramAI BOM
}
```

**Maven:**

```xml
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-openai</artifactId>
    <version>${tramai.version}</version>
</dependency>
```

**Bill of Materials:**

```kotlin
implementation(platform("dev.tramai:tramai-bom"))  // version from the BOM
implementation("dev.tramai:tramai-openai")
```

### Where to go next

| If you want to... | Go here |
|---|---|
| Wire a provider into a working app | `docs/modules/tramai-standalone.md` |
| Use Spring Boot auto-configuration | `docs/modules/tramai-spring.md` |
| Understand the provider SPI contract | `docs/modules/tramai-core.md` (L3: ModelProvider) |
| Learn about streaming in general | `docs/modules/tramai-engine.md` (L2: Streaming) |
| See the full OpenAI-compatible wire format spec | `docs/specs/spec-003-provider-integration.md` |

---

## L2: Usage Guide (5-minute read)

### Quick usage

The simplest path: create an `OpenAiProvider` with an API key and wire it through `TramaiEngine` or `Tramai` (standalone).

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.openai.OpenAiProvider
import dev.tramai.standalone.Tramai

@AiService
@SystemPrompt("You are a helpful assistant.")
interface ChatService {
    @Operation(prompt = "What is the capital of Japan?", model = "gpt-4o")
    suspend fun ask(): String
}

suspend fun main() {
    val chat = Tramai
        .builder()
        .provider(OpenAiProvider(apiKey = System.getenv("OPENAI_API_KEY")), default = true)
        .build()
        .create<ChatService>()

    println(chat.ask()) // "Tokyo"
}
```

**Using `OpenAiProvider` directly with `TramaiEngine`:**

```kotlin
val provider = OpenAiProvider(
    apiKey = System.getenv("OPENAI_API_KEY"),
    organization = "org-abc123",   // optional: OpenAI organization ID
    project = "proj-def456",       // optional: OpenAI project ID
)
val engine = TramaiEngine(provider)
val service = engine.create<ChatService>()
```

### Advanced usage

#### Custom base URL — Together AI / Groq / vLLM

Any provider that exposes an OpenAI-compatible `/chat/completions` endpoint works via `OpenAiCompatibleProvider`. Set `baseUrl` to the provider's root URL (the provider appends `/chat/completions`):

```kotlin
// Together AI
val together = OpenAiCompatibleProvider.bearerToken(
    bearerToken = System.getenv("TOGETHER_API_KEY"),
    baseUrl = "https://api.together.xyz/v1",
    providerName = "together",
)

// Groq
val groq = OpenAiCompatibleProvider.bearerToken(
    bearerToken = System.getenv("GROQ_API_KEY"),
    baseUrl = "https://api.groq.com/openai/v1",
    providerName = "groq",
)

// Self-hosted vLLM
val vllm = OpenAiCompatibleProvider.bearerToken(
    bearerToken = "not-needed",  // vLLM often doesn't require auth locally
    baseUrl = "http://localhost:8000/v1",
    providerName = "vllm",
)
```

Then register them in the provider registry:

```kotlin
val service = Tramai
    .builder()
    .provider(together, name = "together")
    .provider(groq, name = "groq")
    .model("mixtral-8x22b", "together")
    .model("llama-3.3-70b", "groq")
    .defaultProvider("groq")
    .build()
    .create<MultiModelService>()
```

#### Streaming

Streaming uses SSE (Server-Sent Events) — each `data: {...}` line is parsed and emitted as a `StreamChunk`. The provider implements `StreamCapable`:

```kotlin
import dev.tramai.core.model.StreamChunk
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole
import dev.tramai.core.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking

val provider = OpenAiProvider(apiKey = System.getenv("OPENAI_API_KEY"))

val request = ModelRequest(
    model = "gpt-4o",
    messages = listOf(Message(MessageRole.USER, "Count from 1 to 5")),
)

runBlocking {
    provider.stream(request).collect { chunk ->
        when (chunk) {
            is StreamChunk.Token -> print(chunk.text)
            is StreamChunk.Complete -> println("\n[Done: ${chunk.usage.outputTokens} tokens]")
            is StreamChunk.Error -> System.err.println("\n[Error: ${chunk.cause.message}]")
        }
    }
}
```

Via `@AiService` with a `Flow<StreamChunk>` return type (requires `tramai-engine`):

```kotlin
@AiService
interface StreamingService {
    @Operation(prompt = "Tell me a story", model = "gpt-4o")
    fun stream(): Flow<StreamChunk>
}
```

#### Codex auth (experimental)

For local development, you can reuse the ChatGPT OAuth access token stored by Codex CLI (`~/.codex/auth.json`). This avoids manually managing API keys during testing.

```kotlin
import dev.tramai.openai.OpenAiProvider
import dev.tramai.openai.ExperimentalCodexAuth

@OptIn(ExperimentalCodexAuth::class)
fun localProvider(): OpenAiProvider = OpenAiProvider.codexAuth()
```

The `codexAuth()` factory reads the `auth_mode == "chatgpt"` section of the auth file and extracts `tokens.access_token`. It throws `ConfigurationException` with a clear message if the file is missing, unparseable, or not configured for ChatGPT authentication.

You can also use it with `OpenAiCompatibleProvider` for non-OpenAI endpoints:

```kotlin
@OptIn(ExperimentalCodexAuth::class)
val localCompat = OpenAiCompatibleProvider.codexAuth(
    baseUrl = "http://localhost:8000/v1",
    providerName = "local-vllm",
)
```

#### Tool calling

Tool definitions are passed through `ModelRequest.tools` and serialized to the OpenAI tool-calling format. The response's `tool_calls` array is parsed back into `ToolCall` objects:

```kotlin
val request = ModelRequest(
    model = "gpt-4o",
    messages = listOf(Message(MessageRole.USER, "What's the weather in Paris?")),
    tools = listOf(
        ToolDefinition(
            name = "get_weather",
            description = "Get current temperature for a city",
            inputSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
        ),
    ),
)
val response = provider.complete(request)
response.toolCalls?.forEach { toolCall ->
    println("Call ${toolCall.name} with ${toolCall.argumentsJson}")
}
```

#### Custom HTTP client and ObjectMapper

Both provider constructors accept custom `HttpClient` and `ObjectMapper` instances:

```kotlin
val customClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .build()

val customMapper = JsonMapper.builder()
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    .build()

val provider = OpenAiProvider(
    apiKey = System.getenv("OPENAI_API_KEY"),
    httpClient = customClient,
    objectMapper = customMapper,
)
```

### Expert usage

#### Custom `OpenAiAccessTokenSource`

Beyond the built-in `StaticOpenAiAccessTokenSource` and `CodexAuthFileTokenSource`, you can implement your own token source for dynamic credential resolution:

```kotlin
class VaultTokenSource : OpenAiAccessTokenSource {
    override fun accessToken(): String {
        // Fetch dynamically from HashiCorp Vault, AWS Secrets Manager, etc.
        return fetchSecret("openai/api-key")
    }
}

val provider = OpenAiProvider(
    accessTokenSource = VaultTokenSource(),
)
```

#### Array-based content handling

OpenAI-compatible APIs sometimes return content as an array of content blocks (text, image URLs, etc.) instead of a plain string. The `extractContent` method in `OpenAiCompatibleProvider` handles both forms — it joins text blocks with newlines:

```json
{
  "message": {
    "content": [
      { "type": "text", "text": "Hello" },
      { "type": "text", "text": "World" }
    ]
  }
}
```
→ `"Hello\nWorld"`

### Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `accessTokenSource` | `OpenAiAccessTokenSource` | *(required)* | Source for the `Authorization: Bearer` token |
| `baseUrl` | `String` | `https://api.openai.com/v1` (OpenAiProvider) / *(required)* (OpenAiCompatibleProvider) | Root URL of the OpenAI-compatible API |
| `providerName` | `String` | `"openai"` / `"openai-compatible"` | Value returned by `providerId()` |
| `httpClient` | `HttpClient` | `HttpClient.newHttpClient()` | Java `java.net.http.HttpClient` instance |
| `objectMapper` | `ObjectMapper` | `ObjectMapper()` | Jackson `ObjectMapper` for JSON serialization |
| `organization` | `String?` | `null` | OpenAI-Organization header (OpenAiProvider only) |
| `project` | `String?` | `null` | OpenAI-Project header (OpenAiProvider only) |

**Factory methods on `OpenAiCompatibleProvider`:**

| Method | Token source | Use case |
|--------|-------------|----------|
| `bearerToken(token, baseUrl, ...)` | `StaticOpenAiAccessTokenSource` | Standard API-key providers |
| `codexAuth(baseUrl, ...)` | `CodexAuthFileTokenSource` | Local testing with Codex CLI auth (experimental) |

**Factory methods on `OpenAiProvider` (adds defaults for `baseUrl`):**

| Method | Same as |
|--------|---------|
| `OpenAiProvider(apiKey, ...)` | Constructor — uses `StaticOpenAiAccessTokenSource` |
| `OpenAiProvider.bearerToken(...)` | Same as constructor with explicit bearer token |
| `OpenAiProvider.codexAuth(...)` | Uses `CodexAuthFileTokenSource` with OpenAI's base URL |

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-openai` follows a **hierarchical, composable** design: a single generic base class (`OpenAiCompatibleProvider`) handles all OpenAI-compatible wire formats, and a thin subclass (`OpenAiProvider`) adds OpenAI-specific defaults and headers. There is no duplication between the two — `OpenAiProvider` is purely a configuration specialization.

The authentication model is **strategy-based**: `OpenAiAccessTokenSource` is a `fun interface` (SAM) so any token resolution strategy can be injected. The two built-in implementations (`StaticOpenAiAccessTokenSource` and `CodexAuthFileTokenSource`) cover 95% of use cases, but the SPI is open for custom implementations.

### Module boundary

```
Public API:
  dev.tramai.openai
    OpenAiProvider                    — ModelProvider + StreamCapable (OpenAI-specific)
      constructor(apiKey, baseUrl?, httpClient?, objectMapper?, organization?, project?)
      constructor(accessTokenSource, ...)
      bearerToken(token, ...)         — factory method
      codexAuth(...)                  — factory method (@ExperimentalCodexAuth)
      providerId(): String            — always "openai"

    OpenAiCompatibleProvider          — ModelProvider + StreamCapable (generic)
      constructor(accessTokenSource, baseUrl, providerName?, ...)
      bearerToken(token, baseUrl, ...) — factory method
      codexAuth(baseUrl, ...)         — factory method (@ExperimentalCodexAuth)
      providerId(): String            — configurable via constructor

    OpenAiAccessTokenSource           — fun interface SAM
      fun accessToken(): String

    StaticOpenAiAccessTokenSource     — wraps a static string token
      constructor(token: String)

    CodexAuthFileTokenSource          — reads ~/.codex/auth.json (@ExperimentalCodexAuth)
      constructor(authFile?, objectMapper?)
      defaultAuthFile(): Path         — companion static method

    @ExperimentalCodexAuth            — opt-in annotation (RequiresOptIn)

Package: dev.tramai.openai
  2 source files, no sub-packages.
```

### Class hierarchy

```
OpenAiCompatibleProvider  (open)     ← implements ModelProvider, StreamCapable
    │
    └── OpenAiProvider   (final)     ← pre-configures baseUrl, organization, project

OpenAiAccessTokenSource   (fun interface)
    ├── StaticOpenAiAccessTokenSource
    └── CodexAuthFileTokenSource     ← @ExperimentalCodexAuth
```

### Dependency graph

```
tramai-openai
  Depends on:
    - tramai-core (api)        — ModelProvider, ModelRequest, ModelResponse,
                                 StreamCapable, StreamChunk, ProviderException
    - jackson-databind (impl)  — JSON serialization/deserialization of payloads
    - kotlinx-coroutines-core  — Flow-based streaming via stream()

  Depended on by:
    - tramai-standalone        — wired via Tramai.builder().provider()
    - tramai-spring            — auto-configuration discovers OpenAiProvider beans
```

### Inner mechanics

#### `/chat/completions` endpoint flow (non-streaming)

```
1. User calls service.method() via @AiService proxy
2. Engine builds ModelRequest (model, messages, tools, parameters)
3. Engine resolves provider → calls OpenAiProvider.complete(request)
4. Provider serializes payload on Dispatchers.IO:

   POST {baseUrl}/chat/completions
   Authorization: Bearer <token>
   Content-Type: application/json
   OpenAI-Organization: <org>        (OpenAiProvider only)
   OpenAI-Project: <project>         (OpenAiProvider only)

   {
     "model": "gpt-4o",
     "stream": false,
     "messages": [
       {"role": "system", "content": "You are helpful."},
       {"role": "user", "content": "What is the capital of Japan?"}
     ],
     "tools": [ ... ],               (if tool definitions provided)
     "max_tokens": 200,
     "temperature": 0.7
   }

5. Response parsed:

   {
     "model": "gpt-4o-2024-08-06",
     "choices": [{
       "message": {
         "role": "assistant",
         "content": "Tokyo",
         "tool_calls": [...]         (if model requested tools)
       },
       "finish_reason": "stop"
     }],
     "usage": {
       "prompt_tokens": 25,
       "completion_tokens": 3
     }
   }

6. Provider constructs ModelResponse:
   - content          ← message.content (supports string or array-of-content-blocks)
   - toolCalls        ← message.tool_calls array → List<ToolCall>
   - inputTokens      ← usage.prompt_tokens
   - outputTokens     ← usage.completion_tokens
   - modelUsed        ← response.model
   - finishReason     ← choices[0].finish_reason ("stop"→STOP, "length"→LENGTH,
                       "tool_calls"→STOP, "content_filter"→CONTENT_FILTER, else→OTHER)

7. ModelResponse returned to engine
```

#### Streaming via SSE

For streaming, the provider sends the same request with `"stream": true` and consumes the response via `HttpResponse.BodyHandlers.ofInputStream()`. Successful streams are read through an explicitly owned `bufferedReader(UTF_8).use { reader -> readLine() loop }`, which always closes the response body; non-2xx bodies are read with the bounded `readErrorBodyPreview()` (8 KiB + sentinel byte) and surfaced as a safe `StreamChunk.Error`:

```
1. Provider builds payload with "stream": true
2. HTTP POST to {baseUrl}/chat/completions
3. Response consumed as Flow<StreamChunk> via kotlinx.coroutines.flow.flow { }

   SSE data lines arrive as:
     data: {"choices":[{"delta":{"role":"assistant"}}]}
     data: {"choices":[{"delta":{"content":"Tok"}}]}
     data: {"choices":[{"delta":{"content":"yo"}}]}
     data: {"choices":[{"delta":{"content":""},"finish_reason":"stop"}]}
     data: {"usage":{"prompt_tokens":25,"completion_tokens":3}}
     data: [DONE]

4. Each line parsed:
   - Starts with "data: " → strip prefix
   - "[DONE]" → end of stream
   - Otherwise → parse JSON, extract choices[0].delta.content
   - Non-empty content → emit StreamChunk.Token(text)
   - usage present → capture final UsageMetrics

5. On stream end → emit StreamChunk.Complete(fullText, usage)
6. On parse/transport error → emit StreamChunk.Error(exception)

   All SSE parsing happens on the IO dispatcher.
   The calling collect() runs on whatever coroutine context the caller provides.
```

#### Finish reason mapping

| OpenAI `finish_reason` | Tramai `FinishReason` | Notes |
|---|---|---|
| `"stop"` | `STOP` | Model finished naturally |
| `"length"` | `LENGTH` | Hit `max_tokens` limit |
| `"tool_calls"` | `STOP` | Mapped to STOP for simple orchestration — engine handles tool loop |
| `"content_filter"` | `CONTENT_FILTER` | Content filter flagged output |
| *(anything else)* | `OTHER` | Unknown/unspecified |

#### Authentication

The provider never stores credentials — it delegates to `OpenAiAccessTokenSource.accessToken()` at request time. This means:

- Short-lived tokens (OAuth, STS) can be refreshed dynamically
- Token sources can be swapped without reconstructing the provider
- `CodexAuthFileTokenSource` re-reads the file on every call (safe for rotated tokens)

### Error model

| Exception | Trigger | Retryable | Recovery |
|-----------|---------|-----------|----------|
| `ProviderException` (via `providerHttpFailureObserved`) | Non-2xx HTTP status (4xx, 5xx) | Yes for 408/425/429/500/502/503/504 | Check API key, rate limits, provider health |
| `ProviderException` (via `providerTransportFailureObserved`) | Network timeout, connection refused, IO error | Yes — classified as transient | Verify endpoint reachability and network |
| `ProviderException` | Empty `choices` array in response | No — malformed response | Check API version compatibility |
| `ConfigurationException` | Blank/empty API token | N/A — construction-time | Provide a non-blank token |
| `ConfigurationException` (CodexAuth) | Missing auth file or wrong `auth_mode` | N/A — construction-time | Run Codex CLI login or use a different token source |

**HTTP error handling details:**

- The `Retry-After` header is parsed from non-2xx responses and exposed via `error.retryAfterMillis` so the engine can honor provider-side rate-limiting guidance.
- The public exception message is fixed to `Provider request failed with HTTP {status}` and has no cause; `failureCode` is `HTTP_REJECTED`.
- Response bodies are excluded from public exceptions, standard logs, and telemetry. An explicitly configured `ProviderFailureDiagnosticObserver` is the only diagnostic channel and receives a preview capped at 8 KiB with a truncation flag.
- Debug logging records metadata only: failure code, status, and retryability (no provider id or alias; the logger channel identifies the provider).
- Transport failures likewise use fixed cause-free messages and typed codes. The original timeout, connection, I/O, or unexpected throwable is available only to the diagnostic observer.

### Testing strategy

- **`OpenAiProviderTest.kt`** — Uses a local `HttpServer` (com.sun.net.httpserver) to simulate an OpenAI-compatible endpoint without network calls. Covers:
  - API key authentication headers (Authorization, OpenAI-Organization, OpenAI-Project)
  - Request body serialization (model, messages, max_tokens, temperature)
  - Response parsing (content, token usage, finish reason, model name)
  - Array-based content blocks (for multi-modal / content-list responses)
  - OpenAiCompatibleProvider.bearerToken() factory
  - HTTP 429 rate-limiting with Retry-After header parsing
  - Secret-bearing HTTP and malformed-JSON transport failures remain out of public exceptions while diagnostics retain bounded/original detail
  - Empty choices array error handling
  - CodexAuthFileTokenSource: successful token reading
  - CodexAuthFileTokenSource: misconfigured auth file (`auth_mode != "chatgpt"`)

- **`OpenAiProviderIntegrationTest.kt`** — Integration test against a real OpenAI endpoint (requires `TRAMAI_OPENAI_API_KEY` environment variable or uses `bearerToken` fallback).
