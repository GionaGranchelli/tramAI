# Module: `tramai-azure-openai`

> **One-liner:** Provider for Azure OpenAI — deployment-based endpoints with dual API key / Entra ID authentication.
> **Module type:** `provider`
> **Source files:** 1 — `AzureOpenAiProvider.kt` (401 LOC)
> **Test files:** 1 — `AzureOpenAiProviderTest.kt` (206 LOC)
> **Group:** `dev.tramai`, **Version:** `0.3.1`

---

## L1: Quick Start (30-second read)

### What

`tramai-azure-openai` is a `ModelProvider` + `StreamCapable` implementation that connects Tramai to Azure OpenAI. Unlike the standard OpenAI provider, Azure uses **deployment-based routing** — the endpoint URL is constructed from a resource name and deployment ID rather than routing by model name alone.

### Why

Azure OpenAI is the primary AI platform for organizations operating in Microsoft Azure — including many European government agencies and regulated enterprises. It provides enterprise features (private networking, managed identity, content filtering) that the public OpenAI API does not. This module supports both **API key** authentication and **Entra ID (Azure AD)** bearer tokens via `AzureEntraAccessTokenSource`.

### When to use

- **Azure OpenAI Service** — use `AzureOpenAiProvider(resourceName, deploymentId, apiKey = "...")`
- **Entra ID / Managed Identity** — use `AzureOpenAiProvider(resourceName, deploymentId, entraAccessTokenSource = ...)` with `DefaultAzureCredential`
- **Content-filtered deployments** — Azure's content safety filters are transparent to the provider; filtered responses map to `FinishReason.CONTENT_FILTER`

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-azure-openai:0.4.0")
}
```

**Bill of Materials:**

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.4.0"))
implementation("dev.tramai:tramai-azure-openai")
```

### Where to go next

| If you want to... | Go here |
|---|---|
| Wire a provider into a working app | `docs/modules/tramai-standalone.md` |
| Use Spring Boot auto-configuration | `docs/modules/tramai-spring.md` |
| Understand Entra ID token sources | This page, L3 (Authentication) |

---

## L2: Usage Guide (5-minute read)

### Quick usage (API key)

```kotlin
import dev.tramai.azureopenai.AzureOpenAiProvider
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.standalone.Tramai

@AiService
interface ChatService {
    @Operation(prompt = "Summarize this document", model = "gpt-4o")
    suspend fun summarize(): String
}

suspend fun main() {
    val chat = Tramai
        .builder()
        .provider(
            AzureOpenAiProvider(
                resourceName = "my-azure-resource",
                deploymentId = "gpt-4o-deployment",
                apiKey = System.getenv("AZURE_OPENAI_API_KEY"),
            ),
            default = true,
        )
        .model("gpt-4o", "azure-openai")
        .build()
        .create<ChatService>()

    println(chat.summarize())
}
```

### Entra ID authentication

For managed identity or service principal auth, pass an `AzureEntraAccessTokenSource` instead of an API key:

```kotlin
import dev.tramai.azureopenai.AzureOpenAiProvider
import dev.tramai.azureopenai.AzureEntraAccessTokenSource
import com.azure.identity.DefaultAzureCredentialBuilder

val entraTokenSource = AzureEntraAccessTokenSource {
    val credential = DefaultAzureCredentialBuilder().build()
    val token = credential.getToken(
        TokenRequestContext().addScopes("https://cognitiveservices.azure.com/.default")
    )
    token.token
}

val provider = AzureOpenAiProvider(
    resourceName = "my-azure-resource",
    deploymentId = "gpt-4o-deployment",
    entraAccessTokenSource = entraTokenSource,
)
```

When both `apiKey` and `entraAccessTokenSource` are provided, the API key takes precedence.

### Streaming

Streaming uses Azure's SSE format — identical to OpenAI's, parsed by the provider's `parseAzureSseResponse` method:

```kotlin
import dev.tramai.core.model.StreamChunk
import kotlinx.coroutines.flow.Flow

@AiService
interface StreamingService {
    @Operation(prompt = "Write a haiku", model = "gpt-4o")
    fun stream(): Flow<StreamChunk>
}
```

### Tool calling

Tools are serialized to the OpenAI function-calling format (`type: "function"` wrapper) and parsed from the `message.tool_calls` array:

```kotlin
@AiService
interface ToolService {
    @Operation(prompt = "What's the weather?", model = "gpt-4o", tools = ["get_weather"])
    suspend fun ask(): WeatherResponse
}
```

### Content filtering

Azure OpenAI applies content filters that can cause responses to return `finish_reason: "content_filter"`. TramAI maps this to `FinishReason.CONTENT_FILTER`. Content-filter metadata may also appear in response headers.

### Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `resourceName` | `String` | *(required)* | Azure OpenAI resource name |
| `deploymentId` | `String` | *(required)* | Deployment ID within the resource |
| `apiKey` | `String?` | `null` | API key for key-based auth |
| `apiVersion` | `String` | `"2024-10-21"` | Azure OpenAI API version |
| `entraAccessTokenSource` | `AzureEntraAccessTokenSource?` | `null` | Entra ID bearer token source |
| `httpClient` | `HttpClient` | `HttpClient.newHttpClient()` | Java HTTP client |
| `objectMapper` | `ObjectMapper` | `ObjectMapper()` | Jackson `ObjectMapper` |

---

## L3: Architecture & Mechanics (10-minute read)

### Design philosophy

`tramai-azure-openai` is a **standalone provider** — it does not delegate to `OpenAiCompatibleProvider` because Azure's deployment-based URL construction and dual authentication model diverge from the standard OpenAI transport. The module implements its own HTTP request building, JSON payload construction, SSE streaming parser, and response mapping.

### Endpoint construction

The URL is built from three parameters:

```
https://{resourceName}.openai.azure.com/openai/deployments/{deploymentId}/chat/completions?apiVersion={apiVersion}
```

This deployment-based routing means the model name in `ModelRequest.model` is not used for URL construction — it's passed as the `"model"` field in the JSON payload.

### Authentication

The provider resolves authentication at request time in `resolveAuthToken()`:

1. If `apiKey` is present → use `api-key` HTTP header
2. Otherwise → call `entraAccessTokenSource.accessToken()` and use `Authorization: Bearer` header
3. If neither is available → throw `ConfigurationException`

This means Entra ID tokens can be short-lived and refreshed on every request without reconstructing the provider.

### Inner mechanics

**Non-streaming flow:**

```
1. Build payload: { model, stream: false, messages, tools?, max_tokens?, temperature? }
2. POST to deployment URL with api-key or Bearer auth
3. Parse response: choices[0].message → extract content (textual or array), tool_calls, finish_reason
4. Extract usage: prompt_tokens, completion_tokens, reasoning_tokens (from completion_tokens_details)
5. Return ModelResponse
```

**Streaming flow:**

```
1. Build payload with stream: true
2. POST to deployment URL
3. Consume SSE stream (data: prefix, [DONE] delimiter)
4. Per data line: extract choices[0].delta.content → emit StreamChunk.Token
5. Track usage from data.usage → emit StreamChunk.Complete with final usage
```

### Content extraction

Azure OpenAI may return content as a plain string, an array of content blocks, or nested structures. The `extractContent` method handles all three forms, joining multi-block content with newlines.

### Class hierarchy

```
AzureOpenAiProvider (final)     ← implements ModelProvider, StreamCapable
    │
    └── uses AzureEntraAccessTokenSource (fun interface)

AzureEntraAccessTokenSource          ← fun interface SAM
    └── StaticAzureEntraAccessTokenSource  ← wraps a static token string
```

### Dependency graph

```
tramai-azure-openai
  Depends on:
    - tramai-core (api)        — ModelProvider, ModelRequest, ModelResponse,
                                 StreamCapable, ProviderCapability, ProviderException
    - jackson-databind (impl)  — JSON serialization

  Depended on by:
    - tramai-standalone        — wired via Tramai.builder().provider()
    - tramai-spring            — auto-configuration discovers AzureOpenAiProvider beans
```

### Finish reason mapping

| Azure `finish_reason` | Tramai `FinishReason` |
|---|---|
| `"stop"` | `STOP` |
| `"length"` | `LENGTH` |
| `"tool_calls"` | `STOP` |
| `"content_filter"` | `CONTENT_FILTER` |
| *(anything else)* | `OTHER` |

### Capabilities

All four: `VISION`, `TOOL_CALLING`, `STRUCTURED_OUTPUT`, `STREAMING`.
