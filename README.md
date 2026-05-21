# TramAI — Type-safe AI for the JVM

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

## Customer Support Agent in 20 Lines

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.AiDescription
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.System as SystemMessage
import dev.tramai.core.annotations.User as UserMessage
import dev.tramai.core.model.ToolExecutionContext
import dev.tramai.core.model.TramaiTool
import dev.tramai.ollama.OllamaProvider
import dev.tramai.standalone.Tramai
import dev.tramai.standalone.create

@AiService
interface SupportAgent {
    @SystemMessage("You are a Tier-1 support agent. Be concise.")
    @UserMessage("Customer issue: {message}")
    @Operation(model = "gemma4:e2b", tools = ["lookupOrder"])
    suspend fun handle(message: String): Response
}

data class Response(
    @AiDescription("Answer to the customer") val answer: String,
    @AiDescription("Action taken, if any") val action: String? = null
)

val lookupOrderTool = object : TramaiTool<String, String> {
    override val name = "lookupOrder"
    override val description = "Look up an order by ID"
    override val inputType = String::class
    override suspend fun execute(input: String, ctx: ToolExecutionContext): String =
        "Order $input: shipped on 2026-04-15"
}

suspend fun main() {
    val agent = Tramai.builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma3:4b", "ollama")
        .tools(lookupOrderTool)
        .build()
        .create<SupportAgent>()

    val result = agent.handle("Where is my order #ORD-42?")
    println(result.answer) // "Your order ORD-42 was shipped on April 15."
}
```

**Prerequisites:** `ollama pull gemma4:e2b` (no API key needed).

This example demonstrates everything TramAI is built for — annotations, tool calling, structured output, and local AI — in a single file you can copy, paste, and run.

---

## Why TramAI?

Most AI frameworks ask you to reorganize your application around the framework. TramAI starts from the opposite assumption:

- **Your service boundaries are already real** — annotate an interface, don't learn a framework DSL
- **Your domain types already matter** — structured output is the default, not an add-on
- **Production code should be testable** — deterministic mocks without a live model
- **Production infrastructure should be opt-in** — add observability, orchestration, and platform features only when you need them

| Comparison | Spring AI / LangChain4j | TramAI |
|------------|------------------------|--------|
| Core abstraction | Chain / Agent | `@AiService` interface |
| Structured output | Optional add-on | Default for non-`String` returns |
| Provider routing | Heuristic model matching | Explicit `ProviderRegistry` |
| Observability | Spring Boot Actuator | Optional `tramai-observability` module |
| Workflows | Built into the framework | Explicit, typed, modular |

## Quick Start

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.tramai:tramai-standalone:0.3.0")
    implementation("dev.tramai:tramai-ollama:0.3.0")
}
```

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation
import dev.tramai.core.annotations.System as SystemMessage
import dev.tramai.core.annotations.User as UserMessage
import dev.tramai.ollama.OllamaProvider
import dev.tramai.standalone.Tramai
import dev.tramai.standalone.create

@AiService
interface ChatService {
    @SystemMessage("You are a helpful assistant.")
    @UserMessage("What is the capital of France?")
    @Operation(model = "gemma4:e2b")
    suspend fun ask(): String
}

