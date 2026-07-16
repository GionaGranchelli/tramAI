# Module: `tramai-engine`

> **One-liner:** Runtime engine that turns annotated service interfaces into AI-backed proxies with retry, circuit-breaking, caching, and token budgeting.
> **Module type:** `core`

---

## L1: Quick Start (30-second read)

### What

`tramai-engine` is the execution core of Tramai. It takes a Kotlin/Java interface annotated with `@AiService` / `@Operation` and generates a `java.lang.reflect.Proxy` that routes each method invocation through an LLM provider. It owns the full execution pipeline: provider selection, retry logic, circuit breaking, response caching, token budget enforcement, structured-output retry loops, and tool-call orchestration.

### Why

Without `tramai-engine`, you have only the raw SPI contracts in `tramai-core`. The engine bridges annotated interfaces to provider calls, turning a declarative service definition into working AI invocations. It adds production hardening (retries, circuit breakers, timeouts, caches, token budgets) transparently — you never write provider-routing or error-handling code by hand.

### When to use

Always. `tramai-engine` is the mandatory runtime for any Tramai-based application. It is automatically included by:

- `tramai-standalone` (framework-free entry point)
- `tramai-spring` (Spring Boot auto-configuration)

You only interact with it directly if you are building a custom integration or need to configure engine-level settings (cache, circuit breaker, retry policy, token budgets).

### How to add

**Gradle:**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-engine:0.5.0")
}
```

**Maven:**

```xml
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-engine</artifactId>
    <version>0.4.0</version>
</dependency>
```

**Bill of Materials:**

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.5.0"))
implementation("dev.tramai:tramai-engine")
```

### Where to go next

| Topic | Link |
|-------|------|
| Quickstart with all modules | `docs/guides/getting-started.md` |
| Framework-free standalone setup | `docs/modules/tramai-standalone.md` |
| Spring Boot auto-configuration | `docs/modules/tramai-spring.md` |
| Defining tools for the engine | `docs/modules/tramai-testing.md` |
| Spec — Engine & Core design | `docs/specs/spec-001.md` |
| Spec — Production hardening | `docs/specs/spec-011.md` |
| Spec — Tool calling | `docs/specs/spec-010.md` |

---

## L2: Usage Guide (5-minute read)

### Quick usage

The most common pattern: create a `TramaiEngine` with a single provider, then call `engine.create<T>()` to obtain a proxy.

```kotlin
import dev.tramai.engine.TramaiEngine
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.SystemPrompt
import dev.tramai.openai.OpenAiProvider

// 1. Define your AI service interface
@AiService
@SystemPrompt("You are a helpful assistant.")
interface Summarizer {
    @Operation(
        prompt = "Summarize the following text in one sentence",
        model = "gpt-4o",
    )
    fun summarize(text: String): String
}

// 2. Create the engine
val provider = OpenAiProvider(apiKey = System.getenv("OPENAI_API_KEY"))
val engine = TramaiEngine(provider)

// 3. Get a proxy and call it
val service = engine.create<Summarizer>()
val summary = service.summarize("Long article text...")
println(summary) // "The article discusses..."

// 4. Clean up
engine.close()
```

**Suspend variant:**

```kotlin
@AiService
@SystemPrompt("You are a precise billing assistant.")
interface SuspendAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a raw summary",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun analyze(invoiceId: String): String
}

// Usage
val result = runBlocking { service.analyze("invoice-123") }
```

### Advanced usage

#### Retry policy configuration

The engine retries retryable `ProviderException`s (e.g., HTTP 429, 503) and `TimeoutException`s up to `@Operation(providerRetries)` times per route.

```kotlin
val engine = TramaiEngine(
    provider = provider,
    retryPolicySettings = RetryPolicySettings(
        maxRetryAfterMillis = 30_000,   // cap for provider-supplied Retry-After headers
        jitterRatio = 0.2,              // 20% random jitter added to delay
    ),
)
```

The per-route retry budget comes from `@Operation(providerRetries)` — defaults to the engine-level default (3 retries). Set it per operation:

```kotlin
@Operation(
    prompt = "Analyze under rate limiting",
    model = "gpt-4o",
    providerRetries = 5,   // up to 5 retries on this route before fallback
)
suspend fun analyze(id: String): String
```

