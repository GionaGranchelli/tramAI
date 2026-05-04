# Tramai

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

**Tramai** is a structured-first, observability-native AI workflow library for the JVM.

It is built for backend engineers who want AI integration to feel like normal application code:

- one typed interface
- explicit provider routing
- structured output by default
- real failure semantics
- real tests
- optional operational modules when the problem grows

If you want AI in a JVM service without adopting a chain framework or an autonomous-agent programming model, this is the lane.

## Why Tramai

Most AI frameworks ask you to reorganize your application around the framework.

Tramai starts from the opposite assumption:

- your service boundaries are already real
- your domain types already matter
- retries, parsing, timeouts, and observability belong in infrastructure code
- production code should be testable without a live model

So the core abstraction is not a chain, a graph, or an agent loop.

It is an interface method.

## The 30-Second Example

```kotlin
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a structured status.",
        model = "gpt-4o",
    )
    suspend fun analyze(invoiceText: String): InvoiceStatus
}

data class InvoiceStatus(
    val status: String,
    val amount: Double?,
)

val tramai = Tramai {
    provider(OpenAiProvider(apiKey = System.getenv("OPENAI_API_KEY")), name = "openai")
    model("gpt-4o", "openai")
}

val analyzer = tramai.create<InvoiceAnalyzer>()
val result = analyzer.analyze("Vendor: ACME, Total: $150.00")
```

That gives you:

- proxy generation for the service interface
- explicit model-to-provider routing
- structured output handling for `InvoiceStatus`
- retry feedback when the model returns invalid structure

## What It Feels Like In Practice

Tramai is designed so you can start small and grow without throwing away the first version.

### Start small

- annotate one interface
- wire one provider
- return `String` or a DTO

### Add production behavior

- structured output
- retries and timeout control
- tool calling
- OpenTelemetry integration
- deterministic tests with fake providers

### Add workflow runtime only when you need it

- checkpoint/resume
- distributed workers
- scheduling
- HTTP server endpoints
- MCP exposure
- platform features like API keys, plugins, audit, and dashboard

## What Tramai Is Good At

Tramai is strongest when you want:

- extraction and classification
- typed enrichment workflows
- AI-backed service methods inside existing backend code
- explicit multi-step workflows with persisted state
- operational AI systems that need tracing, retries, and auditability

## What It Is Not

Tramai is intentionally **not**:

- a chain framework
- an open-ended autonomous agent framework
- a RAG toolkit
- a memory system
- a prompt-template DSL that takes over your codebase

The orchestration layer is explicit and bounded. It does not smuggle in an agent runtime under a different name.

## Feature Set

### Core library

- `@AiService` proxy generation for Kotlin and Java-friendly service contracts
- structured output with schema generation, extraction, deserialization, and retry feedback
- provider integrations for OpenAI, Anthropic, Ollama, and OpenAI-compatible APIs
- streaming responses
- engine-owned tool calling
- retry, timeout, circuit breaker, token budget, and response-cache controls
- OpenTelemetry operation and workflow observers
- deterministic testing support in `tramai-testing`

### Runtime and operations

- typed workflow orchestration with checkpoint/resume
- worker pool with lease-based work stealing and fencing
- cron scheduling with durable stores
- HTTP server endpoints for workflow runs, webhooks, SSE, schedules, workers, and OpenAPI
- MCP adapter exposing workflows as tools
- platform services for tenants, API keys, rate limiting, plugins, and audit
- optional Vue 3 dashboard module

## Choose Your Path

If you are new to the project, do not start by reading everything.

Pick the path that matches your situation:

- fastest first success: [30-Minute Quickstart](docs/guides/quickstart.md)
- evaluating the library surface: [Getting Started](docs/guides/getting-started.md)
- Maven project: [Maven Setup](docs/guides/maven.md)
- plain JVM app: [Standalone Usage](docs/guides/standalone-usage.md)
- Spring Boot app: [Spring Boot Integration](docs/guides/spring-boot.md)

Good default rule:

- non-Spring app: `tramai-standalone` + one provider
- Spring Boot app: `tramai-spring` + one provider
- add `tramai-observability` only if you want OpenTelemetry
- add `tramai-orchestration` only if you need explicit persisted workflows

Do not start from `tramai-core` unless you are extending Tramai itself.

## Installation

