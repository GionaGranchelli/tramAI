# Tramai

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

Tramai is a structured-first, observability-native AI workflow library for the JVM.

It is built for backend engineers who want typed AI integration through interface methods, explicit provider routing, strong testability, and optional operational modules for workflows, scheduling, server APIs, MCP, multi-tenancy, and dashboarding.

## Start Here

If you are evaluating Tramai for the first time, use one of these entry points:

- [30-Minute Quickstart](docs/guides/quickstart.md)
- [Getting Started](docs/guides/getting-started.md)
- [Maven Setup](docs/guides/maven.md)
- [Standalone Usage](docs/guides/standalone-usage.md)
- [Spring Boot Integration](docs/guides/spring-boot.md)

Use this minimum-default rule:

- non-Spring app: `tramai-standalone` + one provider
- Spring Boot app: `tramai-spring` + one provider
- add `tramai-observability` only if you want OpenTelemetry
- add `tramai-orchestration` only if you want typed persisted workflows

Do not start from `tramai-core` unless you are extending Tramai itself.

## Quick Example

```kotlin
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a structured status",
        model = "gpt-4o"
    )
    suspend fun analyze(invoiceText: String): InvoiceStatus
}

data class InvoiceStatus(val status: String, val amount: Double?)

val tramai = Tramai {
    provider(OpenAiProvider(apiKey = System.getenv("OPENAI_API_KEY")), name = "openai")
    model("gpt-4o", "openai")
}

val analyzer = tramai.create<InvoiceAnalyzer>()
val result = analyzer.analyze("Vendor: ACME, Total: $150.00")
```

## What Tramai Optimizes For

- typed contracts over raw prompt plumbing
- structured output as the default path for non-`String` returns
- framework-agnostic core with thin adapters
- explicit provider and tool routing
- OpenTelemetry-friendly observability
- deterministic testing without live model calls

## What It Is Not

- not a chain framework
- not an open-ended autonomous agent framework
- not a RAG or vector-store toolkit

The orchestration surface is explicit and bounded. Tramai does not hide application logic behind a reasoning loop.

## Current Feature Set

Core library features:

- `@AiService` proxy generation for Kotlin and Java-friendly service contracts
- structured output with schema generation, extraction, deserialization, and retry feedback
- provider integrations for OpenAI, Anthropic, Ollama, and OpenAI-compatible APIs
- streaming responses
- engine-owned tool calling
- retry, timeout, circuit breaker, token budget, and response cache controls
- OpenTelemetry operation and workflow observers
- deterministic test helpers in `tramai-testing`

Operational workflow features added in the recent `0.2.0` line:

- typed workflow orchestration with checkpoint/resume
- worker pool with lease-based work stealing and fencing
- cron scheduling with durable stores
- REST server endpoints for workflow runs, workers, schedules, audit, and SSE
- MCP workflow adapter
- platform services for tenants, API keys, rate limiting, plugins, and audit logs
- optional Vue 3 dashboard module

## Installation

Tramai `0.2.0` targets Java `25+`.

Use the BOM to keep consumer modules aligned:

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

Common published consumer setups:

- standalone runtime: `tramai-standalone` + provider
- Spring Boot runtime: `tramai-spring` + provider
- observability: add `tramai-observability`
- orchestration: add `tramai-orchestration`
- tests: add `tramai-testing` in test scope

## Module Map

Published consumer modules:

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

Repository runtime and platform modules:

| Module | Responsibility |
| --- | --- |
| `tramai-scheduler` | Cron scheduling and durable schedule stores. |
| `tramai-server` | HTTP API, webhooks, run management, OpenAPI, and SSE streams. |
| `tramai-mcp` | MCP server adapter exposing workflows as tools. |
| `tramai-platform` | Multi-tenancy, API keys, rate limiting, plugins, and audit. |
| `tramai-dashboard` | Optional Vue 3 admin dashboard served by the runtime. |

## Documentation

- [Docs Index](docs/README.md)
- [Architecture Overview](docs/architecture/overview.md)
- [Module Overview](docs/architecture/modules.md)
- [API Stability](docs/reference/api-stability.md)
- [0.2.0 Changelog](docs/releases/CHANGELOG-0.2.0.md)
- [Roadmap](docs/roadmap.md)

For the newer operational modules, start with:

- [Orchestration Guide](docs/guides/orchestration.md)
- [Orchestration Persistence](docs/guides/orchestration-persistence.md)
- [Workflow Scheduling](docs/guides/scheduling.md)
- [Workflow Server](docs/guides/server.md)
- [MCP Integration](docs/guides/mcp.md)
- [Platform Operations](docs/guides/platform.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Tramai is released under the [Apache License 2.0](LICENSE).
