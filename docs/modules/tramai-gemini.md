# Module: `tramai-gemini`

> **One-liner:** Provider for Google Gemini API — translates TramAI's unified message model to Gemini's `generateContent` / `streamGenerateContent` endpoints.

---

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Provider adapter for Google Gemini API: `ModelProvider` implementation.

### Public entry points

- `GeminiProvider` — `ModelProvider` implementation (verify against `tramai-gemini/api/tramai-gemini.api`)

### Internal extension points

- Provider transport internals (HTTP client, JSON mapping)

### Significant dependencies

- `api(tramai-core)`; coroutines + Jackson (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Provider retains an injected/default `HttpClient` (constructor default `HttpClient.newHttpClient()`) and exposes no close contract; no engine state ownership

### Thread-safety and concurrency

- Provider must be safe for concurrent invocation by the engine

### Failure semantics

- Provider failures normalized to `ProviderException` per core contracts

### Contract tests / TCKs

- `GeminiProviderTckTest` — enrolled in the provider TCK

### Do not

- Do not implement retry/fallback/circuit logic here — the engine owns that

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — provider-adapters layer

## L1: Quick Start (30-second read)

### What

`tramai-gemini` is a `ModelProvider` + `StreamCapable` implementation that connects Tramai to Google's Gemini API. It translates TramAI's unified message model to Gemini's `contents[]` / `parts[]` format and maps Gemini's response structure back to `ModelResponse`.

### Why

Gemini offers competitive pricing, a generous free tier, large context windows, and multimodal capabilities (text, image, audio, video). The API format is structurally different from OpenAI's — Gemini uses a `contents[{role, parts[{text/inlineData/functionCall}]}]` structure rather than `messages[{role, content}]`. This module handles that translation while exposing the same `ModelProvider` SPI as every other TramAI provider.

### When to use

- **Gemini Flash** — use model `"gemini-2.0-flash"` (default) for fast, cost-effective inference
- **Gemini Pro** — use `"gemini-2.0-pro"` for complex reasoning tasks
- **Multimodal** — pass images via `ContentPart.ImagePart` or `ContentPart.ImageUrlContent`
- **Structured output** — pass a `responseSchema` to enable Gemini's native JSON mode

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-gemini")  // version from the TramAI BOM
}
```

**Bill of Materials:**

```kotlin
val tramaiVersion: String by project
    implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))
implementation("dev.tramai:tramai-gemini")
```

### Where to go next

| If you want to... | Go here |
|---|---|
| Wire a provider into a working app | `docs/modules/tramai-standalone.md` |
| Use Spring Boot auto-configuration | `docs/modules/tramai-spring.md` |
| Understand structured output in general | `docs/modules/tramai-structured.md` |

---

## L2: Usage Guide (5-minute read)

### Quick usage

```kotlin
import dev.tramai.gemini.GeminiProvider
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.standalone.Tramai

@AiService
interface ChatService {
    @Operation(prompt = "Explain the NIS2 directive", model = "gemini-2.0-flash")
    suspend fun explain(): String
}

suspend fun main() {
    val chat = Tramai
        .builder()
        .provider(
            GeminiProvider(apiKey = System.getenv("GEMINI_API_KEY")),
            default = true,
        )
        .model("gemini-2.0-flash", "gemini")
        .build()
        .create<ChatService>()

    println(chat.explain())
}
```

### Streaming

Streaming uses Gemini's `streamGenerateContent` endpoint with SSE (`alt=sse`):

```kotlin
@AiService
interface StreamingService {
    @Operation(prompt = "Write a sonnet", model = "gemini-2.0-flash")
    fun stream(): Flow<StreamChunk>
}
```

### Tool calling

Tool definitions are wrapped in Gemini's `function_declarations` array. Function calls in responses are parsed from `candidates[].content.parts[]` as `functionCall` blocks:

```kotlin
@AiService
interface ToolService {
    @Operation(prompt = "Calculate the VAT for €100", model = "gemini-2.0-flash", tools = ["vat_calculator"])
    suspend fun calculate(): VatResponse
}
```

### Structured output (JSON mode)

Pass a JSON schema string to enable Gemini's native controlled generation:

```kotlin
val provider = GeminiProvider(
    apiKey = System.getenv("GEMINI_API_KEY"),
    responseSchema = """{"type":"object","properties":{"name":{"type":"string"}}}""",
)
```

When `responseSchema` is set, the provider adds `responseMimeType: "application/json"` and `responseSchema` to `generationConfig`.

### Vision / Multimodal

```kotlin
import dev.tramai.core.model.ContentPart
import dev.tramai.core.model.Message
import dev.tramai.core.model.MessageRole

