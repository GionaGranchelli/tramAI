# SPEC-019: Multimodal / Vision Support

- Status: implemented
- Date: 2026-05-14
- Related: [SPEC-003 Provider Integration](./spec-003-provider-integration.md)
- Related ADR: [ADR-010: Multimodal Content Model](../adr/adr-010.md)

---

## 1. Executive Summary

TramAI now fully supports multimodal (image/vision) content across all 7 provider modules. Users can include images in LLM messages, pass images by URL or as raw bytes, control image detail level, check provider capabilities, and get image-aware token estimates.

---

## 2. Architecture

### 2.1 Content Model

```
ContentPart (sealed interface)
├── TextPart(text: String)           -- Plain text content block
├── ImagePart(mimeType, ByteArray)   -- Image as raw bytes (defensive copy)
└── ImageUrlContent(url, mimeType?)  -- Image referenced by URL
```

`Message` carries content parts via `contentParts: List<ContentPart>?` alongside the existing `content: String` field. The message init guard enforces mutual exclusivity: when `contentParts` is non-null, `content` must be blank.

### 2.2 Provider Image Serialization

| Provider | ImagePart (bytes) | ImageUrlContent (URL) |
|----------|-------------------|----------------------|
| **OpenAI** | `image_url` with `data:...;base64,...` | `image_url` with `url` directly |
| **Anthropic** | Content block `type: "image"` + `source.base64` | Downloads URL, same format |
| **Gemini** | `inlineData { mimeType, data }` | Downloads URL, same format |
| **Ollama** | `images: [base64...]` array | Downloads URL, same format |
| **Bedrock** | Content block `type: "image"` + `source.base64` | Downloads URL, same format |
| **Azure OpenAI** | Same as OpenAI | Same as OpenAI |
| **DeepSeek** | Through OpenAiCompatibleProvider | Through OpenAiCompatibleProvider |

