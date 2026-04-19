# Tramai

Tramai is a structured-first AI integration library for the JVM.

It is built for backend engineers who want to add AI to Kotlin or Java services without adopting a chain or agent framework. The primary Tramai abstraction is an annotated interface method. Inputs stay normal method parameters. Outputs can stay typed. The runtime owns execution, retry, and provider routing. Structured parsing, validation, and recovery are first-class behavior rather than add-on utility code.

## Status

Tramai is currently a strong alpha moving toward a frozen `0.1.0` MVP release.

What is already implemented:

- annotated `@AiService` interfaces and runtime proxy execution
- structured output with schema generation, parsing, validation, and retry feedback
- explicit provider registry and operation-level provider override
- Anthropic, OpenAI, OpenAI-compatible, and Ollama providers
- engine-owned timeout and retry handling
- optional OpenTelemetry integration
- standalone runtime, Kotlin DSL, Java entry points, and blocking interfaces
- Spring Boot autoconfiguration
- deterministic testing support

What is explicitly not part of `0.1.0`:

- streaming responses
- tool calling
- conversation memory
- KSP or other generated proxy implementations

The frozen release scope lives in [docs/reference/release-0.1.0.md](./docs/reference/release-0.1.0.md).

## Why Tramai

Tramai is intentionally opinionated about a few boundaries:

- annotated interface methods are the primary user API
- `tramai-engine` owns orchestration and retry policy
- `tramai-structured` owns schema generation, extraction, and structured failure analysis
- provider routing is explicit
- observability is opt-in at the dependency level
- standalone stays minimal instead of silently bundling everything

Those boundaries are documented under [docs/adr](./docs/adr).

## Quick Example

```kotlin
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a structured status",
        model = "claude-sonnet-4-20250514",
    )
    suspend fun analyze(invoiceText: String): InvoiceStatus
}

data class InvoiceStatus(
    val status: String,
)

val tramai = Tramai {
    provider(AnthropicProvider(apiKey = System.getenv("ANTHROPIC_API_KEY")), name = "anthropic")
    model("claude-sonnet-4-20250514", "anthropic")
}

val analyzer = tramai.create<InvoiceAnalyzer>()
```

OpenAI and OpenAI-compatible providers are available as well:

```kotlin
val tramai = Tramai {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai")
    model("gpt-5.1-chat-latest", "openai")
}
```

`tramai-openai` also contains an experimental Codex/ChatGPT auth-file path for local experimentation. That is intentionally marked experimental and is not the default production authentication story.

## Installation

Tramai is currently built and published from this repository. For local development and the included example project, publish the artifacts first:

```bash
./gradlew publishToMavenLocal
```

Then depend on the modules you need. Typical entry points are:

- `dev.tramai:tramai-standalone`
- `dev.tramai:tramai-spring`
- `dev.tramai:tramai-openai`
- `dev.tramai:tramai-anthropic`
- `dev.tramai:tramai-ollama`
- `dev.tramai:tramai-observability`
- `dev.tramai:tramai-testing`
- `dev.tramai:tramai-bom`

## Example Project

The repository includes a minimal Spring Boot example in [examples/kotlin-springboot-example](./examples/kotlin-springboot-example).

It demonstrates:

- a plain text endpoint
- a typed structured endpoint
- local-model usage through Ollama
- deterministic smoke tests against published local artifacts

Run the example locally with:

```bash
./gradlew -p examples/kotlin-springboot-example bootRun
```

## Build And Verify

Tramai currently targets:

- Java 25
- Kotlin 2.3.0
- Gradle 9 wrapper

Useful commands:

```bash
./gradlew test
./gradlew publishToMavenLocal
./gradlew -p examples/kotlin-springboot-example test
```

## Modules

- `tramai-core`: annotations, contracts, shared models, exceptions
- `tramai-engine`: proxy execution, dispatch, timeout handling, retry orchestration
- `tramai-structured`: schema generation and structured-output analysis
- `tramai-anthropic`: Anthropic provider
- `tramai-openai`: OpenAI and OpenAI-compatible providers
- `tramai-ollama`: Ollama provider
- `tramai-observability`: OpenTelemetry observer integration
- `tramai-standalone`: minimal non-framework runtime
- `tramai-spring`: Spring Boot adapter
- `tramai-testing`: mock and failure-oriented test helpers
- `tramai-bom`: version alignment for consumers

## Documentation

Start here:

- [docs/README.md](./docs/README.md)
- [Getting Started](./docs/guides/getting-started.md)
- [Standalone Usage](./docs/guides/standalone-usage.md)
- [Spring Boot Integration](./docs/guides/spring-boot.md)
- [Structured Output](./docs/guides/structured-output.md)
- [Current Limitations](./docs/reference/limitations.md)

For maintainers and contributors:

- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [AGENTS.md](./AGENTS.md)
- [ADR Index](./docs/adr/README.md)
- [Execution Board](./docs/board/board.md)

## Current Limits

Tramai is not yet the right choice if you need:

- streaming-first UI behavior
- provider-native tool calling
- memory or retrieval orchestration
- mature agent workflows

Those are future milestones, not hidden partial features.