#### Circuit breaker

Enable the circuit breaker to skip unhealthy provider routes for a cooldown period.

```kotlin
val engine = TramaiEngine(
    provider = provider,
    circuitBreakerSettings = CircuitBreakerSettings(
        enabled = true,
        failureThreshold = 3,         // 3 consecutive failures opens the circuit
        openDurationMillis = 30_000,  // stay open for 30 seconds
    ),
)
```

When the circuit is open, the engine automatically falls through to the next configured route (see provider routing below) or throws `CircuitBreakerOpenException` if no fallback exists.

#### Response cache

Cache non-streaming, non-tool results to avoid redundant LLM calls.

```kotlin
val engine = TramaiEngine(
    provider = provider,
    responseCache = InMemoryOperationResponseCache(
        maxEntries = 1_000,   // LRU eviction
    ),
)
```

Enable caching per operation with `@Operation(cacheable = true)`:

```kotlin
@Operation(
    prompt = "Classify this text",
    model = "gpt-4o-mini",
    cacheable = true,
    cacheTtlMillis = 60_000,   // 1 minute TTL
)
fun classify(text: String): String
```

Caching is automatically skipped for:
- Operations that return `Flow<StreamChunk>` (streaming)
- Operations that declare `tools` (tool-invoking)

The cache key includes the service interface name, method name, model, explicit provider (if set), and rendered messages (system prompt + operation prompt + arguments).

#### Token budget enforcement

Prevent runaway token consumption with per-attempt or per-operation budgets.

```kotlin
val engine = TramaiEngine(
    provider = provider,
    tokenBudgetSettings = TokenBudgetSettings(
        hardMaxTokensPerAttempt = 4_000,           // fail if a single response exceeds 4K tokens
        hardMaxTokensPerOperation = 16_000,         // fail if cumulative tokens across retries exceed 16K
        softMaxTokensPerOperation = 10_000,         // emit event (no failure) at 10K cumulative
    ),
)
```

- `hardMaxTokensPerAttempt` — per-response token cap
- `hardMaxTokensPerOperation` — cumulative across retries, structured-output retry loops, and tool-call loops
- `softMaxTokensPerOperation` — emits `tramai.token_budget.soft_limit_exceeded` engine event

When a hard limit is exceeded, `TokenBudgetExceededException` is thrown with scope, limit, and observed token counts.

#### Provider routing and fallbacks

Use `ProviderRegistry` for multi-provider setups with fallback routes.

```kotlin
val registry = ProviderRegistry.builder()
    .provider("anthropic", AnthropicProvider(apiKey = "..."))
    .provider("fallback", OllamaProvider(baseUrl = "http://localhost:11434"))
    .model("claude-sonnet-4-20250514", "anthropic")
    .fallbackModel("claude-sonnet-4-20250514", "llama3.2", "fallback")
    .build()

val engine = TramaiEngine(providerRegistry = registry)
```

When the primary route fails with a retryable error (exhausting its per-route retries), the engine falls through to the next candidate route. Fallback routes receive the fallback model name, not the original.

Override the provider at the operation level:

```kotlin
@Operation(
    prompt = "Analyze with Ollama explicitly",
    model = "claude-sonnet-4-20250514",
    provider = "ollama",           // bypass model→provider resolution
)
suspend fun analyze(id: String): String
```

#### Structured output

Combine with `tramai-structured` for typed, validated responses.

```kotlin
import dev.tramai.structured.JacksonStructuredOutputHandler

data class InvoiceAnalysis(
    val total: Double,
    val currency: String,
    val isFraudulent: Boolean,
)

@AiService
interface InvoiceService {
    @Operation(
        prompt = "Analyze this invoice for fraud indicators",
        model = "claude-sonnet-4-20250514",
        maxRetries = 2,   // structured-output retries on parse failure
    )
    suspend fun analyze(invoiceId: String): InvoiceAnalysis
}

// Usage
val engine = TramaiEngine(
    provider = provider,
    structuredOutputHandler = JacksonStructuredOutputHandler(),
)
val service = engine.create<InvoiceService>()
val result = service.analyze("INV-042")
println(result.total)        // Double
println(result.isFraudulent) // Boolean
```

