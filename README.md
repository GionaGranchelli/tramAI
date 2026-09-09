# TramAI — Governed AI Workflows for the JVM

[![CI](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml/badge.svg)](https://github.com/GionaGranchelli/tramAI/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/kotlin-2.3.0-blue.svg?logo=kotlin)](http://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.5-brightgreen.svg?logo=springboot)](https://spring.io/projects/spring-boot)

TramAI is a **Kotlin-first JVM runtime for governed AI workflows**, combining typed AI contracts with runtime policy, human approval, controlled model routing, and verifiable execution evidence.

> **TramAI 0.6.0 is the current release.** The 0.6.x line is frozen except for bug and security fixes. New feature work targets the 0.7.0 roadmap. See [Project Status](docs/STATUS.md), the [0.6.0 release notes](docs/releases/0.6.0-release-notes.md), and the [0.6.0 migration guide](docs/releases/0.6.0-migration-guide.md). The canonical [Product Positioning](docs/product/positioning.md) defines audiences, pillars, boundaries, and messaging.

---

## Why Governed Workflows

| Ordinary AI integration | Governed AI workflow |
|---|---|
| Call a model | Call a model through explicit runtime boundaries |
| Register a tool | Authorize the tool before exposure and execution |
| Retry on failure | Preserve typed failure and evidence semantics |
| Add an approval flag | Suspend and resume through a durable approval lifecycle |
| Log a request | Record governance decisions in structured audit evidence |
| Select a provider | Route according to data and trust policy |

Calling an LLM is easy. Governing what data it sees, which model handles it, which actions it may perform, who approves sensitive outcomes, and how the execution is reconstructed later is the harder problem.

---

## Run a Governed Workflow

```bash
git clone https://github.com/GionaGranchelli/tramAI.git
cd tramAI
./gradlew :examples:governed-workflow:run
```

**Expected output** (deterministic — no model or credentials required):

```
✓ Low-risk claim: ready-for-review — Policy and approval gates passed
✓ Restricted claim: rejected — Restricted claim requires manual handling
✓ High-risk unapproved claim: rejected — High-risk claim requires human approval
✓ High-risk approved claim: ready-for-review — Policy and approval gates passed
```

> This introductory example demonstrates typed workflow composition and deterministic gates. It does not represent the complete durable approval, persistence, audit, or sovereign-runtime stack.

---

## Use TramAI in an Application

TramAI 0.6.0 is published to Maven Central. Add the BOM and the smallest runtime/provider modules your application needs:

```kotlin
dependencies {
    implementation(platform("dev.tramai:tramai-bom:0.6.0"))
    implementation("dev.tramai:tramai-standalone")
    implementation("dev.tramai:tramai-openai")
}
```

Define a typed service instead of assembling prompts in application code:

```kotlin
import dev.tramai.core.annotations.AiService
import dev.tramai.core.annotations.Operation

@AiService
interface GreetingService {
    @Operation(
        prompt = "Greet the user warmly in one sentence based on their name.",
        model = "gpt-4o",
    )
    suspend fun greet(name: String): String
}
```

Configure the provider at the composition boundary and invoke the typed service:

```kotlin
import dev.tramai.openai.OpenAiProvider
import dev.tramai.standalone.Tramai

suspend fun main() {
    val tramai = Tramai {
        provider(OpenAiProvider(apiKey = System.getenv("OPENAI_API_KEY")), name = "openai")
        model("gpt-4o", "openai")
    }

    val greetingService = tramai.create<GreetingService>()
    println(greetingService.greet("Ada"))
}
```

This path requires an `OPENAI_API_KEY`. For Spring Boot, Maven, structured outputs, testing, and provider-specific setup, see the [30-Minute Quickstart](docs/guides/quickstart.md).

> TramAI remains under active development. The 0.6.x line is frozen except for bug and security fixes; new features target 0.7.0.

---

## The Governed Execution Model

```
Typed input
  ↓
Data classification
  ↓
Configured policy and DLP
  ↓
Allowed provider route — or denial
  ↓
Model response validated against a typed contract
  ↓
Tool exposure and execution policy
  ↓
Optional human approval and suspension
  ↓
Replay-safe continuation
  ↓
Audit, evidence, and operational recovery
```

> Governance, routing, approval, persistence, and evidence are composable capabilities. Their guarantees apply when the corresponding components are configured.

---

## What TramAI Governs

### Typed Contracts
JVM interfaces, structured output, schema generation, validation, and deterministic testing — no raw prompt plumbing.

### Runtime Governance
Configurable policy enforced before models, tools, and responses proceed. When the secure policy engine is configured, unknown tools, models, and providers are denied by default.

### Human Control
Approval, suspension, denial, idempotency, and replay-safe continuation. High-risk operations are gated by human decisions that leave durable evidence.

### Controlled Routing
Local, trusted, or approved provider zones selected based on workflow classification and policy.

### Evidence and Recovery
Audit sequencing, evidence export, durable stores, outbox recovery, and worker observability. Tamper-evident audit records when configured.

### Composable Adoption
Standalone and Spring Boot integration. Adopt only the capabilities you need without a mandatory hosted control plane.

---

## Choose Your Depth

| Goal | Start here |
|---|---|
| Run a governed workflow with no credentials | [`examples/governed-workflow`](examples/governed-workflow) |
| Understand governed workflow concepts | [Governed Workflow Quickstart](docs/guides/governed-workflow-quickstart.md) |
| See real approval suspension and resume | [`examples/approval-resume`](examples/approval-resume) |
| Inspect the complete sovereign architecture | [`examples/sovereign-document-intelligence`](examples/sovereign-document-intelligence) |
| Make a basic AI call | [30-Minute Quickstart](docs/guides/quickstart.md) |
| Integrate with Spring Boot | [Spring Boot Guide](docs/guides/spring-boot.md) |
| Test workflows deterministically | [Governed Workflow Testing](docs/guides/governed-workflow-testing.md) |
| Compare every example and its prerequisites | [Example Selection Guide](examples/README.md) |
| Decide between TramAI, Spring AI, and LangChain4j | [JVM AI Framework Comparison](docs/comparison/jvm-ai-frameworks.md) |
| Understand current maturity | [Project Status](docs/STATUS.md) |

---

## Architecture

- **Core runtime** — typed services, engine, structured output, testing
- **Provider adapters** — Ollama, OpenAI-compatible APIs, Anthropic, Azure OpenAI, Bedrock, Gemini, DeepSeek
- **Governance runtime** — policy, DLP, routing, approval, persistence, audit, evidence, operations
- **Optional extensions** — orchestration, RAG, memory, scheduling, server, observability, platform, MCP workflow server

See the [Architecture Overview](docs/architecture/overview.md) and [Sovereign Runtime Module Matrix](docs/modules/sovereign-runtime-module-matrix.md) for detailed module descriptions.

---

## Maturity

| Boundary | Status |
|---|---|
| Typed services and structured output | Released / evolving |
| Governance and sovereign capabilities | Released / evolving |
| Sovereign runtime | RC+ enterprise proof milestone |
| Stable sovereign 1.0 API | Not yet available |
| Compliance or certification | Not claimed |
| Production readiness for every deployment | Not claimed |
| MCP workflow server | Implemented / evolving |
| Governed remote MCP tool connector | Not implemented |

[Full status →](docs/STATUS.md)

---

## Development

```bash
./gradlew test          # run all tests
./gradlew check         # full build validation
./gradlew publishToMavenLocal   # publish to local Maven
```

TramAI targets JVM 21+. Building the repository requires a JDK 21 toolchain.

---

## Documentation

- [JVM AI Framework Comparison](docs/comparison/jvm-ai-frameworks.md) — when to choose TramAI, Spring AI, or LangChain4j
- [Beyond the Model Call](docs/articles/governed-ai-workflows-for-the-jvm.md) — publishable introduction to governed AI workflows on the JVM
- [Example Selection Guide](examples/README.md) — choose a demo, integration sample, reference workflow, or verification harness
- [Product Positioning](docs/product/positioning.md) — canonical positioning, audiences, pillars, claim boundaries
- [Project Status](docs/STATUS.md) — detailed implementation and maturity tracking
- [Post-Sovereignty Roadmap](docs/POST-SOVEREIGNTY-ROADMAP.md) — current and planned development phases
- [Guides](docs/guides/) — quickstart, governed workflows, testing, approval taxonomy
- [Security Model](docs/security/SECURITY-MODEL.md) — threat model and abuse scenarios
- [Runtime Evidence](docs/evidence/runtime-evidence-export-model.md) — evidence record shape and export
- [API Stability](docs/workflow-api-stability-boundary.md) — stable, preview, internal, and deferred surfaces

---

## License

Apache 2.0 © [Giona Granchelli](https://github.com/GionaGranchelli)