suspend fun main() {
    val chat = Tramai.builder()
        .provider(OllamaProvider("http://localhost:11434"), default = true)
        .model("gemma3:4b", "ollama")
        .build()
        .create<ChatService>()

    println(chat.ask()) // "Paris"
}
```

## Modules

TramAI is modular by design, but not every repository module carries the same maturity or publication expectations.

### Stable Consumer Modules

These are the modules most application teams should start with.

| Module | What it does | When to add |
|--------|-------------|-------------|
| `tramai-core` | Annotations + contracts + SPIs + Capability API | Always (transitive) |
| `tramai-engine` | Proxy dispatch + execution + retry | Always (transitive) |
| `tramai-standalone` | Framework-free entry point | Non-Spring projects |
| `tramai-spring` | Spring Boot auto-configuration | Spring Boot projects |
| `tramai-ollama` | Local AI (Ollama) | Development / privacy |
| `tramai-openai` | OpenAI + compatible APIs | Cloud deployment |
| `tramai-azure-openai`| Azure OpenAI API | Enterprise deployment |
| `tramai-anthropic` | Claude via Anthropic API | Anthropic shop |
| `tramai-bedrock` | AWS Bedrock | AWS ecosystem |
| `tramai-gemini` | Google Gemini API | GCP ecosystem |
| `tramai-deepseek` | DeepSeek AI API | DeepSeek users |
| `tramai-structured` | JSON Schema generation + validation | Non-`String` return types |
| `tramai-memory` | Chat memory implementations (`PersistentChatMemory`, etc.) | When conversation context is required |
| `tramai-orchestration`| Multi-step workflows & worker pool | Complex pipelines |
| `tramai-rag` | Retrieval-Augmented Generation pipeline | Document knowledge integration |
| `tramai-embedding` | Embedding models | Core element of RAG flows |
| `tramai-vectorstore-spi`| Vector store abstractions | When storing embeddings |
| `tramai-vectorstore-chroma`| ChromaDB vector store adapter | Fast local/network vector store |
| `tramai-vectorstore-pgvector`| PostgreSQL pgvector vector store adapter | Relational DB vector integration |
| `tramai-observability`| OpenTelemetry spans and worker events | Distributed tracing |
| `tramai-testing` | Mock providers + assertions | Test scope only |
| `tramai-bom` | Version alignment | Multi-module projects |

### Runtime And Platform Modules

The repository also contains higher-level operational modules. These are real modules, but they are newer surfaces and should be treated as fast-moving unless the versioned API docs say otherwise.

| Module | What it does | Typical use |
|--------|-------------|-------------|
| `tramai-memory-store` | Durable chat memory store implementations and SPI support | Persisting memory beyond one JVM |
| `tramai-scheduler` | Cron / delay triggers | Time-based workflows |
| `tramai-server` | HTTP API + webhooks + SSE | Remote workflow execution |
| `tramai-mcp` | MCP server adapter | MCP ecosystem integration |
| `tramai-platform` | Multi-tenancy + API keys + plugins | SaaS and governed deployments |
| `tramai-dashboard` | Vue 3 admin UI | Visual operations |

## Choose Your Path

- **Fastest first success:** [Quickstart Guide](docs/guides/quickstart.md)
- **Spring Boot:** [Spring Boot Integration](docs/guides/spring-boot.md)
- **No framework:** [Standalone Usage](docs/guides/standalone-usage.md)
- **Which modules do I need?** [Module Guide](docs/module-guide.md)

## Feature Set

### Core library
- `@AiService` + `@System` + `@User` + `@Operation` annotation model
- **Structured output** with schema generation, validation, and retry feedback
- **Multimodal / Vision Support** (`ContentPart` modeling with `ImageUrlContent` and `ImagePart`)
- Comprehensive model support (OpenAI, Azure, Anthropic, Ollama, Bedrock, Gemini, DeepSeek)
- Configurable capability validation (`ProviderCapability.VISION`) directly evaluated before execution
- Detailed token awareness & image usage counting
- Streaming responses (`Flow<StreamChunk>`), tool calling, retry, circuit breaker, caching
- OpenTelemetry operation, distributed workflow, and worker pool observers (SIGTERM hooks, leases, fencing)
- Deterministic testing with `tramai-testing`

### Runtime and operations
- **Memory Management:** Ready-to-use memory layers (`TokenAwareChatMemory`, `PersistentChatMemory`) solving token limit exhaustion across multi-turn exchanges.
- **RAG & Vector Stores:** Extensible ingestion, chunking, embedding, and retrieval pipeline with Chroma and PGVector implementations.
- Typed workflow orchestration with checkpoint/resume
- Worker pool with lease-based work stealing and graceful shutdown bounds
- Cron scheduling with durable stores
- HTTP API for workflows, webhooks, SSE streams
- MCP adapter, multi-tenancy, API keys, plugins, audit, dashboard

These runtime and platform capabilities are intentionally optional. The core TramAI story remains typed `@AiService` contracts, explicit provider routing, structured output, and opt-in observability.

## Installation

TramAI `0.3.0` targets **JVM 21+**.

```kotlin
// Gradle — use BOM for version alignment
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.3.0"))
    implementation("dev.tramai:tramai-standalone")
    implementation("dev.tramai:tramai-ollama")
}
```

```xml
<!-- Maven -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.tramai</groupId>
      <artifactId>tramai-bom</artifactId>
      <version>0.3.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## Documentation

- [Docs Index](docs/README.md)
- [Getting Started](docs/guides/getting-started.md)
- [Module Guide](docs/module-guide.md)
- [Architecture](docs/architecture/overview.md)
- [Changelog](CHANGELOG.md)

## License

Apache License 2.0