On parse failure:
1. The assistant's raw response is appended as an `ASSISTANT` message
2. A corrective `USER` message (generated by the structured output handler) is appended
3. The call is retried, up to `@Operation(maxRetries)` total attempts
4. After exhaustion, `StructuredOutputException` is thrown with `originalPrompt`, `lastRawResponse`, `validationError`, and `attemptCount`

#### Streaming

```kotlin
import kotlinx.coroutines.flow.Flow
import dev.tramai.core.model.StreamChunk

@AiService
interface StreamingService {
    @Operation(
        prompt = "Stream a detailed analysis",
        model = "claude-sonnet-4-20250514",
    )
    fun stream(invoiceId: String): Flow<StreamChunk>
}

// Usage
runBlocking {
    service.stream("INV-042").collect { chunk ->
        when (chunk) {
            is StreamChunk.Token -> print(chunk.text)
            is StreamChunk.Complete -> println("\n[Done: ${chunk.usage.outputTokens} tokens]")
            is StreamChunk.Error -> System.err.println("Error: ${chunk.cause.message}")
        }
    }
}
```

Streaming supports fallback routing: if the primary provider emits an `Error` chunk before any tokens (`startup failure`), the engine retries on the next route. If any tokens have been emitted, the error is terminal.

#### Tool calling

```kotlin
import dev.tramai.core.model.ToolResult
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.ResolvedTool
import dev.tramai.core.model.SideEffectLevel

val lookupTool = object : ResolvedTool {
    override val name = "lookup"
    override val description = "Looks up a tenant by ID"
    override val inputSchemaJson = """{"type":"object","properties":{"id":{"type":"string"}}}"""
    override val idempotent = true     // allows retry on transient failure
    override val sideEffectLevel = SideEffectLevel.READ_ONLY

    override suspend fun execute(input: Any, context: ToolExecutionContext): ToolResult {
        val id = (input as Map<*, *>)["id"] as? String ?: return ToolResult.InvalidInput("missing id")
        return ToolResult.Success("""{"name":"Acme Corp"}""")
    }
}

val engine = TramaiEngine(
    provider = provider,
    toolRegistry = ToolRegistry(mapOf(lookupTool.name to lookupTool)),
)

@AiService
interface QueryService {
    @Operation(
        prompt = "Look up the tenant",
        model = "claude-sonnet-4-20250514",
        tools = ["lookup"],
    )
    suspend fun query(tenantId: String): String
}
```

The engine executes up to 5 tool-call loops, appending assistant tool-call messages and tool-result messages to the conversation. Idempotent tools (`idempotent = true`) are retried once on transient failure.

### Expert usage

#### Custom cache implementation

Implement the `OperationResponseCache` interface for Redis, Memcached, or other distributed caches.

```kotlin
class RedisOperationResponseCache(
    private val redis: RedisClient,
) : OperationResponseCache {
    override fun get(key: OperationCacheKey): Any? {
        // Deserialize from Redis
    }

    override fun put(key: OperationCacheKey, value: Any, ttlMillis: Long) {
        // Serialize and store in Redis with TTL
    }
}
```

#### Operation interceptor

Mutate requests and responses mid-flight via `OperationInterceptor`.

```kotlin
val redactingInterceptor = object : OperationInterceptor {
    override fun interceptRequest(
        context: OperationCallContext,
        messages: List<Message>,
    ): List<Message> = messages.map {
        it.copy(content = it.content.replace(Regex("\\b\\d{16}\\b"), "****-****-****-****"))
    }

    override fun interceptResponse(
        context: OperationCallContext,
        response: ModelResponse,
    ): ModelResponse = response.copy(
        content = response.content.replace(Regex("\\b\\d{16}\\b"), "****-****-****-****"),
    )
}

val engine = TramaiEngine(
    provider = provider,
    operationInterceptor = redactingInterceptor,
)
```

#### Operation observer

Attach custom observability without modifying the engine.

