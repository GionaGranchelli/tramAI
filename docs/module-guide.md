# TramAI Module Guide

> **Purpose:** Help you choose the right TramAI modules for your JVM project.
> **Reading time:** L1 (30s) → L2 (10min) → L3 (20min)
> **Build coordinates:** `dev.tramai:<module>:0.2.0`

---

## L1: Quick Start — Decision Flowchart

```
┌───────────────────────────────────────────┐
│  I want to add AI to my JVM application   │
└─────────────────────┬─────────────────────┘
                      │
                      ▼
┌───────────────────────────────────────────┐
│  Which framework are you using?            │
├─────────────────────┬─────────────────────┤
│  Spring Boot        │  None / Ktor / Javalin / CLI │
└─────────┬───────────┴──────────┬──────────┘
          │                      │
          ▼                      ▼
  Add tramai-spring       Add tramai-standalone
  (auto-discovers         (builder API, no
   @AiService beans)       framework needed)
          │                      │
          └──────────┬───────────┘
                     ▼
    ┌────────────────────────────────────┐
    │  Which LLM provider?               │
    ├──────────┬──────────┬──────────────┤
    │  Local   │  OpenAI  │  Anthropic   │
    │  Ollama  │  API     │  Claude API  │
    ├──────────┼──────────┼──────────────┤
    │ ollama   │ openai   │ anthropic    │
    └──────────┴──────────┴──────────────┘
                     │
                     ▼
    ┌────────────────────────────────────┐
    │  Do you need structured output?    │
    │  (return types other than String)  │
    ├────────────────┬───────────────────┤
    │  YES           │  NO               │
    ├────────────────┴───────────────────┤
    │  Add tramai-structured             │
    └────────────────┬───────────────────┘
                     │
                     ▼
    ┌────────────────────────────────────┐
    │  Do you need multi-step workflows? │
    ├────────────────┬───────────────────┤
    │  YES           │  NO               │
    ├────────────────┴───────────────────┤
    │  Add tramai-orchestration          │
    └────────────────┬───────────────────┘
                     │
                     ▼
    ┌────────────────────────────────────┐
    │  Production deployment needs?      │
    ├──────────┬──────────┬──────────────┤
    │Scheduling│ REST API │ Multi-tenant │
    ├──────────┼──────────┼──────────────┤
    │scheduler │  server  │  platform    │
    └──────────┴──────────┴──────────────┘
```

### Quick Decision Table

| Your situation | Required modules | Optional modules |
|---------------|-----------------|-----------------|
| Local model, quick script | core, engine, ollama | structured, testing |
| Spring Boot + OpenAI | core, engine, spring, openai | structured, observability, testing |
| Production REST API | core, engine, spring, openai, server, scheduler | platform, mcp |
| Data extraction pipeline | core, engine, ollama, structured | orchestration |
| Multi-step agent workflow | core, engine, ollama, orchestration | scheduler, server |
| SaaS / multi-tenant | core, engine, server, orchestration, platform | dashboard |

### Minimal Dependency Template

```kotlin
// build.gradle.kts — always start here
repositories {
    mavenCentral()
}

dependencies {
    // Always needed
    implementation("dev.tramai:tramai-core:0.2.0")
    implementation("dev.tramai:tramai-engine:0.2.0")

    // Pick ONE provider
    implementation("dev.tramai:tramai-ollama:0.2.0")   // local models
    // implementation("dev.tramai:tramai-openai:0.2.0") // OpenAI / compatible APIs
    // implementation("dev.tramai:tramai-anthropic:0.2.0") // Claude

    // Optional: structured output
    implementation("dev.tramai:tramai-structured:0.2.0")

    // Pick adapter
    // implementation("dev.tramai:tramai-standalone:0.2.0") // No framework
    // implementation("dev.tramai:tramai-spring:0.2.0")     // Spring Boot
}
```

---

## L2: Module Reference

### Consumer Modules

