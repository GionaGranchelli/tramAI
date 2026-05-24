# Module: `tramai-core`

> **One-liner:** Annotations, data models, provider SPI, structured-output contracts, and exceptions for the Tramai AI library.
> **Module type:** `core`
> **Group:** `dev.tramai`, **Version:** `0.3.1`
> **Source files:** 26 (across 12 packages), **LOC:** 1,074

---

## L1: Quick Start (30-second read)

### What

`tramai-core` is the foundational module of the Tramai ecosystem. It defines the **contracts** that every other module — engine, structured output, providers, orchestration, framework adapters — builds on. It contains no transport, no execution engine, and no runtime wiring. It is the shared vocabulary of the platform.

### Why

Without `tramai-core`, no two Tramai modules could communicate. It provides:

- **Annotations** (`@AiService`, `@Operation`, `@AiTool`, `@SystemPrompt`, `@AiDescription`, etc.) that let you declare AI-backed interfaces in pure Kotlin
- **Data models** (`Message`, `ModelRequest`, `ModelResponse`, `StreamChunk`, `ToolCall`, `ToolResult`) that normalize provider interactions into a unified representation
- **Provider SPI** (`ModelProvider`, `ProviderRegistry`, `StreamCapable`) that lets transport modules plug in without coupling to each other
- **Structured output contracts** (`StructuredOutputContract`, `StructuredOutputHandler`, `StructuredOutputResult`) that make typed, validated responses a first-class concept
- **Observation & interceptor interfaces** (`OperationObserver`, `OperationInterceptor`) for non-invasive monitoring, PII redaction, and security auditing
- **Exception hierarchy** (`TramaiException`, `ProviderException`, `StructuredOutputException`, etc.) with structured context for debugging and retry logic
- **Infrastructure types** (`SecretValueResolver`, `NativeImageProxyConfig`) for credential resolution and GraalVM native-image support

### When to use

- **Always** — every Tramai application depends on `tramai-core`, either directly or transitively through `tramai-engine`, `tramai-standalone`, or `tramai-spring`.
- **Directly** — when writing custom `ModelProvider` implementations, custom `SecretValueResolver` strategies, custom `OperationInterceptor` implementations, or custom tool implementations via `TramaiTool`.
- **Never alone** — `tramai-core` has no execution engine. Pair it with `tramai-engine` for proxy-based invocation, `tramai-structured` for typed outputs, and a provider module (`tramai-openai`, `tramai-anthropic`, `tramai-ollama`) for actual model calls.

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-core:0.3.1")
}
```

**Maven:**

```xml
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-core</artifactId>
    <version>0.3.1</version>
</dependency>
```

### Where to go next

| If you want to... | Go here |
|---|---|
| Execute an `@AiService` interface | `tramai-engine` |
| Parse and validate typed responses | `tramai-structured` |
| Call OpenAI models | `tramai-openai` |
| Call Anthropic models | `tramai-anthropic` |
| Call Ollama models | `tramai-ollama` |
| Get a framework-free entry point | `tramai-standalone` |
| Use Spring Boot auto-configuration | `tramai-spring` |
| Write tests with mock providers | `tramai-testing` |

---

## L2: Usage Guide (5-minute read)

### Quick usage

The most common way to use `tramai-core` types is through an `@AiService` interface. The following minimal example shows all core annotations in action:

```kotlin
import dev.tramai.core.annotations.*
import dev.tramai.core.model.*

// --- 1. Define a structured output type ---
data class Weather(
    @AiDescription("Temperature in Celsius")
    val temperature: Double,
    @AiDescription("Weather condition description")
    val condition: String,
    @AiRange(min = 0.0, max = 100.0)
    val humidity: Double,
)

// --- 2. Define a tool that the model can call ---
data class GetTimeInput(val timezone: String)

class GetTimeTool : TramaiTool<GetTimeInput, String> {
    override val name = "get_time"
    override val description = "Returns the current time for a given timezone"
    override val inputType = GetTimeInput::class

    override suspend fun execute(input: GetTimeInput, context: ToolExecutionContext): String {
        return "Current time in ${input.timezone} is 12:00 UTC"
    }
}

// --- 3. Define the AI service interface ---
@AiService
@SystemPrompt("You are a helpful weather assistant.")
interface WeatherService {

    @Operation(
        prompt = "What is the weather in {city}?",
        model = "gpt-4o",
        tools = ["get_time"],
        timeoutMillis = 15_000,
    )
    suspend fun getWeather(city: String): Weather