```kotlin
class MetricCollectingObserver : OperationObserver {
    override fun onCallStarted(context: OperationCallContext): OperationObservation {
        val startTime = System.nanoTime()
        return object : OperationObservation {
            override fun onProviderResponse(response: ModelResponse) { /* record */ }
            override fun onProviderFailure(error: Throwable) { /* record */ }
            override fun onStructuredParseFailure(rawResponse: String, errorSummary: String) { /* record */ }
            override fun onEngineEvent(name: String, attributes: Map<String, Any?>) { /* record */ }
            override fun onCallCompleted(parseSuccess: Boolean?) {
                val elapsed = System.nanoTime() - startTime
                metrics.recordLatency(context.providerId, context.methodName, elapsed)
            }
        }
    }
}
```

#### Native Image support

`TramaiEngine` uses `java.lang.reflect.Proxy`, which requires proxy class pre-registration in GraalVM Native Image. The `NativeImageSmokeTest` provides a reference for setting this up. Use `@TypeHint` or a GraalVM reflection configuration file to register proxy interfaces.

#### Conversational memory

Inject conversation history into AI calls and persist responses automatically.

Requires adding `tramai-memory` to your dependencies:

```kotlin
dependencies {
    implementation("dev.tramai:tramai-memory:0.5.0")
}
```

Then configure a `WindowChatMemory` and pass it to the engine:

```kotlin
val memory = MessageWindowChatMemory(
    maxMessages = 20,        // non-system messages per conversation
    maxConversations = 1000, // active conversations before LRU eviction
)

val engine = TramaiEngine(
    provider = provider,
    chatMemory = memory,     // enables history injection + persistence
)
```

The `@ConversationId` annotation on a method parameter marks which argument identifies the conversation:

```kotlin
@AiService
interface ChatService {
    @Operation(prompt = "Answer the user's question", model = "claude-sonnet-4-20250514")
    suspend fun chat(
        @ConversationId sessionId: String,
        message: String,
    ): String
}
```

When `chatMemory` is configured, the handler:
1. Resolves the conversation ID from the `@ConversationId` parameter
2. Loads history from the memory store and prepends it to the request
3. After a successful response, persists the user's messages and assistant's reply

Memory is injected for all return kinds (raw string, structured, streaming).
Persistence is skipped for streaming responses (deferred to v1.1).
System messages are deduplicated across turns (history takes precedence).

### Configuration reference

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `RetryPolicySettings.maxRetryAfterMillis` | `Long` | `30_000` | Cap for provider-supplied `Retry-After` delays |
| `RetryPolicySettings.jitterRatio` | `Double` | `0.2` | Fraction of random jitter added to retry delays |
| `CircuitBreakerSettings.enabled` | `Boolean` | `false` | Enable circuit breaker |
| `CircuitBreakerSettings.failureThreshold` | `Int` | `3` | Consecutive failures before circuit opens |
| `CircuitBreakerSettings.openDurationMillis` | `Long` | `30_000` | Duration circuit stays open (ms) |
| `TokenBudgetSettings.hardMaxTokensPerAttempt` | `Long?` | `null` | Hard cap per single provider response |
|| `TokenBudgetSettings.hardMaxTokensPerOperation` | `Long?` | `null` | Hard cap for cumulative operation tokens |
|| `TokenBudgetSettings.softMaxTokensPerOperation` | `Long?` | `null` | Soft cap — emits event, does not fail |
|| `InMemoryOperationResponseCache.maxEntries` | `Int` | `1_000` | Max cache entries (LRU eviction) |
|| `chatMemory` | `ChatMemory?` | `null` | Enables conversational memory injection and persistence |
|| `conversationIdProvider` | `ConversationIdProvider` | `UuidConversationIdProvider` | Fallback ID generator when no `@ConversationId` annotation |