| Module | Layer | Purpose | Depends on | Artifact | When to use | When NOT to use |
|--------|-------|---------|-----------|----------|-------------|----------------|
| `tramai-core` | Core | Annotations (`@AiService`, `@Operation`, `@AiTool`, `@SystemPrompt`, `@AiDescription`) + model types (`Message`, `ModelRequest`, `ModelResponse`, `StreamChunk`) + SPIs (`ModelProvider`, `StructuredOutputHandler`, `OperationInterceptor`, `ProviderRegistry`, `SecretValueResolver`) | none | `tramai-core:0.2.0` | **Always.** Every TramAI project needs this. | Never — this is the foundation. |
| `tramai-engine` | Core | Proxy generation from `@AiService` interfaces, method dispatch, retry policy, circuit breaker, response caching, token budgeting, tool registry | core | `tramai-engine:0.2.0` | **Always** in production. In tests, use `tramai-testing` with mock providers. | For test-only scenarios where you mock the full stack. |
| `tramai-structured` | Core | JSON Schema generation from Kotlin types → structured LLM response → parse → validate → retry on failure. Uses Jackson under the hood. | core | `tramai-structured:0.2.0` | Your service method returns `data class`, `enum`, `List<T>`, `Map<K,V>`, or any non-`String` type. | You only return `String` responses. |
| `tramai-observability` | Core | OpenTelemetry spans for operations, retries, workflow steps, structured output events. Optional at the dependency level. | core, orchestration | `tramai-observability:0.2.0` | You need distributed tracing, metrics, or audit trails for AI calls. | You don't use OpenTelemetry or don't need observability. |
| `tramai-ollama` | Provider | Ollama provider via OpenAI-compatible API endpoint. Single class: `OllamaProvider`. | core | `tramai-ollama:0.2.0` | Running local models (Llama, Gemma, Qwen, Mistral, etc.) via Ollama. Development. Privacy-sensitive. Offline. No API key. | You need a cloud-hosted proprietary model. |
| `tramai-openai` | Provider | OpenAI provider + OpenAI-compatible API providers (Together, vLLM, Groq, etc.). Includes `ExperimentalCodexAuth`. | core | `tramai-openai:0.2.0` | Using GPT-4o, o-series, or any OpenAI-compatible endpoint. | You only use local models. |
| `tramai-anthropic` | Provider | Anthropic Messages API provider for Claude models. | core | `tramai-anthropic:0.2.0` | Using Claude (Sonnet, Haiku, Opus). | You don't use Anthropic. |
| `tramai-standalone` | Adapter | Framework-free entry point. The `Tramai` builder wires core + engine + structured + a provider into a single callable instance. | core, engine, structured | `tramai-standalone:0.2.0` | You don't use Spring Boot. CLI tools, library embeddings, Ktor, Javalin, http4k, plain Kotlin scripts. | You use Spring Boot (use `tramai-spring` instead). |
| `tramai-spring` | Adapter | Spring Boot auto-configuration. `@EnableTramai` discovers `@AiService` interfaces, `@AiTool` beans, and provider configs from `application.yml`. | core, engine, providers (optional) | `tramai-spring:0.2.0` | You use Spring Boot 3.x. | You don't use Spring. |
| `tramai-testing` | Tooling | Mock providers (`MockAiProvider`), assertion helpers (`TramaiAssertions`), recording observers (`RecordingOperationObserver`), failure simulation (`SimulatedFailureProvider`), request recording (`RecordedRequestProvider`). | core | `tramai-testing:0.2.0` | **Always in tests.** Never in production. | Never — use in `testImplementation` only. |
| `tramai-bom` | Tooling | Bill of Materials — aligns all TramAI module versions. Zero code. | none | `tramai-bom:0.2.0` | You have a multi-module project importing multiple TramAI modules. | Single-module project. |

### Platform Modules

