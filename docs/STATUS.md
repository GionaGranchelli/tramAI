# TramAI Status

TramAI is under **active development**.

This document describes the state of the repository, not a formal release contract. It tracks what is implemented and evolving on `master`, and what is intentionally not yet complete.

## Sovereign Runtime Closure Verification

The closure boundary can be verified with:

```bash
./gradlew verifySovereignRuntimeClosure
```

This is the canonical local verification command for the Sovereign Runtime RC+ / enterprise proof milestone.

## Sovereign Runtime Closure Status

The Sovereign Runtime roadmap is **functionally complete as an RC+ / enterprise proof milestone**.

Closure boundary:
- [Sovereign Runtime Closure Boundary](releases/sovereign-runtime-closure-boundary.md)

### Included Proof Points
- JDBC-backed runtime persistence (approval, audit, outbox stores)
- Transactional approval mutation + audit outbox boundary
- Worker lease coordination for multi-node deployments
- Production deployment runbook (JDBC stack)
- Regulated claim triage JDBC E2E proof
- CI-backed E2E execution
- Preview approval REST/control-plane surfaces
- Preview reviewer UI (disabled by default)
- Internal encrypted resume credential custody
- Approved-continuation auto-resume worker
- Approved-resume worker lifecycle/status/health/queue snapshot/metrics
- Approved-resume lifecycle JDBC E2E proof
- Approved-resume worker dashboard and alert examples

### Deferred from Closure
- Key rotation
- Production certification
- Production-grade reviewer UI / enterprise IAM
- Production-grade admin REST surface
- Maven Central release

The next roadmap after this closure is API stabilization and workflow ergonomics.

## Sovereign Runtime API Stability

The Sovereign Runtime RC+ / enterprise proof milestone has a documented API stability boundary, executable verification task, and manifest. Run with:

```bash
./gradlew verifySovereignRuntimeApiBoundary
```

See:

- [Sovereign Runtime API Stability Boundary](architecture/sovereign-api-stability-boundary.md)
- [Sovereign Runtime API Stability Manifest](architecture/sovereign-api-stability-manifest.yml)

Summary:

- Store and runtime SPIs are **RC+ Stable**.
- Workflow ergonomics are **Preview**.
- Concrete JDBC implementations are **Internal**.
- Key rotation, production-grade reviewer UI, production-grade admin REST surface, and 1.0-wide API stability are **Deferred**.

## Implemented / Evolving

| Area | Status |
|---|---|
| Typed AI services (`@AiService`, `@Operation`) | Implemented / evolving |
| Structured output (schema generation, validation, retry) — Kotlin and JavaBean | Implemented / evolving |
| Provider adapters (Ollama, OpenAI, Anthropic, Azure, Bedrock, Gemini, DeepSeek) | Implemented / evolving |
| Deterministic testing (`tramai-testing`, zero-network mock providers) | Implemented |
| Policy engine (`tramai-security`) | Implemented / evolving |
| DLP/redaction | Implemented / evolving |
| Approval gates | Implemented / evolving |
| Replay-safe resume | Implemented / evolving |
| Sovereign routing (trust zones, local/cloud enforcement) | Implemented / evolving |
| Local model registry verification | Implemented / evolving |
| Encrypted file-backed persistence | Implemented / evolving |
| Audit chain (tamper-evident sequencing) | Implemented / evolving |
| Audit outbox (atomic mutation + audit intent, claim-based dispatch) | Implemented / evolving |
| Audit outbox background worker (recovery + dispatch loop) | Implemented / evolving |
| JDBC sovereign persistence (PostgreSQL-backed approval, audit, outbox stores) | Implemented / evolving |
| JDBC transactional approval mutation outbox boundary | Implemented / evolving |
| Spring Boot auto-configuration for JDBC persistence | Implemented |
| JDBC E2E restart proof (Testcontainers, audit/outbox recovery) | Implemented |
| JDBC worker lease coordination (multi-node audit outbox worker coordination) | Implemented |
| Regulated claim triage JDBC E2E proof | Implemented |
| Sovereign ops worker observability runbook | Implemented |
| Sovereign ops audit outbox OpenTelemetry metrics | Implemented |
| Evidence generation (bundles, release artifacts) | Implemented / evolving |
| Sovereign document intelligence example | Implemented |
| OpenTelemetry observability (spans, metrics, opt-in) | Implemented |
| Orchestration (typed workflows, checkpoints, worker pools) | Implemented / evolving |
| Coroutine cancellation correctness (audited paths: all 7 provider adapters, engine, workflow steps, tools) | 🟡 In progress — runtime-boundary characterization pending |
| Cancellation rethrow static analysis (KotlinCancellationCatchScanner) | ✅ Implemented — quality gate scans all non-example modules |
| RAG (ingestion, chunking, embeddings, vector stores) | Implemented / evolving |
| Chat memory (token-aware, persistent) | Implemented / evolving |
| MCP adapter | Implemented / evolving |
| Workflow scheduling | Implemented / evolving |
| HTTP server (REST, webhooks, SSE) | Implemented / evolving |
| Multi-tenancy platform | Implemented / evolving |