**`@Operation` annotation settings relevant to the engine:**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `provider` | `String` | `""` | Explicit provider name (bypasses model→provider resolution) |
| `providerRetries` | `Int` | `3` | Retries per route before fallback |
| `maxRetries` | `Int` | `2` | Structured-output parse retries (only for non-`String` returns) |
| `timeoutMillis` | `Long` | `30_000` | Per-call timeout |
| `cacheable` | `Boolean` | `false` | Enable response caching |
| `cacheTtlMillis` | `Long` | `60_000` | Cache TTL (only when `cacheable = true`) |
| `tools` | `String[]` | `[]` | Tool names to make available |

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-engine` owns the **orchestration** of a single AI operation — from the moment an annotated interface method is called until a value (or error) is returned. It is deliberately **not** a workflow engine (that role belongs to `tramai-orchestration`). It handles:

- Proxy generation (interface → JVM proxy)
- Provider routing and fallback
- Retry with backoff and jitter
- Circuit breaking
- Response caching
- Token budget enforcement
- Structured-output retry loops
- Tool-call execution loops
- Observability hooks (interceptors and observers)

The engine is **stateless** with respect to business logic: all state is either in configuration (settings objects), transient (in-flight observations), or external (cache, provider). This makes the engine testable and embeddable.

### Module boundary

`tramai-engine` depends on `tramai-core` (api scope) and uses `tramai-structured` only in tests. It defines no new annotations — it interprets `@AiService`, `@Operation`, and `@SystemPrompt` from `tramai-core`.

**Owns:**
- `TramaiEngine` — the public entry point, now accepts optional `chatMemory` and `conversationIdProvider`
- `RetryPolicySettings` — retry delay computation
- `CircuitBreakerSettings` — per-provider circuit state
- `TokenBudgetSettings` — per-operation and per-attempt budgets
- `OperationResponseCache` / `InMemoryOperationResponseCache` — caching contract and default impl
- `ToolRegistry` — tool resolution
- Proxy generation and invocation (`TramaiInvocationHandler`) — includes `@ConversationId` resolution and memory delegation
- Route selection logic (via `ProviderRegistry.resolveCandidates`)
- Structured output retry loop
- Conversational memory injection and persistence (via `ChatMemory` SPI)

**Does not own:**
- Annotations (`@AiService`, `@Operation`, etc.) — owned by `tramai-core`
- Model provider implementations — owned by provider modules (`tramai-openai`, `tramai-anthropic`, `tramai-ollama`)
- Structured output schema/parsing — owned by `tramai-structured`
- Multi-step workflows — owned by `tramai-orchestration`
- Framework integration — owned by adapters (`tramai-spring`, `tramai-standalone`)
- Memory implementations — owned by `tramai-memory` (e.g., `MessageWindowChatMemory`, `MemoryInterceptor`)

### Dependency graph

```
tramai-engine
  ├── api: tramai-core (annotations, models, providers, exceptions, observation SPI)
  ├── impl: kotlinx-coroutines-core
  ├── impl: kotlin-reflect
  └── test: tramai-structured (JacksonStructuredOutputHandler)