| Module | Layer | Purpose | Depends on | Artifact | When to use | When NOT to use |
|--------|-------|---------|-----------|----------|-------------|----------------|
| `tramai-orchestration` | Orchestration | Multi-step workflow DSL with typed state: `aiStep`, `localStep`, `parallelStep`, `branchStep`, `gateStep`, `httpStep`, `shellStep`, `codexStep`, `hermesStep`, `mcpStep`, `delayStep`. Builder methods on `AbstractWorkflowBuilder`. Checkpoint/resume (file, JDBC, Markdown). Lease fencing for distributed workers. | core | `tramai-orchestration:0.2.0` | A single `@Operation` isn't enough. You need chained AI calls, conditional branching, parallel execution, human-in-the-loop gates, or checkpoint/resume. | Single `@Operation` per request. Simple request-response. |
| `tramai-scheduler` | Platform | Cron/delay scheduling for workflows. `ScheduledWorkflowTimer`, `InMemoryWorkflowSchedulerStore`, `JdbcWorkflowSchedulerStore`. | orchestration | `tramai-scheduler:0.2.0` | You need time-based workflow triggers (cron jobs, delayed execution). | Your workflows are triggered by user requests only. |
| `tramai-server` | Platform | HTTP API (Spring Boot-based). Controllers for workflows, workers, schedules, audit logs. Webhook support with signature verification. SSE streaming. | orchestration, scheduler | `tramai-server:0.2.0` | You want to expose TramAI operations via REST API. Need webhooks. Need worker management. | Embedded/standalone usage only. |
| `tramai-mcp` | Platform | MCP (Model Context Protocol) server adapter. Exposes workflows as MCP tools. | server, structured | `tramai-mcp:0.2.0` | You want to expose TramAI workflows as MCP tools for AI agents (Claude Desktop, etc.). | You don't use MCP ecosystem. |
| `tramai-platform` | Platform | Multi-tenancy: API keys, tenant isolation, rate limiting, audit log, runtime plugins (`PluginManager`, `PluginWorkflowStartupValidator`). | orchestration, server | `tramai-platform:0.2.0` | Multi-team deployment, SaaS offering, or any scenario requiring tenant isolation. | Single-tenant deployment. Early development. |
| `tramai-dashboard` | Platform | Vue 3 admin UI (auto-configured by `DashboardAutoConfiguration`). Run history, schedule management, workflow visualization. | (none — embedded UI) | `tramai-dashboard:0.2.0` | You need a graphical interface to manage workflows, schedules, and view run history. | Headless deployment only. |

---

## L2: Quick-Start Recipes

### Recipe 1: Chat with a Local Model in 5 Minutes

**Goal:** Send a prompt to a local Ollama model and get a response.

**Modules:** `tramai-core` + `tramai-engine` + `tramai-ollama` + `tramai-standalone`

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-core:0.2.0")
    implementation("dev.tramai:tramai-engine:0.2.0")
    implementation("dev.tramai:tramai-ollama:0.2.0")
    implementation("dev.tramai:tramai-standalone:0.2.0")
}
```

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.ollama.OllamaProvider
import dev.tramai.standalone.Tramai

@AiService
interface ChatService {
    @Operation(prompt = "What is the capital of France?")
    suspend fun ask(): String
}

suspend fun main() {
    val service = Tramai
        .builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma3:4b", "ollama")
        .build()
        .create<ChatService>()

    val answer = service.ask()
    println(answer) // "Paris"
}
```

**Expected output:** `Paris` (or similar, depending on the model)

**Time:** ~5 minutes if Ollama is already running.

---

### Recipe 2: Spring Boot + OpenAI

**Goal:** Chat with GPT-4o from a Spring Boot application.

**Modules:** `tramai-core` + `tramai-engine` + `tramai-openai` + `tramai-spring`

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-core:0.2.0")
    implementation("dev.tramai:tramai-engine:0.2.0")
    implementation("dev.tramai:tramai-openai:0.2.0")
    implementation("dev.tramai:tramai-spring:0.2.0")
}
```

```yaml
# application.yml
tramai:
  openai:
    api-key: ${OPENAI_API_KEY}
    model: gpt-4o
```

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.spring.EnableTramai
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableTramai
class App

@AiService
interface ChatService {
    @Operation(prompt = "Explain recursion in one sentence")
    suspend fun explain(): String
}

fun main() = runApplication<App>()
```

