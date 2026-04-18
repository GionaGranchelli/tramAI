# Aurora

Aurora is a structured-first, observability-native AI workflow library for the JVM.

Aurora is built for backend engineers who want to add AI to existing Kotlin or Java applications without adopting a chain or agent framework. The primary abstraction is an annotated interface method. Inputs stay typed. Outputs stay typed. Structured output is a first-class contract. Observability is optional at the dependency level and automatic when the observability module is present.

## Current Status

Aurora is under active development. The repository already contains working implementations for:

- `aurora-core`
- `aurora-engine`
- `aurora-structured`
- `aurora-anthropic`
- `aurora-openai`
- `aurora-ollama`
- `aurora-observability`
- `aurora-standalone`
- `aurora-spring`
- `aurora-testing`
- `aurora-bom`

The implementation is tracked against the roadmap in [PLAN.md](./PLAN.md), the architecture in [DESIGN.md](./DESIGN.md), and the spec and ADR set under [docs](./docs).

## Example

```kotlin
@AiService
interface InvoiceAnalyzer {
    @Operation(
        prompt = "Analyze the invoice and return a structured status",
        model = "claude-sonnet-4-20250514"
    )
    suspend fun analyze(invoiceId: String): InvoiceStatus
}

data class InvoiceStatus(
    val status: String,
)

val aurora = Aurora {
    provider(AnthropicProvider(apiKey = System.getenv("ANTHROPIC_API_KEY")), name = "anthropic")
    model("claude-sonnet-4-20250514", "anthropic")
}

val analyzer = aurora.create<InvoiceAnalyzer>()
```

OpenAI and OpenAI-compatible providers are available too:

```kotlin
val aurora = Aurora {
    provider(OpenAiProvider(System.getenv("OPENAI_API_KEY")), name = "openai")
    model("gpt-5.1-chat-latest", "openai")
    model("gpt-5-codex", "openai")
}
```

For local experiments and testing, `aurora-openai` also includes an experimental Codex/ChatGPT auth-file path that can read a bearer token from the local Codex login state. That path is intentionally not the default documented production authentication flow.

## Build

Aurora uses:

- Java 25 toolchains
- Kotlin 2.3.0
- Gradle wrapper

Run the full test suite with:

```bash
./gradlew test
```

## Modules

- `aurora-core`: annotations, contracts, shared models, exceptions
- `aurora-engine`: proxy execution, dispatch, retry orchestration
- `aurora-structured`: schema generation and structured-output analysis
- `aurora-anthropic`: Anthropic provider
- `aurora-openai`: OpenAI and OpenAI-compatible providers
- `aurora-ollama`: Ollama provider
- `aurora-observability`: OpenTelemetry observer integration
- `aurora-standalone`: minimal non-framework runtime
- `aurora-spring`: Spring Boot adapter
- `aurora-testing`: mock providers and audit-friendly test helpers
- `aurora-bom`: version alignment for consumers

## Design Rules

Aurora is intentionally strict about a few boundaries:

- `aurora-engine` owns orchestration and retry policy
- `aurora-structured` owns schema generation, extraction, deserialization, and structured failure analysis
- provider resolution uses an explicit registry
- `aurora-standalone` stays minimal
- observability is optional at the dependency level
- Java support in v1 uses explicit blocking service interfaces

Those rules are documented under [docs/adr](./docs/adr).