Tramai `0.2.0` targets Java `25+`.

Use the BOM to keep modules aligned.

### Gradle

```kotlin
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.2.0"))
    implementation("dev.tramai:tramai-standalone")
    implementation("dev.tramai:tramai-openai")
}
```

### Maven

```xml
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

Most applications only need:

- `tramai-standalone` or `tramai-spring`
- one provider module

Then add:

- `tramai-observability` for OpenTelemetry
- `tramai-orchestration` for persisted workflows
- `tramai-testing` in test scope

## Module Map

### Published consumer modules

| Module | Responsibility |
| --- | --- |
| `tramai-bom` | Version alignment for published Tramai artifacts. |
| `tramai-core` | Annotations, contracts, shared models, provider SPI, exceptions. |
| `tramai-engine` | Proxy dispatch, execution, retry, timeout, cache, and tool orchestration. |
| `tramai-structured` | Schema generation, extraction, validation, and structured failure analysis. |
| `tramai-standalone` | Minimal framework-free entry point. |
| `tramai-spring` | Spring Boot auto-configuration and bean registration. |
| `tramai-openai` | OpenAI and OpenAI-compatible provider integration. |
| `tramai-anthropic` | Anthropic provider integration. |
| `tramai-ollama` | Ollama provider integration. |
| `tramai-observability` | Optional OpenTelemetry integration. |
| `tramai-orchestration` | Typed workflow orchestration and persistence contracts. |
| `tramai-testing` | Mock providers and deterministic assertions. |

### Repository runtime and platform modules

| Module | Responsibility |
| --- | --- |
| `tramai-scheduler` | Cron scheduling and durable schedule stores. |
| `tramai-server` | HTTP API, webhooks, run management, OpenAPI, and SSE streams. |
| `tramai-mcp` | MCP server adapter exposing workflows as tools. |
| `tramai-platform` | Multi-tenancy, API keys, rate limiting, plugins, and audit. |
| `tramai-dashboard` | Optional Vue 3 admin dashboard served by the runtime. |

## A Good Mental Model

Think of Tramai in layers:

1. **Service layer**: your `@AiService` interfaces
2. **Engine layer**: execution, retries, parsing, routing, tools
3. **Workflow layer**: explicit multi-step coordination when one call is not enough
4. **Runtime layer**: scheduling, server APIs, MCP, workers
5. **Platform layer**: tenancy, auth, plugins, audit, dashboard

You can stop at layer 1 or 2 and still get real value.

That is the point.

## Documentation

### Start here

- [Docs Index](docs/README.md)
- [Getting Started](docs/guides/getting-started.md)
- [Quickstart](docs/guides/quickstart.md)
- [Tutorial: Build an Invoice Analyzer](docs/guides/tutorial-invoice-analyzer.md)

### Core usage

- [Providers and Model Routing](docs/guides/providers.md)
- [Structured Output](docs/guides/structured-output.md)
- [Tool Calling](docs/guides/tool-calling.md)
- [Streaming](docs/guides/streaming.md)
- [Testing](docs/guides/testing.md)
- [Observability](docs/guides/observability.md)

### Workflows and runtime

- [Orchestration](docs/guides/orchestration.md)
- [Orchestration Persistence](docs/guides/orchestration-persistence.md)
- [Workflow Scheduling](docs/guides/scheduling.md)
- [Workflow Server](docs/guides/server.md)
- [MCP Integration](docs/guides/mcp.md)
- [Platform Operations](docs/guides/platform.md)

### Reference and design

- [Architecture Overview](docs/architecture/overview.md)
- [Module Overview](docs/architecture/modules.md)
- [Configuration Reference](docs/reference/configuration.md)
- [API Stability](docs/reference/api-stability.md)
- [0.2.0 Changelog](docs/releases/CHANGELOG-0.2.0.md)
- [Roadmap](docs/roadmap.md)

## Example Project

The repository includes a Kotlin Spring Boot example that shows:

- typed `@AiService` methods
- structured output
- streaming
- tool calling
- persisted orchestration

Start with:

- [examples/kotlin-springboot-example/README.md](examples/kotlin-springboot-example/README.md)

## Contributing

Tramai treats documentation, tests, and module boundaries as first-class design artifacts.

See [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md).

## License

Tramai is released under the [Apache License 2.0](LICENSE).