```kotlin
// In a controller or service
@Service
class MyService(private val chat: ChatService) {
    suspend fun answer() = chat.explain()
}
```

**Time:** ~10 minutes with an existing Spring Boot project.

---

### Recipe 3: Structured Data Extraction

**Goal:** Extract structured data (a `Person` record) from unstructured text.

**Modules:** `tramai-core` + `tramai-engine` + `tramai-structured` + `tramai-ollama` + `tramai-standalone`

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-core:0.2.0")
    implementation("dev.tramai:tramai-engine:0.2.0")
    implementation("dev.tramai:tramai-structured:0.2.0")
    implementation("dev.tramai:tramai-ollama:0.2.0")
    implementation("dev.tramai:tramai-standalone:0.2.0")
}
```

```kotlin
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.ollama.OllamaProvider
import dev.tramai.standalone.Tramai

data class Person(
    @AiDescription("Full name of the person")
    val name: String,
    @AiDescription("Age in years")
    val age: Int,
    @AiDescription("City of residence")
    val city: String,
)

@AiService
interface ExtractionService {
    @Operation(prompt = "Extract person info from: \"John is a 32-year-old developer from Berlin\"")
    suspend fun extract(): Person
}

suspend fun main() {
    val service = Tramai
        .builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma3:4b", "ollama")
        .build()
        .create<ExtractionService>()

    val person = service.extract()
    println(person) // Person(name=John, age=32, city=Berlin)
}
```

**Time:** ~10 minutes.

---

### Recipe 4: Multi-Step Workflow

**Goal:** A workflow that: (1) classifies customer feedback, (2) generates a response, (3) stores the result.

**Modules:** `tramai-core` + `tramai-engine` + `tramai-structured` + `tramai-orchestration` + `tramai-ollama`

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-core:0.2.0")
    implementation("dev.tramai:tramai-engine:0.2.0")
    implementation("dev.tramai:tramai-structured:0.2.0")
    implementation("dev.tramai:tramai-ollama:0.2.0")
    implementation("dev.tramai:tramai-orchestration:0.2.0")
    implementation("dev.tramai:tramai-standalone:0.2.0")
}
```

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.ollama.OllamaProvider
import dev.tramai.orchestration.workflow
import dev.tramai.standalone.Tramai

// --- Service interfaces (each backed by an @Operation) ---

@AiService
interface ClassifierService {
    @Operation(prompt = "Classify this feedback as POSITIVE, NEGATIVE, or NEUTRAL")
    suspend fun classify(text: String): String
}

@AiService
interface ResponderService {
    @Operation(prompt = "Write a polite response to this feedback")
    suspend fun respond(sentiment: String, text: String): String
}

// --- Workflow state ---

data class FeedbackState(
    val originalText: String = "",
    val classification: String = "",
    val response: String = "",
)

// --- Main ---

suspend fun main() {
    val classifier = Tramai.builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma3:4b", "ollama")
        .build()
        .create<ClassifierService>()

    val responder = Tramai.builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma3:4b", "ollama")
        .build()
        .create<ResponderService>()

    val workflow = workflow<FeedbackState>("feedback-pipeline") {
        aiStep(
            name = "classify",
            input = { state -> state.originalText },
            invoke = { text -> classifier.classify(text) },
            merge = { state, classification -> state.copy(classification = classification) },
        )
        aiStep(
            name = "respond",
            input = { state -> Pair(state.classification, state.originalText) },
            invoke = { (sentiment, text) -> responder.respond(sentiment, text) },
            merge = { state, response -> state.copy(response = response) },
        )
    }.build { state -> state.response }

    val result = workflow.run(FeedbackState(originalText = "Your product is amazing but the UI is confusing."))
    println(result) // The generated response
}
```

**Time:** ~20 minutes.

---

### Recipe 5: Production REST API with Scheduling

**Goal:** Expose workflows via HTTP API with cron triggers.

**Modules:** `tramai-core` + `tramai-engine` + `tramai-orchestration` + `tramai-server` + `tramai-scheduler`

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-core:0.2.0")
    implementation("dev.tramai:tramai-engine:0.2.0")
    implementation("dev.tramai:tramai-orchestration:0.2.0")
    implementation("dev.tramai:tramai-server:0.2.0")
    implementation("dev.tramai:tramai-scheduler:0.2.0")
}
```

