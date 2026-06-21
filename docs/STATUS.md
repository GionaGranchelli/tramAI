# TramAI Status

TramAI is under **active development**.

This document describes the state of the repository, not a formal release contract. It tracks what is implemented and evolving on `master`, and what is intentionally not yet complete.

## Implemented / Evolving

| Area | Status |
|---|---|
| Typed AI services (`@AiService`, `@Operation`) | Implemented / evolving |
| Structured output (schema generation, validation, retry) | Implemented / evolving |
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
| Sovereign ops worker observability runbook | Implemented |
| Sovereign ops audit outbox OpenTelemetry metrics | Implemented |
| Evidence generation (bundles, release artifacts) | Implemented / evolving |
| Sovereign document intelligence example | Implemented |
| OpenTelemetry observability (spans, metrics, opt-in) | Implemented |
| Orchestration (typed workflows, checkpoints, worker pools) | Implemented / evolving |
| RAG (ingestion, chunking, embeddings, vector stores) | Implemented / evolving |
| Chat memory (token-aware, persistent) | Implemented / evolving |
| MCP adapter | Implemented / evolving |
| Workflow scheduling | Implemented / evolving |
| HTTP server (REST, webhooks, SSE) | Implemented / evolving |
| Multi-tenancy platform | Implemented / evolving |

## Released

| Version | Date | Notes |
|---|---|---|
| 0.3.1 | 2026-05-24 | Latest tagged release. Stable core library surface. |
| 0.3.0 | — | Typed AI services, structured output, full provider suite. |
| 0.2.0 | — | Orchestration, scheduling, server, platform modules introduced. |

## Unreleased Work

Several sovereign-runtime capabilities currently exist on `master` but are not yet part of a tagged release. Check release tags before depending on a capability as a published API.

Unreleased capabilities include:

- sovereign routing and trust zones
- policy enforcement and DLP/redaction
- approval gates and replay-safe resume
- encrypted file-backed persistence
- local model registry verification
- audit chain and audit outbox
- audit outbox background worker
- evidence generation examples

APIs in these areas may change before the next release.

## Sovereign Runtime

**Status: Release Candidate boundary declared / active development**

The Sovereign Runtime RC boundary is now documented and locally verifiable through:

```bash
./gradlew verifySovereignRuntimeReleaseCandidate --no-configuration-cache --rerun-tasks
```

This includes governed runtime execution, sovereign routing, DLP, replay-safe approvals, encrypted file-backed persistence, audit/outbox recovery, worker observability, and release evidence generation.

For the full RC boundary declaration, see [docs/releases/sovereign-runtime-rc-boundary.md](./releases/sovereign-runtime-rc-boundary.md).

Not yet included: stable 1.0 API, Maven Central release, database-backed persistence, distributed worker coordination, key rotation, and production deployment certification.

## Planned / Not Complete

The following are intentionally not claimed as complete. Some are planned, some are deferred, and some represent infrastructure that does not yet exist:

- stable 1.0 public API
- Maven Central release of sovereign-runtime modules
- REST/Actuator operational endpoints
- database-backed outbox or persistence
- distributed worker leader election
- key rotation
- full production deployment guide
- complete API reference documentation

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

These documents cover included capability areas, representative modules, validation commands, explicit non-goals, and known release risks.

## Historical Context

Prior to June 2026, TramAI was primarily positioned as a typed AI integration library. The pivot to a governed AI workflow runtime began with the sovereign-runtime modules. The ROADMAP.md at the repository root documents the full enterprise vision and phased plan.
