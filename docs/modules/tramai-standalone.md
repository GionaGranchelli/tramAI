# Module: `tramai-standalone`

> **One-liner:** Minimal framework-free entry point that wires core, engine, and structured output into a single `Tramai.create<T>()` call.
> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

---

## Architecture

### Responsibility

Framework-free entry point composing core, engine and structured output into a minimal runtime (`Tramai`, `TramaiRuntime`).

### Public entry points

- `Tramai` — builder-style entry point for framework-free usage
- `TramaiRuntime` — assembled runtime with lifecycle (public per `tramai-standalone/api/tramai-standalone.api`)

Verify the full public surface against `tramai-standalone/api/tramai-standalone.api`.

### Internal extension points

- Engine/structured/provider wiring knobs through the `Tramai` builder

### Significant dependencies

- `api(tramai-core)`, `api(tramai-engine)`, `api(tramai-structured)`, `api(kotlin-reflect)` (see [module-catalog.yml](../../config/quality/module-catalog.yml))

### Lifecycle ownership

- Owns the assembled runtime lifecycle (`close()`)

### Thread-safety and concurrency

- Runtime is concurrent-safe; delegates to engine coroutine scopes

### Failure semantics

- Surfaces engine/structured failures directly to callers

### Contract tests / TCKs

- `tramai-standalone/src/test`; consumed by framework adapters (e.g. `tramai-spring-core` depends on it)

### Do not

- Do not add provider or Spring dependencies here

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — runtime-execution layer
---

## L1: Quick Start (30-second read)

### What

`tramai-standalone` is the simplest way to use Tramai without Spring Boot, a DI framework, or any external wiring. A single `Tramai` class uses the builder pattern to collect a provider, model mappings, tools, and engine settings, then exposes a single `create<T>()` method that returns a fully functional `@AiService` proxy.

### Why

The standalone module exists so you can go from zero to an AI-backed service interface with:

- **No framework dependency** — no Spring, no Guice, no CDI
- **One import** — `dev.tramai.standalone.Tramai`
- **Zero magic** — every dependency is explicit in the builder chain
- **Structured output auto-wired** — `JacksonStructuredOutputHandler` is injected automatically, no extra setup

### When to use

```
Use this module when:
- You want the fastest path to an AI-backed @AiService interface
- You're writing a CLI tool, a script, or a small integration
- You want explicit, readable setup code without annotations or auto-configuration
- You're testing or prototyping new providers or models
- You want to understand exactly how core + engine + structured wire together

Don't use this module when:
- You need Spring Boot auto-configuration (use `tramai-spring-core` / `tramai-spring-boot-starter`; `tramai-spring` is the legacy facade)
- You need the orchestration DSL with @AiService scanning (use tramai-orchestration)
- You want DI-managed provider beans (use `tramai-spring-core` / provider starters)
```

### How to add

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-standalone")  // version from the TramAI BOM
    implementation("dev.tramai:tramai-ollama")  // version from the TramAI BOM // or tramai-openai, tramai-anthropic
}
```

```xml
<!-- Maven -->
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-standalone</artifactId>
    <version>${tramai.version}</version>
</dependency>
```

### Where to go next

- [Module Guide](../module-guide.md) — Recipe-based walkthroughs
- [tramai-core](./tramai-core.md) — Annotations and SPI contracts
- [tramai-engine](./tramai-engine.md) — Orchestration, retry, routing internals
- [tramai-structured](./tramai-structured.md) — How typed outputs are handled
- [tramai-ollama](./tramai-ollama.md), [tramai-openai](./tramai-openai.md), [tramai-anthropic](./tramai-anthropic.md) — Provider modules
- `tramai-spring-core` — Spring Boot alternative (`tramai-spring` is the legacy facade; card lands in a later 11.2b slice)

---

## L2: Usage Guide (5-minute read)

### Quick usage: Builder API

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.ollama.OllamaProvider
import dev.tramai.standalone.Tramai

@AiService
interface Translator {
    @Operation(prompt = "Translate the following text to French: {{$text}}")
    suspend fun translate(text: String): String
}

suspend fun main() {
    val translator = Tramai
        .builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma4:e2b", "ollama")
        .build()
        .create<Translator>()

    println(translator.translate("Hello, world!")) // "Bonjour le monde !"
}
```

### Quick usage: DSL variant

```kotlin
val translator = Tramai {
    provider(OllamaProvider("http://localhost:11434"), default = true)
    model("gemma4:e2b", "ollama")
}.create<Translator>()
```