```yaml
# application.yml
tramai:
  server:
    port: 8080
  scheduler:
    enabled: true
```

The server auto-exposes:
- `POST /workflows` — create and run a workflow
- `GET /workflows/{id}` — get workflow status
- `POST /schedules` — create a cron schedule
- `GET /schedules` — list active schedules

**Time:** ~15 minutes if you have a workflow defined.

---

### Recipe 6: MCP Tool from a Workflow

**Goal:** Expose a TramAI workflow as an MCP tool for Claude Desktop.

**Modules:** `tramai-core` + `tramai-engine` + `tramai-orchestration` + `tramai-server` + `tramai-mcp`

```yaml
# application.yml
tramai:
  mcp:
    enabled: true
    server-name: tramai-workflows
```

No extra code needed. Every registered workflow in `tramai-orchestration` becomes an MCP tool automatically.

**Time:** ~5 minutes of configuration.

---

## L3: Architecture Overview

### Runtime Architecture Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                     APPLICATION CODE                              │
│  @AiService interface (declarative contract)                      │
│  @AiTool beans / methods                                         │
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  tramai-engine                                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐  │
│  │ Proxy        │  │ RetryPolicy  │  │ OperationResponseCache │  │
│  │ Generator    │  │ + CB         │  │ (InMemory impl)        │  │
│  └──────┬───────┘  └──────────────┘  └────────────────────────┘  │
│         │                                                        │
│  ┌──────▼───────┐  ┌──────────────┐  ┌────────────────────────┐  │
│  │ ToolRegistry │  │ TokenBudget  │  │ CircuitBreaker         │  │
│  └──────┬───────┘  └──────────────┘  └────────────────────────┘  │
└─────────┼────────────────────────────────────────────────────────┘
          │
┌─────────▼────────────────────────────────────────────────────────┐
│  tramai-core (Provider SPI)                                       │
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐  │
│  │ ModelProvider │  │ ProviderRegistry│  │ StreamCapable          │  │
│  │ (interface)   │  │  (registry)  │  │ (interface)            │  │
│  └──────┬───────┘  └──────────────┘  └────────────────────────┘  │
│         │                                                        │
│  ┌──────▼───────┐  ┌──────────────┐  ┌────────────────────────┐  │
│  │OperationObserver  │  │SecretValueResolver│  │ StructuredOutputHandler │  │
│  │ (SPI)        │  │ (SPI)        │  │ (SPI)                  │  │
│  └──────────────┘  └──────────────┘  └────────────────────────┘  │
└─────────┬────────────────────────────────────────────────────────┘
          │
          ▼