```

No transitive dependency on any specific AI provider. No dependency on Spring, Micronaut, or any framework.

### Inner mechanics

#### Proxy generation algorithm

```
┌─────────────────────────────────────────────────────────────────────┐
│ TramaiEngine.create(ServiceType)                                    │
│   │                                                                 │
│   ├─ ServiceDefinition.create(serviceType, toolRegistry)            │
│   │   ├─ Validate: is interface? has @AiService?                    │
│   │   ├─ Read @SystemPrompt (optional)                              │
│   │   └─ For each method (excluding Object methods):                │
│   │       ├─ Read @Operation (required, fail if missing)            │
│   │       ├─ Resolve tool names → ToolDefinition[]                  │
│   │       ├─ Determine return kind (String/Unit/STRUCTURED/STREAMING│
│   │       │   via Kotlin reflection or Java fallback)               │
│   │       ├─ Detect suspend vs blocking via Continuation parameter  │
│   │       └─ Store as OperationDefinition in method→op map          │
│   │                                                                 │
│   └─ TramaiInvocationHandler(providerRegistry, cache, circuit,      │
│       retry, tokenBudget, observer, interceptor, definition,        │
│       chatMemory, conversationIdProvider)                           │
│         │                                                           │
│         └─ Proxy.newProxyInstance(loader, [ServiceType], handler)   │
│                                                                     │
│ On method invocation:                                               │
│   handler.invoke(proxy, method, args)                               │
│     ├─ Object method? → delegate to toString/hashCode/equals        │
│     ├─ Resolve conversationId from @ConversationId or provider      │
│     ├─ Suspend? → launch in engine scope, return COROUTINE_SUSPENDED│
│     └─ Blocking? → runBlocking { execute(op, args) }                │
│                                                                     │
│   execute(op, args):                                                │
│     ├─ ReturnKind.STRING → executeRaw → executeWithTools            │
│     ├─ ReturnKind.UNIT → executeRaw, return Unit                    │
│     ├─ ReturnKind.STRUCTURED → executeStructured (retry loop)       │
│     └─ ReturnKind.STREAMING → executeStreaming (Flow)               │
└─────────────────────────────────────────────────────────────────────┘
```

**Key design decisions in proxy generation:**

1. **No annotation processing at compile time** — the engine uses `java.lang.reflect.Proxy` at runtime, reading annotations via standard Java reflection. This avoids KAPT/KSP build-time dependencies and keeps the module build-light.
2. **Kotlin reflection is optional** — the engine prefers `method.kotlinFunction` for suspend detection and parameter names, but falls back to Java reflection (signature-based `Continuation` detection and `Parameters` API) when Kotlin metadata is absent.
3. **Return kind resolution** — the engine classifies every method into one of four return kinds:
   - `STRING`: `String` return type → raw text response
   - `UNIT`: `Unit` / `void` → calls provider, discards content
   - `STRUCTURED`: any non-`String` object type → structured output contract
   - `STREAMING`: `Flow<StreamChunk>` → streaming route
4. **Cache eligibility** — an operation is cacheable only when all of: `@Operation(cacheable = true)`, return kind is not `STREAMING`, and no tools are declared.

#### Retry mechanics

The engine implements a **two-level retry hierarchy**:

**Level 1 — Per-route retries** (`@Operation(providerRetries)`)
```
For each candidate route (primary → fallback1 → fallback2):
  Repeat up to (providerRetries + 1) times:
    Call provider
    If success → return
    If retryable error (TimeoutException, ProviderException(retryable=true)):
      Compute delay = min(retryAfterHint, maxRetryAfterMillis) + jitter
      Emit "tramai.retry.scheduled" event
      Delay and retry
    If non-retryable error → throw immediately
  After exhausting retries → fall through to next route
```

**Level 2 — Structured-output parse retries** (`@Operation(maxRetries)`)
```
For structured return types, after Level 1 succeeds:
  Parse response through StructuredOutputHandler
  If Success → return value
  If Failure:
    If maxRetries not exceeded → append corrective messages, retry Level 1
    If exhausted → throw StructuredOutputException
```

**Retry delay computation:**

```
rawDelay = error.retryAfterMillis  (from ProviderException)
           ?: INITIAL_PROVIDER_RETRY_DELAY_MILLIS shl retryIndex  (50ms, 100ms, 200ms...)
