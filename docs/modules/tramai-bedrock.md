# Module: `tramai-bedrock`

> **One-liner:** Provider for Amazon Bedrock using the InvokeModel API — translates TramAI's unified message model to the Claude (Anthropic) format.
> **Module type:** `provider`
> **Source files:** 1 — `BedrockProvider.kt` (323 LOC)
> **Test files:** 1 — `BedrockProviderTest.kt` (121 LOC)
> **Group:** `dev.tramai`, **Version:** `0.3.1`

---

## L1: Quick Start (30-second read)

### What

`tramai-bedrock` is a `ModelProvider` + `StreamCapable` implementation that connects Tramai to Amazon Bedrock via the `InvokeModel` API. It translates TramAI's unified message model into the Claude Messages format (Anthropic) and back.

### Why

Amazon Bedrock is AWS's managed AI service, providing access to Claude, Llama, and other foundation models through a single API with AWS IAM authentication, VPC support, and enterprise governance. For European organizations already on AWS, Bedrock eliminates the need for separate API keys and provides data residency guarantees through region selection.

### When to use

- **AWS-native deployments** — use `BedrockProvider(region = "eu-west-1")` for European data residency
- **Claude via Bedrock** — the default model is `anthropic.claude-3-sonnet-20240229-v1:0`
- **IAM-based auth** — no API key management; authenticates via the AWS credentials chain

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-bedrock:0.5.0")
}
```

**Bill of Materials:**

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.5.0"))
implementation("dev.tramai:tramai-bedrock")
```

### Where to go next

| If you want to... | Go here |
|---|---|
| Wire a provider into a working app | `docs/modules/tramai-standalone.md` |
| Use Spring Boot auto-configuration | `docs/modules/tramai-spring.md` |
| Understand the Anthropic message format | `docs/modules/tramai-anthropic.md` (L3) |

---

## L2: Usage Guide (5-minute read)

### Quick usage

```kotlin
import dev.tramai.bedrock.BedrockProvider
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.standalone.Tramai

@AiService
interface ChatService {
    @Operation(prompt = "What is GDPR?", model = "anthropic.claude-3-sonnet-20240229-v1:0")
    suspend fun explain(): String
}

suspend fun main() {
    val chat = Tramai
        .builder()
        .provider(
            BedrockProvider(region = "eu-west-1"),
            default = true,
        )
        .model("anthropic.claude-3-sonnet-20240229-v1:0", "bedrock")
        .build()
        .create<ChatService>()

    println(chat.explain())
}
```

### Authentication

By default, `BedrockProvider` uses `DefaultCredentialsProvider.create()`, which resolves credentials from:
- Environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`)
- `~/.aws/credentials`
- IAM roles (ECS task role, EC2 instance profile)

For custom credential providers:

```kotlin
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials

val provider = BedrockProvider(
    region = "eu-west-1",
    credentialsProvider = StaticCredentialsProvider.create(
        AwsBasicCredentials.create("access-key", "secret-key")
    ),
)
```

### Streaming

```kotlin
@AiService
interface StreamingService {
    @Operation(prompt = "Write a poem", model = "anthropic.claude-3-sonnet-20240229-v1:0")
    fun stream(): Flow<StreamChunk>
}
```

For Bedrock, streaming uses the same `InvokeModel` endpoint — the response body is read and emitted as tokens through a `Flow`.

### Tool calling

The provider translates TramAI tool definitions to the Claude `tools` format and parses `tool_use` content blocks from the response:

```kotlin
@AiService
interface ToolService {
    @Operation(prompt = "Calculate VAT", model = "anthropic.claude-3-sonnet-20240229-v1:0", tools = ["vat_calculator"])
    suspend fun calculate(): VatResponse
}
```

### Vision / Multimodal

The provider supports image inputs via `ContentPart.ImagePart` and `ContentPart.ImageUrlContent`, translating them to Claude's `image` content blocks with base64 encoding. Supported image types: `image/png`, `image/jpeg`, `image/gif`, `image/webp`.

### Configuration reference

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `region` | `String` | *(required)* | AWS region (e.g., `"eu-west-1"`) |
| `modelId` | `String` | `"anthropic.claude-3-sonnet-20240229-v1:0"` | Default Bedrock model ID |
| `credentialsProvider` | `AwsCredentialsProvider` | `DefaultCredentialsProvider.create()` | AWS credentials |
| `objectMapper` | `ObjectMapper` | `ObjectMapper()` | Jackson `ObjectMapper` |

---

## L3: Architecture & Mechanics (10-minute read)

### Design philosophy

`tramai-bedrock` is a **standalone provider** — it implements its own transport layer using the AWS SDK (`BedrockRuntimeClient`) rather than delegating to `OpenAiCompatibleProvider`. This is necessary because Bedrock uses the `InvokeModel` API with model-specific payloads, not the OpenAI `/chat/completions` format.

### Payload translation

The provider translates TramAI's unified message model to the Claude Messages format:

| TramAI | Claude (Bedrock) |
|---|---|
| `MessageRole.SYSTEM` | Top-level `system` field |
| `MessageRole.USER` | `role: "user"` |
| `MessageRole.ASSISTANT` | `role: "assistant"` |
| `MessageRole.TOOL` | `role: "user"` with `tool_result` content block |
| `ToolDefinition` | `tools[{name, description, input_schema}]` |
| `ToolCall` | `tool_use` content block with `id`, `name`, `input` |

### Inner mechanics

**Non-streaming flow:**

```
1. Build Claude payload: { anthropic_version, max_tokens, messages, system?, tools?, temperature? }
2. Wrap in InvokeModelRequest with modelId and contentType: "application/json"
3. Call bedrockRuntimeClient.invokeModel()
4. Parse response body: content[] → extract text blocks and tool_use blocks
5. Map stop_reason → FinishReason (end_turn→STOP, max_tokens→LENGTH, tool_use→STOP, content_filtered→CONTENT_FILTER)
6. Return ModelResponse with usage (input_tokens, output_tokens)
```

**Streaming flow:**

```
1. Build same Claude payload
2. InvokeModel — read entire response body
3. Extract text from content[] blocks
4. Emit StreamChunk.Token(fullText) followed by StreamChunk.Complete
```

### Authentication model

Authentication uses AWS Signature V4 via the `AwsCredentialsProvider` interface. The default is the standard AWS credential provider chain — no API keys needed for IAM-role-based deployments.

### Finish reason mapping

| Claude `stop_reason` | Tramai `FinishReason` |
|---|---|
| `"end_turn"` | `STOP` |
| `"max_tokens"` | `LENGTH` |
| `"tool_use"` | `STOP` |
| `"content_filtered"` | `CONTENT_FILTER` |
| `"stop_sequence"` | `STOP` |
| *(anything else)* | `OTHER` |

### Class hierarchy

```
BedrockProvider (final)     ← implements ModelProvider, StreamCapable
    │
    └── uses BedrockRuntimeClient (AWS SDK)
        └── authenticated via AwsCredentialsProvider
```

### Dependency graph

```
tramai-bedrock
  Depends on:
    - tramai-core (api)        — ModelProvider, ModelRequest, ModelResponse,
                                 StreamCapable, ProviderCapability
    - aws-bedrockruntime (impl) — InvokeModel API
    - aws-auth (impl)          — credentials chain
    - jackson-databind (impl)  — JSON payload construction

  Depended on by:
    - tramai-standalone        — wired via Tramai.builder().provider()
    - tramai-spring            — auto-configuration discovers BedrockProvider beans
```

### Capabilities

All four: `VISION`, `TOOL_CALLING`, `STRUCTURED_OUTPUT`, `STREAMING`.
