# TramAI — AI That Respects Your Code

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

TramAI is a structured-first, observability-native AI workflow library for the JVM. It lets you add AI capabilities through typed interface methods instead of chain DSLs, agent frameworks, or stringly-typed prompt plumbing.

## The Problem

You want AI in your JVM application. What you usually get:

- Raw strings instead of domain types
- Framework-centric abstractions that flatten your service boundaries
- Tool or provider misconfiguration discovered only at runtime
- Tests that depend on live models, network calls, or brittle mocks
- Observability bolted on after the fact

TramAI takes the opposite approach: AI calls should look like normal method calls. Typed inputs. Typed outputs. Explicit configuration. Deterministic tests. Loud failures when correctness cannot be guaranteed.

> TramAI treats an AI call like an HTTP client call. The proxy converts your interface arguments to messages, dispatches through the engine, maps the response, and records standard OTel spans. No background loops, no hidden state, no magic.

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
        .model("gemma4:e2b", "ollama")
        .tools(lookupOrderTool)
        .build()
        .create<SupportAgent>()

    val result = agent.handle("Where is my order #ORD-42?")
    println(result.answer) // "Your order ORD-42 was shipped on April 15."
}
```

Run it with `ollama pull gemma4:e2b`. No API key needed.

What this shows:

- One annotated interface as the primary AI abstraction
- Structured output by returning a `data class`
- Tool calling with startup validation
- Local model execution with no framework requirement

If `lookupOrder` is missing, proxy creation fails with `ConfigurationException` before any request is sent, so no tokens are burned on a broken setup.

## What TramAI Provides

| You write | TramAI handles |
|-----------|---------------|
| `@AiService` interface | Proxy generation at startup |
| `@Operation(model = "...")` | Provider resolution, retry, circuit breaker |
| `data class Response(...)` | Schema generation, structured output, validation |
| `tools = ["lookupOrder"]` | Tool validation at proxy creation |
| `Flow<StreamChunk>` return type | Streaming with backpressure |
| `suspend fun ask(): MyType` | Coroutine dispatch and response mapping |

## Why Teams Pick It

| Dimension | Spring AI / LangChain4j | TramAI |
|-----------|--------------------------|--------|
| Structured output | Optional adapter | **Default** for non-`String` returns |
| Provider routing | Heuristic model parsing | **Explicit** registry |
| Tool validation | None | **Fail-loudly at proxy creation** |
| Provider fallback | Manual | **Automatic** with circuit breaker |
| Token budgets | None | **Hard + soft limits** per operation |
| Response caching | None | **Per-operation** TTL-based |
| Testability | Live model required | **Deterministic mocks** |
| Failure testing | Non-deterministic integration tests | **Zero-network** `SimulatedFailureProvider` |
| Observability | Micrometer (framework-bound) | **Optional** OTel module |
| Framework coupling | Required | **Optional** standalone or Spring |

## Deterministic Testing

`tramai-testing` gives you zero-network tests that prove retries, routing, and failure handling without a live model:

```kotlin
val provider = SimulatedFailureProvider {
    onMethod("summarize").retryableFailure("rate limited", statusCode = 429)
    onMethod("summarize") respondWith "recovered summary"
}
val observer = RecordingOperationObserver()
val tramai = Tramai {
    provider(provider, default = true)
    model("claude-sonnet-4-20250514", "simulated-failure")
    observer(observer)
}
val service = tramai.create<TestRawService>()

val result = runBlocking { service.summarize("invoice-1") }

assertEquals("recovered summary", result)
TramaiAssertions.assertThat(provider, observer)
    .whenCalled("summarize")
    .wasCalledTimes(2)
    .andRetried(1)
    .andObservedFailure(ProviderException::class)
    .emittedProvider("simulated-failure")
```

That test proves exact retry count, provider routing, failure observation, and recovery, all without network access or flaky CI dependencies.

## Getting Started

TramAI `0.3.1` targets **JVM 21+**.

```kotlin
// Gradle
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.3.1"))
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
      <version>0.3.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Paths from here:

- [Quickstart Guide](docs/guides/quickstart.md)
- [Spring Boot Integration](docs/guides/spring-boot.md)
- [Standalone Usage](docs/guides/standalone-usage.md)
- [Testing Guide](docs/guides/testing.md)
- [Module Guide](docs/module-guide.md)
- [Architecture Overview](docs/architecture/overview.md)
- [Docs Index](docs/README.md)

## Modules

### Stable Consumer Modules

| Module | What it does | When to add |
|--------|-------------|-------------|
| `tramai-core` | Annotations, contracts, SPIs, capability API | Always (transitive) |
| `tramai-engine` | Proxy dispatch, execution, retry | Always (transitive) |
| `tramai-standalone` | Framework-free entry point | Non-Spring projects |
| `tramai-spring` | Spring Boot auto-configuration | Spring Boot projects |
| `tramai-structured` | JSON Schema generation and validation | Non-`String` return types |
| `tramai-ollama` | Local AI via Ollama | Development or privacy-sensitive use |
| `tramai-openai` | OpenAI and compatible APIs | Cloud deployment |
| `tramai-azure-openai` | Azure OpenAI API | Enterprise Azure deployments |
| `tramai-anthropic` | Claude via Anthropic API | Anthropic deployments |
| `tramai-bedrock` | AWS Bedrock | AWS ecosystem |
| `tramai-gemini` | Google Gemini API | GCP ecosystem |
| `tramai-deepseek` | DeepSeek API | DeepSeek deployments |
| `tramai-memory` | Chat memory implementations | Multi-turn context |
| `tramai-orchestration` | Multi-step workflows and worker pool | Complex pipelines |
| `tramai-rag` | Retrieval-augmented generation pipeline | Document-backed answers |
| `tramai-embedding` | Embedding models | RAG and semantic search |
| `tramai-vectorstore-spi` | Vector store abstractions | When storing embeddings |
| `tramai-vectorstore-chroma` | ChromaDB adapter | Local or network vector store |
| `tramai-vectorstore-pgvector` | PostgreSQL pgvector adapter | Relational vector search |
| `tramai-observability` | OpenTelemetry spans and worker events | Distributed tracing |
| `tramai-testing` | Mock providers and assertions | Test scope |
| `tramai-bom` | Version alignment | Multi-module builds |

### Runtime And Platform Modules

| Module | What it does | Typical use |
|--------|-------------|-------------|
| `tramai-memory-store` **Experimental** | Durable chat memory storage and SPI support | Persisting memory beyond one JVM |
| `tramai-scheduler` **Experimental** | Cron and delay triggers | Time-based workflows |
| `tramai-server` **Experimental** | HTTP API, webhooks, SSE | Remote workflow execution |
| `tramai-mcp` **Experimental** | MCP server adapter | MCP ecosystem integration |
| `tramai-persistence-file` **Experimental** | Encrypted file-backed approval, continuation, and audit stores | Sovereign single-node persistence |
| `tramai-platform` **Experimental** | Multi-tenancy, API keys, plugins | SaaS and governed deployments |
| `tramai-dashboard` **Experimental** | Vue 3 admin UI | Visual operations |

## Feature Set

Core library:

- `@AiService`, `@System`, `@User`, and `@Operation` annotations
- Structured output with schema generation, validation, and retry feedback
- Tool calling, streaming, retry, circuit breaker, and response caching
- Multimodal support with capability validation before execution
- Explicit provider registry instead of model-name heuristics
- OpenTelemetry integration that remains opt-in at the dependency level
- Deterministic testing through `tramai-testing`

Runtime and operations:

- Token-aware and persistent memory implementations
- RAG ingestion, chunking, embeddings, retrieval, and vector stores
- Typed workflow orchestration with checkpoint and resume behavior
- Worker pool execution with leases, fencing, and graceful shutdown
- Scheduling, server, MCP, platform, audit, and dashboard modules

These higher-level runtime capabilities are intentionally optional. The core TramAI story remains typed `@AiService` contracts, explicit provider routing, structured output, and opt-in observability.

## Documentation And Roadmap

- [Documentation](docs/README.md)
- [Getting Started](docs/guides/getting-started.md)
- [Changelog](CHANGELOG.md)
- [Roadmap](ROADMAP.md)

## License

Apache License 2.0