Providers that accept URLs natively (OpenAI, Gemini via inlineData) pass them directly. Providers that require base64 (Anthropic, Ollama, Bedrock) upload via [`ImageDownloader`](#23-imagedownloader).

### 2.3 ImageDownloader

`ImageDownloader` in `dev.tramai.core.util` downloads images from URLs to byte arrays. Configuration:
- Max download size: 20 MB
- Connect timeout: 10 seconds
- Read timeout: 30 seconds
- MIME detection: URL extension-based (`.jpg`/`.jpeg`→`image/jpeg`, `.png`→`image/png`, `.webp`→`image/webp`, `.gif`→`image/gif`)
- Query parameters and URL fragments are stripped before extension matching

### 2.4 Tool Result Support

`ToolResult.Success` has an optional `contentParts: List<ContentPart>?` field (default `null`). When a tool returns content parts (e.g., generated images), the engine creates a `Message(role=TOOL)` with:
- `content = value?.toString() ?: ""`
- `contentParts = TextPart(text) + tool.contentParts`

This ensures images returned by tools are visible to the LLM in subsequent ReAct loop iterations.

### 2.5 Provider Capabilities

`ProviderCapability` enum defines capabilities providers may support:
- `VISION` — image/multimodal content
- `TOOL_CALLING` — function/tool calling
- `STRUCTURED_OUTPUT` — JSON-structured responses
- `STREAMING` — streaming responses

All major providers declare `VISION = true`. The engine checks `supportsCapability(VISION)` before sending images and throws `ProviderCapabilityException` if the provider doesn't support vision.

### 2.6 Image Detail

`ImageDetail` enum (LOW, HIGH, AUTO) on `ModelRequest.imageDetail` controls image resolution sent to providers. OpenAI wires `"detail"` to each `image_url` block. Default is `AUTO` (provider decides).

### 2.7 Token Counting

`UsageMetrics` now includes:
- `imageCount: Int` — number of images in the request
- `imageTokensEstimate: Int` — estimated tokens consumed by images

`Message.estimateImageTokens(imageDetail)` uses the OpenAI formula: 170 tokens per 224px tile (1 tile per image when dimensions are unknown), 85 tokens for LOW detail.

---

## 3. API

### 3.1 Constructing Messages with Images

```kotlin
// Image from raw bytes
val bytes = java.io.File("screenshot.png").readBytes()
Message(
    role = MessageRole.USER,
    content = "",  // must be blank when contentParts is set
    contentParts = listOf(
        ContentPart.TextPart("What's in this screenshot?"),
        ContentPart.ImagePart("image/png", bytes),
    ),
)

// Image from URL
Message(
    role = MessageRole.USER,
    content = "",
    contentParts = listOf(
        ContentPart.TextPart("Analyze this diagram"),
        ContentPart.ImageUrlContent("https://example.com/diagram.png"),
    ),
)
```

### 3.2 Checking Provider Capability

```kotlin
if (provider.supportsCapability(ProviderCapability.VISION)) {
    // Safe to send images
} else {
    // Fall back to text-only or OCR
}
```

### 3.3 Controlling Image Detail

```kotlin
val request = ModelRequest(
    model = "gpt-4o",
    messages = listOf(userMessage),
    imageDetail = ImageDetail.LOW,  // cheaper, 85 tokens per image
)
```

### 3.4 Estimating Image Tokens

```kotlin
val tokens = message.estimateImageTokens(ImageDetail.HIGH)
// Returns 170 * imageCount (approximate)
```

### 3.5 Returning Images from Tools

```kotlin
override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
    val imageFile = generateImage(input)
    return ToolResult.Success(
        value = "Generated image saved to ${imageFile.absolutePath}",
        contentParts = listOf(
            ContentPart.ImageUrlContent("file://${imageFile.absolutePath}"),
        ),
    )
}
```

---

## 4. Migration Guide

No migration needed. All existing code continues to work unchanged:

- `Message(role, "text")` — creates text-only message, no change
- `ToolResult.Success(value)` — creates text-only result, no change
- `ModelRequest(model, messages)` — imageDetail defaults to AUTO
- `UsageMetrics()` — imageCount/imageTokensEstimate default to 0

---

## 5. Implementation Details

### 5.1 Files Modified

| Module | File | Change |
|--------|------|--------|
| tramai-core | ContentPart.kt | Added `ImageUrlContent` sealed variant, `isImage()` companion |
| tramai-core | Message.kt | Updated `hasImage()`, added `estimateImageTokens()` |
| tramai-core | ToolResult.kt | Added `contentParts` to `Success` |
| tramai-core | ModelRequest.kt | Added `ImageDetail` enum, `imageDetail` field |
| tramai-core | ModelProvider.kt | Added `ProviderCapability` enum, `supportsCapability()` |
| tramai-core | Streaming.kt | Added `imageCount`, `imageTokensEstimate` to UsageMetrics |
| tramai-core | ImageDownloader.kt | NEW — URL download utility |

### 5.2 Provider Modules

| Module | Change |
|--------|--------|
| tramai-openai | ImageUrlContent handler + ImageDetail wire-up + supportsCapability |
| tramai-anthropic | ImageUrlContent handler + supportsCapability |
| tramai-gemini | ImageUrlContent handler + supportsCapability |
| tramai-ollama | ImageUrlContent handler + supportsCapability |
| tramai-bedrock | ImageUrlContent handler + supportsCapability |
| tramai-azure-openai | ImageUrlContent handler + supportsCapability |
| tramai-deepseek | supportsCapability |

### 5.3 Engine

| Module | File | Change |
|--------|------|--------|
| tramai-engine | TramaiEngine.kt | Tool result contentParts wire-up + VISION capability check |

### 5.4 Test Coverage

| Module | Tests |
|--------|-------|
| tramai-core | ImageDownloaderTest (12) |
| tramai-ollama | OllamaProviderTest (2 new image tests) |
| tramai-bedrock | BedrockProviderTest (6, first-ever test file) |
| tramai-azure-openai | AzureOpenAiProviderTest (10, first-ever test file) |
| tramai-openai | Existing image test verified |
| tramai-anthropic | Existing image test verified |
| tramai-gemini | Existing image test verified |

---

## 6. Future Work

| Priority | Feature | Notes |
|----------|---------|-------|
| P2 | Image dimensions parsing for accurate token counting | Parse PNG/JPEG headers for actual image dimensions |
| P3 | Streaming vision annotations | Bounding boxes / coordinates in StreamChunk |
| P3 | Gemini native fileData support | Upload to Google File API for large images |
