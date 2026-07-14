# TramAI Example Selection Guide

TramAI examples serve different purposes: some teach the basic API, some demonstrate governed workflow composition, some prove durable approval or sovereign-runtime behavior, and some are verification harnesses rather than application templates.

> TramAI is under active development. Examples reflect the current state of the project; see [Project Status](../docs/STATUS.md) for detailed maturity tracking.

Use this guide to select the smallest example that answers your question.

---

## Start Here

New to TramAI? Start with:

```bash
./gradlew :examples:governed-workflow:run
```

It is deterministic, requires no credentials or external model, and shows typed workflow composition, policy gates, approval gates, and failure paths.

> This example demonstrates composition. It does not demonstrate durable approval storage, replay persistence, audit export, or sovereign deployment.

---

## Choose by Goal

| Goal | Recommended path |
|---|---|
| Learn governed AI service, policy, and approval patterns | [Tool Governance](#tool-governance) |
| See a governed workflow in five minutes | [Governed Workflow](#governed-workflow) |
| Learn typed AI services, tools, and structured output | [Support Agent](#support-agent) |
| Explore a conventional Spring Boot backend integration | [Kotlin Spring Boot Example](#kotlin-spring-boot-example) |
| Test durable approval suspension and resume | [Approval Resume](#approval-resume) |
| Learn sovereign Spring Boot auto-configuration | [Spring Sovereign Starter](#spring-sovereign-starter) |
| Inspect the complete sovereign reference architecture | [Sovereign Document Intelligence](#sovereign-document-intelligence) |
| Verify offline/zero-egress runtime behavior | [Sovereign Offline Verification](#sovereign-offline-verification) |
| Run a real local-model sovereign environment | [Sovereign Lab](#sovereign-lab) |

---

## Example Matrix

| Example | Type | Real model | External infrastructure | Persistence | Governance depth | Primary command |
|---|---|---|---|---|---|---|
| Tool Governance | Learning demo | No | None | None | Tool permission outcomes | `./gradlew :examples:tool-governance:test` |
| Governed Workflow | Learning demo | No | None | None | Composition | `./gradlew :examples:governed-workflow:run` |
| Support Agent | Core API demo | Ollama for runtime | Ollama | None | Basic AI integration | `./gradlew :examples:support-agent:run` |
| Kotlin Spring Boot | Integration application | Ollama | Ollama | File checkpoints | Core workflow integration | `./gradlew -p examples/kotlin-springboot-example bootRun` |
| Approval Resume | Lifecycle proof | Deterministic | Embedded PostgreSQL | JDBC | Durable approval | `./gradlew :examples:approval-resume:test` |
| Spring Sovereign Starter | Starter integration | Deterministic | None for basic path | In-memory by default | Sovereign configuration | `./gradlew :examples:spring-sovereign-starter:bootRun` |
| Sovereign Document Intelligence | Reference workflow | Repository-local example provider | None required | Runtime stores/artifacts | Full reference architecture | `./gradlew :examples:sovereign-document-intelligence:run` |
| Sovereign Offline Verification | Verification harness | Loopback provider | Docker + Python 3 | Evidence files | Offline verification | `./scripts/verify-zero-egress.sh` |
| Sovereign Lab | Physical lab | Yes, local | PostgreSQL, local model, optional Docker | JDBC | Full local evaluation | Follow lab quickstart in [sovereign-lab/README.md](sovereign-lab/README.md) |

---

## Example Profiles

### Tool Governance

**Choose this when:** you want to understand tool permission outcomes (ALLOW, DENY, REQUIRE_APPROVAL) and the dedicated `tool.permission` runtime evidence family.

**Run:**
```bash
./gradlew :examples:tool-governance:run
```

**Requires:** nothing — deterministic, no credentials, no external model, no Docker.

**Demonstrates:** three deterministic tool governance scenarios — read-only lookup (ALLOW), account deletion (DENY via policy wrapper), payment (REQUIRE_APPROVAL via approval suspension). Each scenario verifies tool execution count, enforcement point decisions, and dedicated `tool.permission` runtime evidence export. Shows that tool enforcement events are excluded from generic `policy.decision` evidence.

**Does not demonstrate:** durable approval storage, replay persistence, audit export, sovereign deployment, `REDACT_RESULT`, `ALLOW_INTERNAL_ONLY`, or MCP governance.

**Next step:** [Governed Workflow](#governed-workflow) for composition patterns, or [Approval Resume](#approval-resume) for durable human approval.

---

### Governed Workflow

**Choose this when:** this is your first TramAI evaluation.

**Run:**
```bash
./gradlew :examples:governed-workflow:run
```

**Requires:** nothing — deterministic, no credentials, no external model.

**Demonstrates:** typed workflow composition — `aiStep` wrapping a deterministic classifier, `gateStep` for policy and approval enforcement, `localStep` for finalization, four success and rejection scenarios.

**Does not demonstrate:** durable approval storage, replay persistence, audit export, sovereign deployment.

**Next step:** [Approval Resume](#approval-resume) for durable human approval, or [Sovereign Document Intelligence](#sovereign-document-intelligence) for the full reference architecture.

---

### Support Agent

**Choose this when:** you want the smallest real local-model example using annotations, tools, structured output, retry, and deterministic tests.

**Run:**
```bash
./gradlew :examples:support-agent:run
```

**Requires:** Ollama with `gemma4:e2b` for the runtime path. Tests use `MockAiProvider` and do not require Ollama.

**Demonstrates:** `@AiService` annotations, typed `Response` output with `@AiDescription` fields, tool calling, retry policies, and deterministic testing with `MockAiProvider`.

**Does not demonstrate:** sovereign governance, durable approval, policy enforcement, audit evidence, or sovereign persistence. This is a core AI integration example consuming released 0.4.0 artifacts.

**Next step:** [Kotlin Spring Boot Example](#kotlin-spring-boot-example) for a fuller application, or [Governed Workflow](#governed-workflow) for governance.

---

### Kotlin Spring Boot Example

**Choose this when:** you want to see TramAI inside a conventional Spring Boot HTTP application.

**Run:**
```bash
./gradlew -p examples/kotlin-springboot-example bootRun
```

**Requires:** a separate Gradle build (not part of the root project), released 0.4.0 dependencies, Ollama, and the configured models:

```bash
ollama pull gemma4:e4b
ollama pull deepseek-r1:8b-64k
```

See the [Kotlin Spring Boot example README](kotlin-springboot-example/README.md) for endpoints and manual requests.

**Demonstrates:** raw text generation, streaming responses, tool calling, structured output mapped to typed DTOs, HTTP endpoints, persisted workflow orchestration with checkpoint inspection and resume.

**Does not demonstrate:** the sovereign Spring starter path; its file-based workflow persistence is not the same as sovereign approval/audit persistence.

**Next step:** [Spring Sovereign Starter](#spring-sovereign-starter) for sovereign auto-configuration.

---

### Approval Resume

**Choose this when:** you specifically want to understand durable human approval.

**Run:**
```bash
./gradlew :examples:approval-resume:test
```

**Requires:** embedded PostgreSQL only — no Docker, no external model, no credentials.

**Demonstrates:** low-value bypass, high-value suspension, approved and denied decision paths, repeated-resume behavior, and an at-most-once reimbursement side effect.

**Does not demonstrate:** every workflow resuming exactly once, every side effect executing exactly once, every database configuration, or production reviewer authorization.

**Next step:** [Sovereign Document Intelligence](#sovereign-document-intelligence) for the full reference architecture combining routing, approval, audit, and evidence.

---

### Spring Sovereign Starter

**Choose this when:** you want to understand how the sovereign runtime is integrated through Spring Boot configuration and auto-configuration.

**Run:**
```bash
./gradlew :examples:spring-sovereign-starter:bootRun
```

**Requires:** nothing for the basic path — a deterministic local provider, no cloud call, no API key.

**Demonstrates — basic path:** `SovereignTramaiRuntime` auto-configuration, typed `@AiService` with `@Operation`, in-memory audit, approval, continuation, and suspension stores.

**Note:** state is lost on restart in the basic in-memory path.

**Demonstrates — advanced paths:** encrypted file persistence, JDBC persistence, operational services, Actuator and observability modules. See the full [starter README](spring-sovereign-starter/README.md) for advanced configuration.

**Does not demonstrate:** a full sovereign evidence chain, durable audit persistence in the basic path, or production IAM.

**Next step:** [Sovereign Lab](#sovereign-lab) for a physical environment, or [Sovereign Document Intelligence](#sovereign-document-intelligence) for a bounded reference workflow.

---

### Sovereign Document Intelligence

**Choose this when:** you want the deepest self-contained architectural example.

**Run:**
```bash
./gradlew :examples:sovereign-document-intelligence:run
```

A `--release-bundle-manifest` argument is available for release/evidence artifact generation but is optional for the standard run.

**Requires:** nothing — uses a repository-local example provider. No credentials, no external infrastructure.

**Demonstrates:** restricted input classification, local-only routing, policy enforcement, approval suspension, replay-safe continuation, audit chain, and evidence artifacts.

**Important:** this is a reference workflow, not a production deployment template.

**Next step:** [Sovereign Offline Verification](#sovereign-offline-verification) for controlled offline verification, or [Sovereign Lab](#sovereign-lab) for a real local model.

---

### Sovereign Offline Verification

**Choose this when:** you are evaluating offline-runtime and zero-egress evidence behavior.

**Primary command:**
```bash
./scripts/verify-zero-egress.sh
```

See the [verification script](../scripts/verify-zero-egress.sh) for implementation details.

**Requires:** Docker and Python 3. The script builds the verification image, runs it with `--network=none`, and validates the generated report. No real model or API credentials are required.

**Demonstrates:** creating a temporary local model artifact, verifying its digest, using a loopback HTTP provider, configuring the runtime in OFFLINE mode, executing external network probes, and writing verification and evidence output.

**Important:** this is a verification harness, not an application template. A successful harness run is evidence about that controlled run; it does not prove universal zero-egress behavior for every deployment.

**Next step:** [Sovereign Lab](#sovereign-lab) for physical local-model evaluation.

---

### Sovereign Lab

**Choose this when:** you want to evaluate TramAI with a real local model and production-like supporting infrastructure.

**Entry levels:**

**No-infrastructure smoke (no model or Docker required):**
```bash
./gradlew verifySovereignLabProfile
./gradlew verifySovereignLabRuntimeSmoke
```

These validate configuration and Spring wiring without invoking a real model.

**Physical lab (requires infrastructure):**
- Java 21+
- PostgreSQL
- A local OpenAI-compatible model endpoint (Ollama, llama.cpp, vLLM, LM Studio, or LocalAI)
- Encryption key
- Optionally Docker Compose

See the [Sovereign Lab README](sovereign-lab/README.md) for the full setup guide.

**Demonstrates:** full local-model sovereign evaluation with PostgreSQL persistence, approval workflow, REST control plane, reviewer UI, evidence bundles, and zero-egress configuration.

**Important:** this is an advanced, operator/evidence-oriented evaluation path. It is not suitable as the first example and requires more infrastructure than the self-contained reference workflows.

**Next step:** review the [sovereign-lab README](sovereign-lab/README.md) for detailed setup, or refer to [Sovereign Offline Verification](#sovereign-offline-verification) for a lighter verification path.

---

## Recommended Learning Paths

### Path A — Fast governed evaluation
`governed-workflow` → `approval-resume` → `sovereign-document-intelligence`

### Path B — JVM application integration
`support-agent` → `kotlin-springboot-example` → `spring-sovereign-starter`

### Path C — Sovereign and offline verification
`spring-sovereign-starter` → `sovereign-document-intelligence` → `sovereign-offline-verification` → `sovereign-lab`

### Path D — Contributor verification
`./gradlew test` → `./gradlew check` → sovereign runtime closure tasks → offline verification harness

These are learning and evaluation paths, not maturity certification levels.

---

## What the Examples Do Not Prove

- Running an example does not establish compliance.
- A successful example is not a security certification.
- LOCAL is a configured trust zone, not automatic proof of physical isolation.
- A zero-egress harness result applies to the observed test environment.
- Deterministic providers do not prove model quality.
- Human approval does not prove that the underlying decision was correct.
- Examples are not production deployment templates unless explicitly stated.
- Governed remote MCP tool import is not demonstrated.
