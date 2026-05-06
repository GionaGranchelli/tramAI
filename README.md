# TramAI — Type-safe AI for the JVM

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

## Customer Support Agent in 20 Lines

```kotlin
@AiService
interface SupportAgent {
    @System("You are a Tier-1 support agent. Be concise.")
    @User("Customer issue: {message}")
    @Operation(model = "gemma3:4b", tools = ["lookupOrder"])
    suspend fun handle(message: String): Response
}

data class Response(
    @AiDescription("Answer to the customer") val answer: String,
    @AiDescription("Action taken, if any") val action: String? = null
)

@Service
class OrderTool {
    @AiTool(description = "Look up an order by ID")
    fun lookupOrder(@AiDescription("Order UUID") id: String): String =
        "Order $id: shipped on 2026-04-15"
}

@SpringBootApplication @EnableTramai class App

fun main() {
    val ctx = runApplication<App>()
    val agent = ctx.getBean(SupportAgent::class.java)
    println(agent.handle("Where is my order #ORD-42?").answer)
    // "Your order ORD-42 was shipped on April 15."
}
```

**Prerequisites:** `ollama pull gemma3:4b` (no API key needed).

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
    implementation("dev.tramai:tramai-standalone:0.2.0")
    implementation("dev.tramai:tramai-ollama:0.2.0")
}
```

```kotlin
import dev.tramai.core.annotations.*
import dev.tramai.ollama.OllamaProvider
import dev.tramai.standalone.Tramai

@AiService
interface ChatService {
    @System("You are a helpful assistant.")
    @User("What is the capital of France?")
    @Operation(model = "gemma3:4b")
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

TramAI is modular by design. Pick what you need, ignore the rest.

| Module | What it does | When to add |
|--------|-------------|-------------|
| `tramai-core` | Annotations + contracts + SPIs | Always (transitive) |
| `tramai-engine` | Proxy dispatch + execution + retry | Always (transitive) |
| `tramai-standalone` | Framework-free entry point | Non-Spring projects |
| `tramai-spring` | Spring Boot auto-configuration | Spring Boot projects |
| `tramai-ollama` | Local AI (Ollama) | Development / privacy |
| `tramai-openai` | OpenAI + compatible APIs | Cloud deployment |
| `tramai-anthropic` | Claude via Anthropic API | Anthropic shop |
| `tramai-structured` | JSON Schema generation + validation | Non-`String` return types |
| `tramai-orchestration` | Multi-step workflows | Complex pipelines |
| `tramai-observability` | OpenTelemetry spans | Need tracing |
| `tramai-testing` | Mock providers + assertions | Test scope only |
| `tramai-bom` | Version alignment | Multi-module projects |
| `tramai-server` | HTTP API + webhooks | Platform deployment |
| `tramai-scheduler` | Cron / delay triggers | Time-based workflows |
| `tramai-mcp` | MCP server adapter | MCP ecosystem |
| `tramai-platform` | Multi-tenancy + API keys + plugins | SaaS deployment |
| `tramai-dashboard` | Vue 3 admin UI | Visual management |

## Choose Your Path

- **Fastest first success:** [Quickstart Guide](docs/guides/quickstart.md)
- **Spring Boot:** [Spring Boot Integration](docs/guides/spring-boot.md)
- **No framework:** [Standalone Usage](docs/guides/standalone-usage.md)
- **Which modules do I need?** [Module Guide](docs/module-guide.md)

## Feature Set

### Core library
- `@AiService` + `@System` + `@User` + `@Operation` annotation model
- Structured output with schema generation, validation, and retry feedback
- Providers for OpenAI, Anthropic, Ollama, and OpenAI-compatible APIs
- Streaming responses, tool calling, retry, circuit breaker, caching
- OpenTelemetry operation and workflow observers
- Deterministic testing with `tramai-testing`

### Runtime and operations
- Typed workflow orchestration with checkpoint/resume
- Worker pool with lease-based work stealing
- Cron scheduling with durable stores
- HTTP API for workflows, webhooks, SSE streams
- MCP adapter, multi-tenancy, API keys, plugins, audit, dashboard

## Installation

TramAI `0.2.0` targets JVM 21+.

```kotlin
// Gradle — use BOM for version alignment
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.2.0"))
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
      <version>0.2.0</version>
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
- [Changelog](docs/releases/CHANGELOG-0.2.0.md)

## License

Apache License 2.0