    @Operation(
        prompt = "Summarize the weather for the next week.",
        model = "gpt-4o-mini",
        cacheable = true,
    )
    suspend fun summarizeForecast(data: String): String
}
```

The `@AiService` annotation marks the interface for proxy generation. The `@SystemPrompt` annotation provides a system-level prompt prepended to every operation. Each `@Operation` defines a single AI-backed method with its prompt, model, optional tools, timeout, and retry settings.

### Advanced usage

#### Custom `ModelProvider` implementation

To integrate a custom AI provider, implement the `ModelProvider` interface:

```kotlin
import dev.tramai.core.model.*
import dev.tramai.core.provider.*

class CustomProvider(
    private val apiKey: String,
) : ModelProvider {
    override suspend fun complete(request: ModelRequest): ModelResponse {
        // Translate ModelRequest -> your API format
        // Call your API
        // Translate response -> ModelResponse
        return ModelResponse(
            content = "Processed: ${request.messages.lastOrNull()?.content ?: ""}",
            inputTokens = 10,
            outputTokens = 5,
            modelUsed = "custom-model",
        )
    }

    override fun providerId(): String = "custom"
}
```

If your provider supports streaming, implement `StreamCapable`:

```kotlin
import dev.tramai.core.model.*
import dev.tramai.core.provider.*
import kotlinx.coroutines.flow.*

class CustomStreamingProvider : ModelProvider, StreamCapable {
    override suspend fun complete(request: ModelRequest): ModelResponse {
        return ModelResponse(content = "Hello!")
    }

    override suspend fun stream(request: ModelRequest): Flow<StreamChunk> = flow {
        emit(StreamChunk.Token("Hello"))
        emit(StreamChunk.Token(" world"))
        emit(StreamChunk.Complete(fullText = "Hello world"))
    }
}
```

#### Manual provider registry

The `ProviderRegistry` routes `@Operation` model names to concrete `ModelProvider` instances, with fallback support:

```kotlin
import dev.tramai.core.provider.*

val registry = ProviderRegistry.builder()
    .provider("openai", openAiProvider, default = true)
    .provider("anthropic", anthropicProvider)
    .model("gpt-4o", "openai")
    .model("claude-3-opus", "anthropic")
    .fallbackProvider("gpt-4o", "anthropic") // falls back to Anthropic if OpenAI fails
    .build()
```

Resolution order:
1. Explicit `@Operation(provider = "...")` — highest priority
2. Registered model-to-provider mapping
3. Default provider (set via `provider(..., default = true)` or `defaultProvider(...)`)
4. Throws `ConfigurationException` if no route is found

#### Custom `OperationInterceptor`

Interceptors can inspect or modify request messages and provider responses, useful for PII redaction:

```kotlin
import dev.tramai.core.observation.*

class PiiRedactionInterceptor : OperationInterceptor {
    override fun interceptRequest(
        context: OperationCallContext,
        messages: List<Message>,
    ): List<Message> {
        return messages.map { msg ->
            msg.copy(
                content = msg.content.replace(Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"), "***-**-****")
            )
        }
    }
}
```

#### Secret resolution

Credentials can be resolved dynamically via `SecretValueResolver`. Built-in resolvers support `env:` and `file:` schemes:

```kotlin
import dev.tramai.core.secret.*

val resolver = CompositeSecretValueResolver(listOf(
    EnvironmentSecretValueResolver,
    FileSecretValueResolver,
))

val apiKey = resolver.resolve("env:OPENAI_API_KEY")      // from environment variable
val apiKey2 = resolver.resolve("file:/etc/secrets/key")  // from local file
```

### Expert usage

#### `StructuredOutputHandler` — custom parsing and validation

The `StructuredOutputHandler` SPI lets you plug in your own schema generation, parsing, and validation strategy. The reference implementation lives in `tramai-structured` (Jackson-based).

```kotlin
import dev.tramai.core.structured.*
import kotlin.reflect.KType

class CustomSchemaHandler : StructuredOutputHandler {
    override fun createContract(targetType: KType): StructuredOutputContract {
        val schema = generateSchema(targetType)
        return StructuredOutputContract(
            targetType = targetType,
            schemaJson = schema,
        )
    }

    override fun analyze(rawResponse: String, targetType: KType): StructuredOutputResult {
        return try {
            val value = deserialize(parseJson(rawResponse), targetType)
            StructuredOutputResult.Success(value, rawResponse)
        } catch (e: Exception) {
            StructuredOutputResult.Failure(
                rawResponse = rawResponse,
                errorSummary = "Parse failed: ${e.message}",
                feedbackMessage = "Please respond with valid JSON matching the expected schema.",
            )
        }
    }