The DSL uses a `Tramai(...)` top-level function that receives a `Tramai.Builder.() -> Unit` lambda — the same `Builder` class used by the fluent chain.

### Structured output with data classes

Since `JacksonStructuredOutputHandler` is auto-wired, any non-`String` return type gets parsed and validated automatically:

```kotlin
data class Weather(
    val temperature: Double,
    val condition: String,
    val humidity: Double,
)

@AiService
interface WeatherService {
    @Operation(prompt = "What is the weather in {{$city}}?")
    suspend fun getWeather(city: String): Weather
}

suspend fun main() {
    val service = Tramai {
        provider(OllamaProvider("http://localhost:11434"), default = true)
        model("gemma4:e2b", "ollama")
    }.create<WeatherService>()

    val weather = service.getWeather("Tokyo")
    println("${weather.temperature}°C, ${weather.condition}")
}
```

### Registering tools

```kotlin
data class TimeInput(val timezone: String)

class GetTimeTool : TramaiTool<TimeInput, String> {
    override val name = "get_time"
    override val description = "Returns current time for a timezone"
    override val inputType = TimeInput::class
    override suspend fun execute(input: TimeInput, context: ToolExecutionContext): String =
        "Current time in ${input.timezone} is 12:00 UTC"
}

val service = Tramai {
    provider(OpenAiProvider("sk-..."), default = true)
    model("gpt-4o", "openai")
    tools(GetTimeTool())
}.create<Assistant>()
```

### Multiple providers and model routing

```kotlin
val service = Tramai {
    provider(OllamaProvider("http://localhost:11434"), name = "local")
    provider(OpenAiProvider("sk-..."), name = "openai")
    model("gemma4:e2b", "local")
    model("gpt-4o", "openai")
    defaultProvider("openai") // fallback when no explicit model mapping
}.create<MultiModelService>()
```

### Engine configuration

```kotlin
val service = Tramai {
    provider(OllamaProvider("http://localhost:11434"), default = true)
    model("gemma4:e2b", "ollama")
    retryPolicy(RetryPolicySettings(maxRetryAfterMillis = 30_000, jitterRatio = 0.2))
    circuitBreaker(CircuitBreakerSettings(failureThreshold = 5, openDurationMillis = 30_000))
    tokenBudget(TokenBudgetSettings(hardMaxTokensPerOperation = 50_000))
    observer(myObserver)
    interceptor(myInterceptor)
    cache(myCache)
}.create<MyService>()
```

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-standalone` is deliberately thin — a single file that composes three lower-level modules (`tramai-core` for contracts, `tramai-engine` for orchestration, `tramai-structured` for typed output handling) into a single callable entry point. The philosophy is: **one import, one `create<T>()` call, zero framework**.

### Builder pattern

`Tramai` uses an explicit, immutable builder (not a `copy()` / `data class` mutation pattern). The `Builder` class aggregates all configuration into private fields, then `build()` creates a snapshot:

```
Tramai.builder()
  .provider(...)         → registers ModelProvider in ProviderRegistry
  .model(...)            → maps model name to provider
  .tools(...)            → registers TramaiTools with JSON schema generation
  .retryPolicy(...)      → engine-level retry pacing
  .circuitBreaker(...)   → engine-level circuit breaker for failover
  .tokenBudget(...)      → engine-level token budget controls
  .observer(...)         → pluggable operation observer
  .interceptor(...)      → pluggable request/response interceptor
  .cache(...)            → pluggable operation response cache
  .build()               → immutable Tramai instance