## Released

| Version | Date | Notes |
|---|---|---|
| 0.4.0 | 2026-07-06 | Latest published release. Sovereign Runtime and evidence-handoff milestone. |
| 0.3.1 | 2026-05-24 | Previous stable core release. |
| 0.3.0 | — | Typed AI services, structured output, full provider suite. |
| 0.2.0 | — | Orchestration, scheduling, server, platform modules introduced. |

## Current Development Train

The `master` branch has been prepared for **TramAI 0.5.0** release.

The 0.5.0 train contains the post-sovereignty roadmap: workflow API stability, structured-output hardening, workflow and approval ergonomics, runtime evidence export, tool governance, and product adoption work.

### Implemented in 0.5.0

- Workflow API stability boundary
- Structured-output lifecycle and repair hardening
- JavaBean DTO schema generation
- Governed workflow examples and diagnostics
- Approval ergonomics and failure taxonomy
- Runtime evidence exporters and bundle wiring
- Dedicated tool.permission runtime evidence family
- Tool exposure/execution governance proofs
- Tool-governance usage example
- Product positioning and JVM framework comparison

### 0.5.0 Release Status

- 0.5.0 release candidate prepared — publication pending
- Remote publication pending tag and Central Portal acceptance
- 0.4.0 remains the latest confirmed published version until publication completes

## Sovereign Runtime

**Status: Release Candidate boundary declared / active development**

The Sovereign Runtime RC boundary is now documented and locally verifiable through:

```bash
./gradlew verifySovereignRuntimeReleaseCandidate --no-configuration-cache --rerun-tasks
```

This includes governed runtime execution, sovereign routing, DLP, replay-safe approvals, encrypted file-backed persistence, audit/outbox recovery, worker observability, and release evidence generation.

For the full RC boundary declaration, see [docs/releases/sovereign-runtime-rc-boundary.md](./releases/sovereign-runtime-rc-boundary.md).

Not yet included: stable 1.0 API, Maven Central release, key rotation, and cloud-provider production certification.

## Planned / Not Complete

The following are intentionally not claimed as complete. Some are planned, some are deferred, and some represent infrastructure that does not yet exist:

- stable 1.0 public API
- Maven Central release of sovereign-runtime modules
- key rotation
- complete API reference documentation
- production-certified dashboards and organization-specific alert tuning

No timelines are committed for these items.

## Validation

The CI pipeline runs the full test suite on every push:

```bash
./gradlew test --rerun-tasks
```

All sovereign-runtime tests (policy, approval, audit, outbox, replay, persistence) pass against the current `master` branch. No tests are skipped or marked as flaky.

## Release Readiness

The current sovereign runtime release-readiness checklist and module matrix are tracked in:

- [docs/releases/sovereign-runtime-release-readiness.md](./releases/sovereign-runtime-release-readiness.md)
- [docs/modules/sovereign-runtime-module-matrix.md](./modules/sovereign-runtime-module-matrix.md)

For first-time integration, see [Sovereign Runtime Quickstart](./guides/sovereign-runtime-quickstart.md).

For a regulated workflow reference scenario, see [Regulated Claim Triage Reference Scenario](./scenarios/regulated-claim-triage.md).

For the production-hardening direction toward database-backed persistence, see [Sovereign JDBC Persistence Design](./architecture/sovereign-jdbc-persistence-design.md).

For production deployment guidance, see [Sovereign JDBC Production Deployment Runbook](./runbooks/sovereign-jdbc-production-deployment.md).

These documents cover included capability areas, representative modules, validation commands, explicit non-goals, and known release risks.

## Historical Context

Prior to June 2026, TramAI was primarily positioned as a typed AI integration library. The pivot to a governed AI workflow runtime began with the sovereign-runtime modules. The ROADMAP.md at the repository root documents the full enterprise vision and phased plan.