    override fun generateSchema(type: KType): String = buildSchemaFor(type)
    override fun deserialize(input: Any, targetType: KType): Any = customDeserialize(input, targetType)
    override fun serialize(value: Any): Any = customSerialize(value)
}
```

#### `NativeImageProxyConfig` — GraalVM native-image support

When compiling Tramai applications with GraalVM `native-image`, JDK proxy classes for `@AiService` interfaces must be registered explicitly:

```kotlin
import dev.tramai.core.nativeimage.*
import java.nio.file.*

NativeImageProxyConfig.write(
    outputPath = Path.of("META-INF/native-image/reflect-config.json"),
    WeatherService::class,
    StockAnalysisService::class,
)
```

This generates a JSON snippet that GraalVM's native-image agent uses to register dynamic proxies for those interfaces.

#### Custom `OperationObserver`

The observer SPI enables non-invasive monitoring — logging, metrics, tracing:

```kotlin
import dev.tramai.core.observation.*

class MetricsObserver : OperationObserver {
    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        println("[START] ${context.serviceInterface}.${context.methodName} " +
                "attempt #${context.attempt} on ${context.providerId}")
        val startTime = System.nanoTime()
        return object : OperationObservation {
            override fun onProviderResponse(response: ModelResponse) {
                println("[TOKENS] in=${response.inputTokens} out=${response.outputTokens}")
            }
            override fun onProviderFailure(error: Throwable) {
                println("[FAIL] ${error.message}")
            }
            override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) {
                println("[PARSE_FAIL] $errorSummary")
            }
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) {
                println("[EVENT] $name $attributes")
            }
            override fun onCallCompleted(parseSuccess: Boolean?) {
                val elapsed = (System.nanoTime() - startTime) / 1_000_000
                println("[END] ${context.methodName} took ${elapsed}ms")
            }
        }
    }
}
```

### Configuration reference

`tramai-core` defines the annotation-level configuration. The table below lists `@Operation` parameters — the primary configuration surface for application developers.

| Property | Type | Default | Description |
|---|---|---|---|
| `prompt` | `String` | *(required)* | Prompt template sent as the base user instruction |
| `model` | `String` | *(required)* | Logical model name (resolved via `ProviderRegistry`) |
| `provider` | `String` | `""` | Explicit provider ID — bypasses registry model resolution |
| `tools` | `String[]` | `[]` | Tool names available to this operation |
| `maxRetries` | `Int` | `2` | Structured-output parse/validation retries |
| `providerRetries` | `Int` | `3` | Transient provider failure retries |
| `timeoutMillis` | `Long` | `30_000` | Max duration per provider attempt |
| `cacheable` | `Boolean` | `false` | Whether the engine may cache non-streaming responses |
| `cacheTtlMillis` | `Long` | `60_000` | Cache TTL when `cacheable` is enabled |

Other key annotation parameters:

| Annotation | Property | Type | Default | Description |
|---|---|---|---|---|
| `@AiTool` | `name` | `String` | `""` | Explicit tool name (defaults to method name) |
| `@AiTool` | `description` | `String` | *(required)* | Tool description for model tool definitions |
| `@AiTool` | `idempotent` | `Boolean` | `false` | Safe to retry on transient failure |
| `@AiTool` | `sideEffectLevel` | `SideEffectLevel` | `UNKNOWN` | Side-effect classification |
| `@AiDescription` | `value` | `String` | *(required)* | Human-readable property description |
| `@AiMinItems` | `value` | `Int` | *(required)* | Minimum collection size |
| `@AiRange` | `min` | `Double` | *(required)* | Inclusive lower bound |
| `@AiRange` | `max` | `Double` | *(required)* | Inclusive upper bound |
| `@SystemPrompt` | `value` | `String` | *(required)* | System prompt prepended to each request |

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-core` follows a **contract-first, SPI-driven** design philosophy. Every type in the module exists for one of three reasons:

1. **Declare intent** — annotations (`@AiService`, `@Operation`, `@AiTool`) let developers describe what they want in pure Kotlin/Java, without coupling to any specific provider or execution strategy.
2. **Normalize the polyglot** — data models (`ModelRequest`, `ModelResponse`, `Message`, `ToolCall`, `StreamChunk`) provide a single representation for the many different wire formats used by AI providers (OpenAI, Anthropic, Ollama, etc.).
3. **Define extension boundaries** — interfaces (`ModelProvider`, `StreamCapable`, `StructuredOutputHandler`, `OperationObserver`, `OperationInterceptor`, `SecretValueResolver`) define explicit SPIs that transport modules, monitoring adapters, and security tooling implement without cross-coupling.

The module carries **zero runtime dependencies** beyond `kotlinx-coroutines-core`.

### Module boundary

```
┌──────────────────────────────────────────────┐
│               tramai-core                     │
│                                               │
│   ┌─────────────────────────────────┐         │
│   │      annotations (7 types)      │         │
│   │  @AiService  @Operation         │         │
│   │  @AiTool     @SystemPrompt      │         │
│   │  @AiDescription                 │         │
│   │  @AiMinItems  @AiRange          │         │
│   └─────────────────────────────────┘         │
│                                               │
│   ┌─────────────────────────────────┐         │
│   │     model (6 files, 7 types)     │         │
│   │  Message  ModelRequest          │         │
│   │  ModelResponse  StreamChunk     │         │
│   │  ToolCall  ToolResult           │         │
│   │  ToolDefinition  ToolExecCtx    │         │
│   │  TramaiTool  ResolvedTool       │         │
│   └─────────────────────────────────┘         │
│                                               │
│   ┌─────────────────────────────────┐         │
│   │     provider (4 files)          │         │
│   │  ModelProvider  ProviderRegistry│         │
│   │  StreamCapable  ProviderFailures│         │
│   └─────────────────────────────────┘         │
│                                               │
│   ┌─────────────────────────────────┐         │
│   │   structured (3 files)          │         │
│   │  StructuredOutputContract       │         │
│   │  StructuredOutputHandler        │         │
│   │  StructuredOutputResult         │         │
│   └─────────────────────────────────┘         │
│                                               │
│   ┌─────────────────────────────────┐         │
│   │   observation (2 files)         │         │
│   │  OperationInterceptor           │         │
│   │  OperationObserver (->Obs.)     │         │
│   └─────────────────────────────────┘         │
│                                               │
│   ┌─────────────────────────────────┐         │
│   │   exception (2 files, 7 types)  │         │
│   │  TramaiException (sealed)       │         │
│   │  ProviderException             │         │
│   │  StructuredOutputException      │         │
│   │  ConfigurationException        │         │
│   │  TimeoutException              │         │
│   │  ProviderCapabilityException   │         │
│   │  CircuitBreakerOpenException   │         │
│   │  TokenBudgetExceededException  │         │
│   │  ToolInvalidInputException     │         │
│   └─────────────────────────────────┘         │
│                                               │
│   ┌─────────────────────────────────┐         │
│   │  secret (1 file)                │         │
│   │  SecretValueResolver (fun int)  │         │
│   │  EnvironmentSecretResolver      │         │
│   │  FileSecretValueResolver       │         │
│   └─────────────────────────────────┘         │
│                                               │
│   ┌─────────────────────────────────┐         │
│   │  nativeimage (1 file)           │         │
│   │  NativeImageProxyConfig         │         │
│   └─────────────────────────────────┘         │
└──────────────────────────────────────────────┘
```

**What `tramai-core` does NOT include:**
- No HTTP client or transport code
- No execution engine or proxy generation
- No JSON parsing or schema generation — that lives in `tramai-structured`
- No workflow/orchestration — that lives in `tramai-orchestration`
- No observability runtime — that lives in `tramai-observability`
- No framework integration — that lives in `tramai-spring` / `tramai-standalone`

### Dependency graph

```
tramai-core ─── kotlinx-coroutines-core
     │
     ├── tramai-engine
     │       ├── tramai-structured
     │       └── tramai-observability
     │
     ├── tramai-openai
     ├── tramai-anthropic
     ├── tramai-ollama
     ├── tramai-standalone
     ├── tramai-spring
     └── tramai-testing
```

`tramai-core` is the single direct dependency of all transport, engine, and adapter modules. It sits at the bottom of the dependency stack.

### Inner mechanics

#### 12-package structure

