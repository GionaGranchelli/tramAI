# TramAI Kotlin Spring Boot Example

This example is the repository's reference Spring Boot app for TramAI.

It is intentionally small enough to read in one sitting, but it still demonstrates the main TramAI capabilities through normal backend application structure:

- typed `@AiService` methods
- raw text generation
- streaming responses
- tool calling through explicit application tools
- structured output mapped into typed DTOs
- persisted workflow orchestration with checkpoint inspection and resume
- GraalVM proxy metadata generation

## Why This Example Exists

The point is not to build an agent framework demo.

The point is to show what a backend-friendly TramAI integration looks like when:

- the HTTP layer stays thin
- AI contracts remain typed
- orchestration is explicit
- tools remain normal application components
- persistence stays inspectable

## Code Layout

The example is organized by concern:

- `src/main/kotlin/dev/tramai/examples/springboot/ExampleApplication.kt`
  Spring Boot bootstrap only.
- `src/main/kotlin/dev/tramai/examples/springboot/ai`
  TramAI service contracts.
- `src/main/kotlin/dev/tramai/examples/springboot/application`
  Application-facing facade used by the API layer.
- `src/main/kotlin/dev/tramai/examples/springboot/api`
  Controllers, API DTOs, and error mapping.
- `src/main/kotlin/dev/tramai/examples/springboot/domain`
  Structured-output model types and mapping.
- `src/main/kotlin/dev/tramai/examples/springboot/tools`
  Deterministic tool implementations used by tool calling.
- `src/main/kotlin/dev/tramai/examples/springboot/workflow`
  Workflow persistence, state, and orchestration.

## Capabilities Mapped To Endpoints

| Capability | Endpoint | What it proves |
| --- | --- | --- |
| Raw text | `POST /invoice/summary` | A normal typed interface method can return `String`. |
| Streaming | `POST /invoice/summary/stream` | TramAI can emit incremental tokens through Spring SSE. |
| Tool calling | `POST /invoice/enrich` | The model can request a named application tool and continue. |
| Structured output | `POST /invoice/triage` | TramAI can parse model JSON into typed DTOs. |
| Orchestration | `POST /invoice/workflow` and related workflow endpoints | Typed workflow state can compose multiple TramAI calls with persistence and resume. |

## Stack

- `dev.tramai:tramai-spring:0.3.1`
- `dev.tramai:tramai-orchestration:0.3.1`
- Spring Boot `3.4.5`
- Kotlin `2.3.0`
- Java `21+`
- Ollama for local execution by default

## Run It

Requirements:

- Ollama running locally
- the models used by `application.yml` available locally

Example:

```bash
ollama pull gemma4:e4b
ollama pull deepseek-r1:8b-64k
ollama serve
```

Run from the repository root:

```bash
./gradlew -p examples/kotlin-springboot-example bootRun
```

## Try It

The fastest way to explore the app is one of:

- [Request.http](./Request.http)
- [request-curl.sh](./request-curl.sh)
- [MANUAL.md](./MANUAL.md)

Health endpoint:

```bash
curl -s http://localhost:8080/ | jq
```

## Configuration

Default configuration lives in:

- [application.yml](./src/main/resources/application.yml)

By default the example uses:

- provider: `ollama`
- persistence root: `build/tramai-example/workflows`
- lease owner id: `example-node-1`

Completed workflow checkpoints are intentionally kept on disk so you can inspect the saved state after a run.

## Workflow Persistence Layout

For a workflow id like `wf-1042`, the example writes files like:

```text
build/tramai-example/workflows/
└── invoice-review-workflow/
    └── wf-1042/
        ├── checkpoint.md
        └── lease.properties
```

The checkpoint is retained after completion. The lease file exists only while a node owns the run.

## Native Image Metadata

The example ships checked-in proxy metadata for `InvoiceAnalyzer`.

Refresh it with:

```bash
./gradlew -p examples/kotlin-springboot-example generateNativeImageProxyConfig
```

## Verification

Run the example tests:

```bash
./gradlew -p examples/kotlin-springboot-example test
```

The smoke subset used by release validation is:

```bash
./gradlew -p examples/kotlin-springboot-example smokeTest
```