capped  = min(rawDelay, maxRetryAfterMillis)
jitter  = capped * jitterRatio * random(0..1)
delay   = capped + jitter
```

Constants: `INITIAL_PROVIDER_RETRY_DELAY_MILLIS = 50`, `MAX_PROVIDER_RETRY_DELAY_MILLIS = 1_000`.

**Tool retries:**
- Idempotent tools (`idempotent = true`) are retried once on `TransientFailure`
- Non-idempotent tools are never retried
- Maximum tool-call loops per operation: **5** (guard against infinite tool loops)

#### Error model

All exceptions are in `dev.tramai.core.exception`:

| Exception | Raised when | Retryable |
|-----------|-------------|-----------|
| `ConfigurationException` | Invalid interface (no `@AiService`), missing `@Operation`, no provider registered | No (fail fast) |
| `ProviderException` | Provider returns an error; `retryable` flag from provider | `retryable` boolean |
| `TimeoutException` | Provider call exceeds `timeoutMillis` | Yes |
| `CircuitBreakerOpenException` | Circuit is open and no fallback route exists | No (routes skipped silently if fallback exists) |
| `TokenBudgetExceededException` | Hard token budget exceeded | No (terminal for the operation) |
| `StructuredOutputException` | Structured parse failure after exhausting retries | No |
| `ProviderCapabilityException` | Streaming requested but provider doesn't support it | No |

**Circuit breaker state machine:**

```
CLOSED ──(consecutive failures >= threshold)──→ OPEN
OPEN ──(openDurationMillis elapsed)──→ HALF_OPEN (next call probes)
HALF_OPEN ──(success)──→ CLOSED
HALF_OPEN ──(failure)──→ OPEN
```

The circuit breaker is per-provider (keyed by `providerId`), not per-model. On success, the circuit state is removed entirely. On failure, a counter increments until the threshold, then the circuit opens for `openDurationMillis`.

#### Testing strategy

The test suite covers **28 behavioral scenarios** across 2 test files:

**`TramaiEngineTest`** (1,117 lines, 24 test methods):

| Category | Tests | What they verify |
|----------|-------|-----------------|
| **Happy path** | `suspend proxy`, `blocking interface`, `Java interface` | Proxy returns provider content, correct model/timeout routing, message construction |
| **Return types** | `unit return`, `structured output`, `streaming` | All 4 return kinds work correctly |
| **Validation** | `missing @AiService`, `missing @Operation`, `invalid settings`, `structured without handler`, `no provider for model` | Fast-fail on misconfiguration |
| **Structured output** | `retry after malformed`, `fail after exhaustion`, `preserves context` | Parse-retry loop, exception payload |
| **Retries** | `retryable provider failure`, `honors retry-after`, `non-retryable`, `timeout retry`, `timeout exhaustion` | Delay computation, jitter, honor/ignore retry hints |
| **Error wrapping** | `unexpected provider error` | Raw exceptions → `ProviderException` |
| **Provider routing** | `explicit model registration`, `operation-level provider`, `no provider`, `fallback after retryable` | Route selection, fallback chain |
| **Circuit breaker** | `skips unhealthy route`, `reopens after window expires` | Threshold, open duration, engine events |
| **Streaming** | `fallback on startup error`, `streams chunks`, `token budget on complete`, `cancellation`, `provider lacks streaming` | Startup fallback, token emission, budget enforcement |
| **Caching** | `raw result cached`, `TTL expiry`, `skipped for tools` | Cache hit/miss, key derivation, invalidation |
| **Token budget** | `hard per-attempt`, `hard per-operation`, `soft limit`, `streaming budget` | All three budget scopes |
| **Interceptor** | `request/response modification` | Redaction via interceptor |
| **Concurrency** | `high-concurrency rate limiting` | 50 concurrent calls with rate limiting and retries |

**`NativeImageSmokeTest`** (50 lines, 1 test):

- Verifies that `TramaiEngine.create()` works with `java.lang.reflect.Proxy` in a GraalVM-native compatible setup

**Key testing patterns:**
- `RecordingProvider` / `NamedProvider` — capture requests for assertion
- `SequencedProvider` — return different responses on successive calls (for retry/structured-output tests)
- `RecordingObserver` — capture engine events for observability assertions
- `StreamingProvider` / `NamedStreamingProvider` — emit controlled `Flow<StreamChunk>` sequences
- All test interfaces are `private` to the test, avoiding API pollution

---

## L4: API Reference (auto-generated from KDoc)

| Type | Kind | Description |
|------|------|-------------|
| `TramaiEngine` | Class | Main engine: creates AI-backed proxies from annotated interfaces |
| `TramaiEngine.create()` | Method | Returns a JVM proxy implementing the given service type |
| `TramaiEngine.close()` | Method | Cancels the engine's coroutine job hierarchy |
| `RetryPolicySettings` | Data class | Retry delay computation: max `Retry-After` cap, jitter ratio |
| `CircuitBreakerSettings` | Data class | Per-provider circuit breaker: enabled, threshold, open duration |
| `TokenBudgetSettings` | Data class | Token budgets: hard per-attempt, hard/soft per-operation |
| `OperationResponseCache` | Interface | Cache contract for non-streaming operation results |
| `OperationCacheKey` | Data class | Stable cache key from interface, method, model, provider, messages |
| `CachedMessage` | Data class | Immutable message fragment for cache key derivation |
| `InMemoryOperationResponseCache` | Class | LRU-backed in-memory cache with TTL expiry |
| `ToolRegistry` | Class | Name-based resolution of registered tools |
| `NoOpOperationResponseCache` | Object | No-op cache singleton (default) |