| # | Package | Files | Purpose |
|---|---|---|---|
| 1 | `dev.tramai.core.annotations` | 7 | `@AiService`, `@Operation`, `@AiTool`, `@SystemPrompt`, `@AiDescription`, `@AiMinItems`, `@AiRange` |
| 2 | `dev.tramai.core.model` | 6 | `Message`, `ModelRequest`, `ModelResponse`, `StreamChunk`, `ToolCall`, `ToolDefinition`, `TramaiTool`, `ResolvedTool`, `ToolResult`, `UsageMetrics`, `ToolExecutionContext`, `SideEffectLevel`, `MessageRole`, `FinishReason` |
| 3 | `dev.tramai.core.provider` | 4 | `ModelProvider`, `ProviderRegistry`, `StreamCapable`, `ProviderFailures` (utility functions) |
| 4 | `dev.tramai.core.structured` | 3 | `StructuredOutputContract`, `StructuredOutputHandler`, `StructuredOutputResult` |
| 5 | `dev.tramai.core.observation` | 2 | `OperationInterceptor`, `OperationObserver`/`OperationObservation` |
| 6 | `dev.tramai.core.exception` | 2 | `TramaiException` (sealed base), 8 concrete exception types |
| 7 | `dev.tramai.core.secret` | 1 | `SecretValueResolver`, `EnvironmentSecretValueResolver`, `FileSecretValueResolver`, `CompositeSecretValueResolver` |
| 8 | `dev.tramai.core.nativeimage` | 1 | `NativeImageProxyConfig` |

#### Class hierarchy

```
Kotlin annotations (runtime retention, must be documented)
  ├── @AiService              — marks an interface as a Tramai proxy target
  ├── @Operation              — declares one AI-backed method on an @AiService
  ├── @AiTool                 — registers a method as a callable tool
  ├── @SystemPrompt           — service-wide system prompt
  ├── @AiDescription          — schema hint for structured output properties
  ├── @AiMinItems             — minimum collection size constraint
  └── @AiRange                — numeric range constraint (min, max)

Data classes
  ├── Message(role, content, toolCallId, toolCalls)
  ├── ModelRequest(model, messages, tools, maxTokens, temperature, timeoutMillis, ...)
  ├── ModelResponse(content, toolCalls, inputTokens, outputTokens, modelUsed, finishReason)
  ├── ToolCall(id, name, argumentsJson)
  ├── ToolDefinition(name, description, inputSchemaJson)
  ├── ToolExecutionContext(operationName, modelName, attemptNumber, conversationId, timeout, attributes)
  ├── UsageMetrics(inputTokens, outputTokens)
  ├── StructuredOutputContract(targetType, schemaJson)
  ├── ProviderRoute(providerName, effectiveModelName)
  ├── ResolvedProviderRoute(providerName, provider, requestedModelName, effectiveModelName)
  ├── OperationCallContext(serviceInterface, methodName, providerId, requestedModel, attempt)

Sealed classes / interfaces
  ├── StreamChunk — sealed
  │   ├── Token(text)          — incremental text fragment
  │   ├── Complete(fullText, usage) — stream finished
  │   └── Error(cause)         — stream failed
  ├── ToolResult — sealed
  │   ├── Success(value)       — executed successfully
  │   ├── InvalidInput(message) — input rejected, feed back to model
  │   ├── TransientFailure(cause) — retry if idempotent
  │   └── PermanentFailure(message) — surface to caller
  ├── StructuredOutputResult — sealed interface
  │   ├── Success(value, rawResponse)
  │   └── Failure(rawResponse, errorSummary, feedbackMessage)
  └── TramaiException — sealed base for all exceptions

Interfaces
  ├── ModelProvider            — provider SPI: complete(request): ModelResponse
  ├── StreamCapable            — capability marker: stream(request): Flow<StreamChunk>
  ├── TramaiTool<I, O>         — user-facing tool contract
  ├── ResolvedTool             — engine-facing tool contract
  ├── StructuredOutputHandler  — pluggable schema/parse SPI
  ├── OperationObserver        — creates per-call observations
  ├── OperationObservation     — per-attempt callback set
  ├── OperationInterceptor     — request/response inspection + modification
  └── SecretValueResolver      — resolves external secret references

Utility objects
  ├── CompositeOperationInterceptor — chains multiple interceptors
  ├── NoOpOperationInterceptor      — no-op default
  ├── NoOpOperationObserver         — no-op observer
  ├── NoOpOperationObservation      — no-op observation
  ├── EnvironmentSecretValueResolver — resolves "env:VAR" references
  ├── FileSecretValueResolver        — resolves "file:/path" references
  ├── CompositeSecretValueResolver   — chains multiple resolvers
  └── NativeImageProxyConfig         — generates GraalVM proxy metadata
```

### SPI system

