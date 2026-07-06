# TramAI — Governed AI Workflows for the JVM

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

TramAI is a **Kotlin-first JVM runtime for governed AI workflows**. It helps teams build AI-powered systems where model calls, tool usage, approvals, data handling, routing, replay-safety, and auditability are treated as first-class runtime concerns rather than scattered application code.

> **Status: active development.** The current `master` branch contains several unreleased sovereign-runtime capabilities. APIs may change before the next tagged release. This README describes the current architectural direction, not a frozen public API.

## Why TramAI Exists

Calling an LLM is the easy part.

Enterprise AI workflows also need:

- policy enforcement
- data classification and DLP/redaction
- approval boundaries for high-risk operations
- replay-safe continuation after suspension
- local-vs-cloud routing as a security decision
- audit trails with tamper-evident sequencing
- durable operational recovery after restarts
- evidence that sensitive work stayed inside the allowed trust zone

TramAI exists to make those concerns explicit, testable, and composable in JVM applications.

## Core Capabilities

| Capability | What it means |
|---|---|
| Typed AI services | Define AI operations through Kotlin/JVM interfaces — typed inputs, typed outputs. |
| Structured output | Keep model responses shaped and validated by application contracts. |
| Policy enforcement | Apply runtime rules before sensitive AI/tool operations execute. |
| DLP/redaction | Prevent sensitive data from leaking into prompts, logs, replay envelopes, or audit views. |
| Approval gates | Suspend risky operations until an explicit approval decision is made. |
| Replay-safe resume | Resume suspended invocations without trusting mutable/raw replay payloads. |
| Sovereign routing | Route restricted workloads to local/trusted model zones and deny unsafe routes. |
| Model registry verification | Verify local model artifacts before they can be used. |
| Audit chain | Record governance-relevant events with tamper-evident sequencing. |
| Encrypted file-backed persistence | Durable encrypted stores for approvals, suspended invocations, audit streams, and outbox records. |
| JDBC persistence | PostgreSQL-backed sovereign stores: approvals, audit events, suspended invocations, approval continuations, and audit outbox. Spring Boot auto-configuration via `type=jdbc`. |
| JDBC E2E restart proof | Spring Boot example with Testcontainers PostgreSQL: persist state, restart, recover — audit and outbox survive context restart. |
| Audit outbox recovery | Persist audit emission intent and safely recover/dispatch it via a configurable background worker. |
| Approval gateway | Request human approval through an ergonomic API without manually wiring low-level stores. [Golden path guide](docs/guides/approval-gateway-golden-path.md). |
| Approved-continuation auto-resume | Automatically resume approved, suspended workflows via a background worker with encrypted credential custody, configurable retry, and full observability. |

## Quick Example

```kotlin
@AiService
interface SupportAgent {
    @SystemMessage("You are a Tier-1 support agent. Be concise.")
    @UserMessage("Customer issue: {message}")
    @Operation(model = "gemma4:e2b")
    suspend fun handle(message: String): Response
}

data class Response(
    @AiDescription("Answer to the customer") val answer: String,
    @AiDescription("Action taken, if any") val action: String? = null
)

val agent = Tramai.builder()
    .provider(OllamaProvider("http://localhost:11434"), default = true)
    .model("gemma4:e2b", "ollama")
    .build()
    .create<SupportAgent>()

val result = agent.handle("Where is my order #ORD-42?")
println(result.answer)
```

One annotated interface, typed output, and local model execution. No framework dictating your architecture.

## Architecture Snapshot

TramAI is organized into focused Gradle modules:

### Core Library

| Module | Purpose |
|---|---|
| `tramai-core` | Annotations, contracts, SPIs |
| `tramai-engine` | Proxy dispatch, execution, retry, tool calling |
| `tramai-standalone` | Framework-free entry point |
| `tramai-spring` | Spring Boot auto-configuration |
| `tramai-structured` | JSON Schema generation and structured output validation |
| `tramai-testing` | Deterministic mock providers and assertions |

### Provider Adapters

| Module | Purpose |
|---|---|
| `tramai-ollama` | Local models via Ollama |
| `tramai-openai` | OpenAI and compatible APIs |
| `tramai-anthropic` | Claude via Anthropic API |
| `tramai-azure-openai` | Azure OpenAI API |
| `tramai-bedrock` | AWS Bedrock |
| `tramai-gemini` | Google Gemini API |
| `tramai-deepseek` | DeepSeek API |

### Sovereign Runtime

| Module | Purpose |
|---|---|
| `tramai-security` | Policy enforcement, DLP, redaction, replay envelope safety |
| `tramai-sovereign` | Trust zones, sovereign routing, local/cloud enforcement primitives |
| `tramai-persistence-file` | Encrypted file-backed stores for approvals, continuations, audit, and outbox |
| `tramai-spring-boot-starter-sovereign` | Sovereign runtime Spring Boot auto-configuration |
| `tramai-spring-boot-starter-sovereign-ops` | Operational APIs: audit outbox, recovery, dispatch, background worker, observer SPI |
| `tramai-spring-boot-starter-sovereign-ops-actuator` | Optional Actuator endpoint and health indicator for worker status (read-only, opt-in) |
| `tramai-spring-boot-starter-sovereign-ops-micrometer` | Micrometer metrics for sovereign ops audit outbox worker |
| `tramai-spring-boot-starter-sovereign-ops-observability` | OpenTelemetry metrics for sovereign ops audit outbox worker |
| [Worker observability runbook](docs/operations/sovereign-ops-worker-observability-runbook.md) | Operator-facing documentation for worker status, health, and metrics surfaces |
| `tramai-spring-boot-starter-sovereign-persistence-file` | File-backed persistence auto-configuration |
| `tramai-spring-boot-starter-sovereign-persistence-jdbc` | PostgreSQL-backed persistence auto-configuration for approvals, suspended invocations, continuations, audit events, audit outbox, and resume credentials |
| `tramai-spring-boot-starter-sovereign-ops-rest` | REST control plane for approval decisions, resume, and inbox query (Preview, disabled by default) |