val imagePart = ContentPart.ImagePart(
    mimeType = "image/png",
    data = Files.readAllBytes(Path.of("document.png")),
)
val message = Message(
    role = MessageRole.USER,
    contentParts = listOf(
        ContentPart.TextPart("Extract the text from this document"),
        imagePart,
    ),
)
```

Images are encoded as `inlineData { mimeType, data }` within Gemini's `parts[]` array.

### Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `apiKey` | `String` | *(required)* | Gemini API key |
| `baseUrl` | `String` | `https://generativelanguage.googleapis.com` | API base URL |
| `responseSchema` | `String?` | `null` | JSON schema for structured output mode |
| `apiVersion` | `String` | `"v1beta"` | API version (`v1` or `v1beta`) |
| `httpClient` | `HttpClient` | `HttpClient.newHttpClient()` | Java HTTP client |
| `objectMapper` | `ObjectMapper` | `ObjectMapper()` | Jackson `ObjectMapper` |

---

## L3: Architecture & Mechanics (10-minute read)

### Design philosophy

`tramai-gemini` is a **standalone provider** — it implements its own transport, payload translation, and response mapping because Gemini's API structure diverges significantly from the OpenAI `/chat/completions` format. The module maps every TramAI concept to its Gemini equivalent.

### Concept mapping

| TramAI Concept | Gemini API Equivalent |
|---|---|
| `ModelRequest.model` | `models/{model}:generateContent` |
| SYSTEM message | `system_instruction` field |
| USER message | `contents[{role: "user"}]` |
| ASSISTANT message | `contents[{role: "model"}]` |
| TOOL message | `contents[{role: "function"}]` with `functionResponse` |
| `ToolDefinition` | `tools[{function_declarations[...]}]` |
| Structured output | `generationConfig.responseMimeType` + `responseSchema` |
| Streaming | `streamGenerateContent?alt=sse` |
| ImagePart | `inlineData { mimeType, data }` within content parts |

### Inner mechanics

**Non-streaming flow:**

```
1. Build payload:
   - Map messages → contents[{role, parts[{text/inlineData/functionResponse}]}]
   - Extract system message → system_instruction
   - Map tools → tools[{function_declarations}]
   - Build generationConfig (maxOutputTokens, temperature, responseSchema)
2. POST to /v1beta/models/{model}:generateContent with X-Goog-Api-Key header
3. Parse response: candidates[0].content.parts[] →
   - Extract text from parts[].text
   - Extract function calls from parts[].functionCall
4. Map finishReason (STOP→STOP, MAX_TOKENS→LENGTH, SAFETY/RECITATION→CONTENT_FILTER)
5. Extract usageMetadata (promptTokenCount, candidatesTokenCount)
6. Return ModelResponse
```

**Streaming flow:**

```
1. Build same payload
2. POST to .../streamGenerateContent?alt=sse
3. Consume SSE stream (data: prefix)
4. Per line: extract candidates[0].content.parts[].text → emit StreamChunk.Token
5. Track usageMetadata → emit StreamChunk.Complete
```

### Finishing reason mapping

| Gemini `finishReason` | Tramai `FinishReason` |
|---|---|
| `"STOP"` | `STOP` |
| `"MAX_TOKENS"` | `LENGTH` |
| `"SAFETY"` | `CONTENT_FILTER` |
| `"RECITATION"` | `CONTENT_FILTER` |
| *(anything else)* | `OTHER` |

### Safety warnings

Gemini responses may contain parts that are neither `text` nor `functionCall`. The provider logs a `WARNING` for such parts and skips them, rather than silently dropping them or crashing.

### Class hierarchy

```
GeminiProvider (final)     ← implements ModelProvider, StreamCapable
```

### Dependency graph

```
tramai-gemini
  Depends on:
    - tramai-core (api)        — ModelProvider, ModelRequest, ModelResponse,
                                 StreamCapable, ProviderCapability
    - jackson-databind (impl)  — JSON serialization

  Depended on by:
    - tramai-standalone        — wired via Tramai.builder().provider()
    - tramai-spring            — auto-configuration discovers GeminiProvider beans
```

### Capabilities

All four: `VISION`, `TOOL_CALLING`, `STRUCTURED_OUTPUT`, `STREAMING`.