`tramai-core` defines four extension SPIs. Each has a well-defined lifecycle:

| SPI | Interface | Implemented by | Registration |
|---|---|---|---|
| **Provider** | `ModelProvider` (+ `StreamCapable`) | `tramai-openai`, `tramai-anthropic`, `tramai-ollama`, custom providers | `ProviderRegistry.builder().provider(...)` |
| **Structured Output** | `StructuredOutputHandler` | `tramai-structured` (Jackson handler) | Engine constructor |
| **Observation** | `OperationObserver` | `tramai-observability`, custom observers | Engine constructor |
| **Interception** | `OperationInterceptor` | Custom PII redaction, logging, auditing | `CompositeOperationInterceptor` |
| **Secret Resolution** | `SecretValueResolver` | Built-in `env:`/`file:` resolvers, custom resolvers | `CompositeSecretValueResolver` |

Resolution pipeline (for `ModelProvider`):

```
@Operation(model="gpt-4o", provider="")
         │
         ▼
ProviderRegistry.resolve(operation)
    │
    ├── @Operation.provider != "" → lookup by provider name
    ├── routesByRequestedModel["gpt-4o"] → mapped routes (with fallbacks)
    └── defaultProviderName → fallback default
         │
         ▼
    ResolvedProviderRoute(provider=OpenAiProvider, model="gpt-4o")
         │
         ▼
    engine → provider.complete(request)
```

### Error model

`tramai-core` defines a sealed exception hierarchy rooted at `TramaiException` (which extends `RuntimeException`):

```
TramaiException (sealed, extends RuntimeException)
  ├── StructuredOutputException
  │   └── carries: originalPrompt, lastRawResponse, validationError, attemptCount
  ├── ProviderException
  │   └── carries: statusCode, retryable, retryAfterMillis
  ├── ConfigurationException
  ├── TimeoutException
  ├── ProviderCapabilityException
  │   └── carries: providerId, capability
  ├── CircuitBreakerOpenException
  │   └── carries: providerId, reopenAtEpochMillis
  ├── TokenBudgetExceededException
  │   └── carries: scope, limitTokens, observedTokens, providerId, modelName
  └── ToolInvalidInputException
```

**Error handling rules:**

- **`ProviderException`** with `retryable=true` — engine should retry per `@Operation.providerRetries`. Retryable status codes: 408, 425, 429, 500, 502, 503, 504. Transport failures (timeouts, connection refused, IO errors) are classified as retryable.
- **`ProviderException`** with `retryable=false` — non-retryable HTTP errors (4xx except 408/425/429), surfaced to caller immediately.
- **`StructuredOutputException`** — the engine retries up to `@Operation.maxRetries` times, appending the `feedbackMessage` from `StructuredOutputResult.Failure` to each subsequent prompt.
- **`ConfigurationException`** — raised at resolution time when providers are unregistered or misconfigured. Never retried.
- **`CircuitBreakerOpenException`** — the engine either falls back to another provider route or surfaces the failure.
- **`TokenBudgetExceededException`** — the engine terminates the current attempt and surfaces the failure.
- **`ToolInvalidInputException`** — tool-authored validation failure; engine feeds back to the model for correction.

`ProviderFailures.kt` provides utility functions for normalizing provider errors:

```kotlin
fun providerHttpFailure(providerName, statusCode, body, retryAfterHeader): ProviderException
fun providerTransportFailure(providerName, error): ProviderException
```

### Testing strategy

`tramai-core` has 3 test files (in `src/test/kotlin`):

| Test file | What it covers |
|---|---|
| `ProviderRegistryTest.kt` | Registry resolution: explicit provider, model mapping, fallback routes, default provider, unknown provider errors, configuration failure edge cases |
| `ProviderFailuresTest.kt` | HTTP failure normalization, transport failure mapping (timeout, connect, IO), retry-after header parsing |
| `NativeImageProxyConfigTest.kt` | JSON generation format, validation of `@AiService` annotation presence, validation of interface requirement |

Testing philosophy for consumers of `tramai-core`:

- **Annotations** are compile-time contracts — test that they're present and carry expected values via reflection when building custom scanners.
- **Data models** are pure data — no behavior to test beyond `ModelResponse.totalTokens()`.
- **Provider implementations** — test that `ModelRequest` → provider transport → `ModelResponse` round-trips correctly.
- **ProviderRegistry** — test that resolution order matches the documented priority: `@Operation.provider` > model mapping > default provider > `ConfigurationException`.