```

Every setter returns `Builder` (via `apply`), enabling both the fluent chain and the DSL lambda. Once `build()` is called, the `Tramai` instance is immutable — all fields are `private` and there are no setters.

### Provider registry wiring

The `Builder` delegates all provider and model routing configuration to a `ProviderRegistry.Builder` (from `tramai-core`):

1. `.provider(provider, name, default)` — Registers a `ModelProvider` under a logical name (defaults to `provider.providerId()`). The `default = true` flag sets this as the fallback when no explicit model mapping matches.

2. `.model(modelName, providerName)` — Maps a logical model string (the one referenced in `@Operation(model = ...)`) to a registered provider. Creates the primary route.

3. `.fallbackModel(requestedModelName, fallbackModelName, providerName)` — Adds a secondary route: if the primary provider fails, the engine can fall through.

4. `.fallbackProvider(modelName, providerName)` — Shorthand: same model name, different provider.

5. `.defaultProvider(providerName)` — Model-agnostic fallback when no route exists for the requested model.

At `build()` time, `registryBuilder.build()` produces an immutable `ProviderRegistry` that is passed into the `Tramai` constructor.

### Structured output auto-integration

The most important design decision in `tramai-standalone` is that **`JacksonStructuredOutputHandler` is hard-wired** into the `create<T>()` method:

```kotlin
fun <T : Any> create(serviceType: KClass<T>): T = TramaiEngine(
    providerRegistry = providerRegistry,
    structuredOutputHandler = JacksonStructuredOutputHandler(),  // <-- auto-wired
    toolRegistry = toolRegistry,
    operationObserver = operationObserver,
    operationInterceptor = operationInterceptor,
    responseCache = responseCache,
    circuitBreakerSettings = circuitBreakerSettings,
    retryPolicySettings = retryPolicySettings,
    tokenBudgetSettings = tokenBudgetSettings,
).create(serviceType)
```

This means:

- **`String` return types** — the raw model response text is returned directly
- **Any other return type** (`data class`, `List`, `Map`, etc.) — the engine routes the response through `JacksonStructuredOutputHandler`, which generates a JSON schema prompt, sends it to the model, parses the response, validates it against the expected type, and either returns a typed result or throws a `StructuredOutputException` with retry context

The `Builder` also uses `JacksonStructuredOutputHandler` internally when generating tool input JSON schemas via `handler.generateSchema(tool.inputType.createType())`.

### Dependency graph

```
tramai-standalone
  Depends on:
    - tramai-core (required) — annotations, ProviderRegistry, ModelProvider, OperationObserver
    - tramai-engine (required) — TramaiEngine, ToolRegistry, CircuitBreakerSettings, RetryPolicySettings, TokenBudgetSettings, OperationResponseCache
    - tramai-structured (required) — JacksonStructuredOutputHandler

  Depended on by:
    - Application code (end-user entry point)
    - tramai-spring-core (api dependency — `api(project(":tramai-standalone"))`)
```

### What `create<T>()` does end-to-end

```
1. User calls Tramai.builder()...build().create<MyService>()
2. Constructor assembles ProviderRegistry, ToolRegistry, engine settings
3. create() instantiates TramaiEngine with all components + JacksonStructuredOutputHandler
4. TramaiEngine.create(MyService::class) generates a JDK dynamic proxy
5. Each @Operation method invocation:
   a. Engine builds ModelRequest (model, messages, parameters)
   b. ProviderRegistry resolves provider for the operation
   c. Engine calls provider.complete(request) or .stream(request)
   d. If return type != String → structured output handler parses + validates response
   e. Typed result (or exception) returned to caller
```

### Module boundary

```
Public:
  dev.tramai.standalone.Tramai
    companion: builder(): Builder
    create<T>(KClass<T>): T          — service proxy with structured output
    create<T>(): T (reified inline)  — convenience overload

  dev.tramai.standalone.Tramai.Builder
    provider(provider, name, default)
    model(modelName, providerName)
    fallbackModel(requested, fallback, provider)
    fallbackProvider(modelName, providerName)
    defaultProvider(providerName)
    tools(vararg tools)
    tools(tools: Iterable)
    observer(observer)
    interceptor(interceptor)
    cache(cache)
    circuitBreaker(settings)
    retryPolicy(settings)
    tokenBudget(settings)
    build(): Tramai

  dev.tramai.standalone.Tramai(configure)  — top-level DSL function

Package: dev.tramai.standalone
  1 source file, no sub-packages.
```

### Error model

| Situation | Exception | When |
|-----------|-----------|------|
| Unknown provider in `.model()` | `ConfigurationException` | At `build()` time, via `ProviderRegistry.build()` |
| Missing model mapping + no default | `ConfigurationException` | At invocation time, via `ProviderRegistry.resolveCandidates()` |
| Duplicate tool name in `.tools()` | `ConfigurationException` | At Builder configuration time |
| Structured output parse failure | `StructuredOutputException` | At invocation time, inside engine |
| Provider network/timeout failure | `ProviderTransportException` | At invocation time, inside engine |

### Testing strategy

- `tramai-standalone` is a thin composition layer; its own tests cover runtime assembly (`Tramai` / `TramaiRuntime` wiring)
- Correctness is verified through `tramai-engine` tests (proxy dispatch, routing, retry) and `tramai-structured` tests (schema generation, parsing, validation)
- Integration tests in provider modules (`tramai-ollama`, `tramai-openai`, `tramai-anthropic`) cover the full `Tramai.builder()...create<T>()` path