┌──────────────────────────────────────────────────────────────────┐
│  PROVIDERS                                                       │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐                  │
│  │ Ollama     │  │ OpenAI     │  │ Anthropic  │                  │
│  │Provider    │  │Provider    │  │Provider    │                  │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘                  │
│        │               │               │                         │
│  HTTP POST          HTTP POST       HTTP POST                    │
│  localhost:11434   api.openai.com  api.anthropic.com              │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  OPTIONAL PLUGINS                                                │
│                                                                   │
│  tramai-structured        tramai-observability                    │
│  ┌────────────────────┐  ┌────────────────────────────────┐       │
│  │ JacksonStructOut   │  │ OpenTelemetryOperationObserver │       │
│  │ Handler            │  │ OpenTelemetryWorkflowObserver  │       │
│  │ (schema→validate→ │  │ (spans for ops + workflows)    │       │
│  │  retry on fail)    │  └────────────────────────────────┘       │
│  └────────────────────┘                                          │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  FRAMEWORK ADAPTERS                                              │
│                                                                   │
│  tramai-standalone                  tramai-spring                 │
│  ┌──────────────────────┐          ┌───────────────────────────┐  │
│  │ Tramai.builder()     │          │ @EnableTramai             │
│  │  .provider(Ollama)  │          │ TramaiAutoConfiguration   │
│  │  .model(...)        │          │ AiServiceFactoryBean      │
│  │  .build()           │          │ AiToolScanner             │
│  │  .create<Service>() │          │                           │
│                                     └───────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  ORCHESTRATION LAYER                                              │
│                                                                   │
│  tramai-orchestration                                             │
│  ┌──────────────────────────────────────────────────────────┐    │
│  │ Workflow { AiStep, LocalStep, ParallelStep, BranchStep,  │    │
│  │   GateStep, HttpStep, ShellStep, CodexStep, HermesStep,  │    │
│  │   McpStep }                                              │    │
│  ├──────────────────────────────────────────────────────────┤    │
│  │ Checkpoint: FileWorkflowCheckpointStore (file, JDBC, MD) │    │
│  │ Leasing:   FileWorkflowLeaseStore / JdbcWorkflowLease    │    │
│  │ Workers:   TramaiWorker + WorkerRegistryStore            │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                   │
│  tramai-scheduler              tramai-server                     │
│  ┌────────────────────────┐   ┌────────────────────────────┐     │
│  │ ScheduledWorkflowTimer  │   │ WorkflowController         │     │
│  │ CronSchedule           │   │ ScheduleController         │     │
│  │ JdbcSchedulerStore     │   │ WorkerController           │     │
│  └────────────────────────┘   │ AuditController            │     │
│                                │ WebhookSignatureVerifier   │     │
│  tramai-mcp                   └────────────────────────────┘     │
│  ┌──────────────────────┐                                        │
│  │ TramaiMcpServer      │     tramai-platform                    │
│  │ McpToolHandlers      │   ┌────────────────────────────┐       │
│  └──────────────────────┘   │ PlatformController          │       │
│                              │ Security (API keys)        │       │
│  tramai-dashboard            │ PluginManager              │       │
│  ┌──────────────────────┐   │ PlatformWorkflowService    │       │
│  │ Vue 3 admin UI       │   └────────────────────────────┘       │
│  │ DashboardSettings    │                                        │
│  └──────────────────────┘                                        │
└──────────────────────────────────────────────────────────────────┘
```

### Module Dependency Graph

```
tramai-core                 (zero deps)
  ├── tramai-engine         (→ core)
  ├── tramai-structured     (→ core)
  ├── tramai-ollama         (→ core)
  ├── tramai-openai         (→ core)
  ├── tramai-anthropic      (→ core)
  ├── tramai-testing        (→ core)
  ├── tramai-orchestration  (→ core)
  │     ├── tramai-observability (→ core, orchestration)
  │     ├── tramai-scheduler     (→ orchestration)
  │     │     └── tramai-server  (→ orchestration, scheduler)
  │     │           ├── tramai-mcp      (→ server, structured)
  │     │           └── tramai-platform (→ orchestration, server)
  │     │                 └── tramai-dashboard
  ├── tramai-standalone     (→ core, engine, structured)
  │     └── tramai-spring   (→ all providers + standalone)
  └── tramai-bom            (zero deps, version alignment only)
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Annotations over YAML/JSON config** | `@AiService` + `@Operation` give you compile-time checks, refactoring support, and discoverability. No DSL to learn. |
| **Kotlin-first, Java-friendly** | Core uses Kotlin features (suspend, data classes, sealed classes) but all SPIs have Java-friendly overloads. |
| **Provider registry, not heuristics** | `ProviderRegistry` explicitly registers providers. No fragile model-name-prefix matching. |
| **Structured output as first-class** | Not an afterthought — the engine delegates to `StructuredOutputHandler` when return type isn't `String`. |
| **Opt-in observability** | `tramai-observability` is a compile-time dependency, never a runtime leak. Engine calls `OperationObserver` list — empty list = no overhead. |
| **Explicit orchestration** | Workflows are explicitly built with `workflow<S>(name) { ... }.build { ... }` DSL, not inferred from annotations. This keeps the core/engine simple. |