### Optional Higher-Level Modules

| Module | Purpose |
|---|---|
| `tramai-orchestration` | Typed workflow coordination, checkpoints, worker pools |
| `tramai-observability` | OpenTelemetry spans and metrics (opt-in) |
| `tramai-memory` | Chat memory implementations |
| `tramai-rag` | Retrieval-augmented generation pipeline |
| `tramai-embedding` | Embedding models |
| `tramai-scheduler` | Cron and delay triggers |
| `tramai-server` | HTTP API, webhooks, SSE |
| `tramai-mcp` | MCP server adapter |
| `tramai-platform` | Multi-tenancy, API keys, plugins |
| `tramai-memory-store` | Durable chat memory storage |
| `tramai-dashboard` | Vue 3 admin UI |

## Sovereign Workflow Mental Model

A typical governed workflow follows this pattern:

```
Input document
  -> classify sensitivity
  -> enforce policy
  -> choose allowed model route (local or deny)
  -> execute locally or deny
  -> suspend for approval when needed
  -> resume through replay-safe continuation
  -> persist audit evidence
  -> dispatch audit event through outbox
  -> produce evidence/release artifacts
```

Every step is an explicit runtime concern, not an afterthought.

## Reference Example

The `examples/sovereign-document-intelligence` module demonstrates an end-to-end sovereign workflow:

```
RESTRICTED document
  -> LOCAL-only routing
  -> policy enforcement
  -> approval suspension
  -> replay-safe resume
  -> audit chain
  -> evidence pack / release bundle
```

Run it from the repository root:

```bash
./gradlew :examples:sovereign-document-intelligence:run --args="--release-bundle-manifest=build/sovereign-release/release-artifacts-v1.json"
```

This is a reference workflow — not a production deployment template.

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

More:
- [Quickstart Guide](docs/guides/quickstart.md)
- [Spring Boot Integration](docs/guides/spring-boot.md)
- [Testing Guide](docs/guides/testing.md)
- [Architecture Overview](docs/architecture/overview.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Project Status](docs/STATUS.md)

## Roadmap

**Sovereign Lab Evidence Handoff v1 is complete.** The next phase focuses on workflow ergonomics, API stability, structured output contracts, and runtime evidence.

See the [Post-Sovereignty Roadmap](docs/POST-SOVEREIGNTY-ROADMAP.md) for the full plan and phased PRs.

## Project Status

TramAI is under **active development**.

The current `master` branch includes unreleased work around:

- sovereign routing and trust zones
- replay-safe approvals
- encrypted file-backed persistence
- local model artifact verification
- audit chain and audit outbox storage
- audit outbox recovery, dispatch, and background worker
- evidence-generation examples

Until the next tagged release, APIs in these areas may change.

### Not Yet Included

The following are intentionally not claimed as complete:

- stable 1.0 API
- production-hardening of the Preview REST control plane (mutations, authentication, rate limiting)
- key rotation
- complete API reference documentation

### Sovereign Runtime

The Sovereign Runtime roadmap is **functionally complete as an RC+ / enterprise proof milestone**.

See:

- [Sovereign Runtime Closure Boundary](docs/releases/sovereign-runtime-closure-boundary.md)
- [Regulated Claim Triage Scenario](docs/scenarios/regulated-claim-triage.md)
- [Sovereign JDBC Production Deployment Runbook](docs/runbooks/sovereign-jdbc-production-deployment.md)

Run the full closure verification chain with:

```bash
./gradlew verifySovereignRuntimeClosure --no-configuration-cache --rerun-tasks
```

The `verifySovereignRuntimeClosure` task aggregates the full verification surface for the Sovereign Runtime RC+ / enterprise proof closure boundary. For the RC-specific variant (used during development), use:

```bash
./gradlew verifySovereignRuntimeReleaseCandidate --no-configuration-cache --rerun-tasks
```

Approved-resume worker dashboards, alert examples, and an operator triage runbook are available under docs/observability/ and docs/runbooks/.

## Sovereign Runtime Release Readiness

The sovereign runtime capabilities on `master` are actively evolving and not yet a stable 1.0 API. For the current release-readiness boundary, included modules, validation commands, and known non-goals, see:

- [docs/releases/sovereign-runtime-release-readiness.md](docs/releases/sovereign-runtime-release-readiness.md)
- [docs/modules/sovereign-runtime-module-matrix.md](docs/modules/sovereign-runtime-module-matrix.md)

For a practical first integration path, see:

- [Sovereign Runtime Quickstart](docs/guides/sovereign-runtime-quickstart.md)

For a domain-level walkthrough, see:

- [Regulated Claim Triage Reference Scenario](docs/scenarios/regulated-claim-triage.md)

For the production-hardening direction toward JDBC / database-backed persistence, see:

- [Sovereign JDBC Persistence Design](docs/architecture/sovereign-jdbc-persistence-design.md)

## Development Commands

```bash
./gradlew test                     # full test suite
./gradlew test --rerun-tasks       # full test suite (no cache)
./gradlew publishToMavenLocal      # publish to local Maven repository
```

## License

Apache License 2.0
