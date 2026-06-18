# TramAI Architecture

## Purpose

TramAI separates AI workflow code from governance concerns such as policy, approval, audit, routing, persistence, and replay safety. The architecture is layered so that application teams write typed `@AiService` interfaces while the runtime enforces constraints that would otherwise be scattered across business logic.

## Architectural Layers

| Layer | Responsibility |
|---|---|
| Application workflow | Business-level AI use cases expressed through typed operation contracts. |
| AI service layer | Typed `@AiService` invocation, structured output handling, provider dispatch. |
| Security/governance layer | Policy checks, DLP, redaction, approval gates, replay safety. |
| Sovereign routing layer | Determines whether work can use local, restricted, or cloud routes. |
| Persistence layer | Durable encrypted stores for suspended invocations, approval state, audit streams, and outbox records. |
| Ops layer | Operator-facing recovery and audit operations. |
| Evidence layer | Output artifacts that prove what happened and under which constraints. |

## Core Flow

1. Application code defines an `@AiService` interface with typed inputs and outputs.
2. TramAI generates a proxy for that interface at startup.
3. A method call is translated into an AI operation.
4. Inputs are rendered into model messages.
5. If the return type is structured, TramAI generates or loads a cached schema and validates the response.
6. Provider resolution selects the primary route and any configured fallbacks.
7. The provider call is wrapped in observability instrumentation when OpenTelemetry is available.
8. The provider returns raw model output plus metadata.
9. TramAI either returns raw text, streams chunks, or parses and validates structured output.
10. Parse failures enter a retry loop with validation feedback.

### Structured Output Retry

When a structured operation fails to parse, TramAI distinguishes three failure classes: not JSON, wrong structure, validation failure. For each retry, the engine appends the broken response and generated feedback message, then resubmits on the next attempt. If the retry limit is exhausted, `StructuredOutputException` carries the original prompt, last raw response, validation error, and attempt count.

## Policy and DLP

Policy decisions happen before sensitive execution:

- data classification is applied to inputs before they reach model providers
- DLP/redaction prevents sensitive data in prompts, logs, replay envelopes, and audit views
- logs, summaries, and operational DTOs must not leak raw tokens, prompts, envelopes, approval IDs, denial reasons, tool arguments, or model responses
- fail-closed is the default for unsafe governance paths

## Approval and Replay Safety

Risky workflows can suspend for approval:

- the engine stores continuation state and throws `ApprovalSuspendedException`
- resume uses the stored approval record, expected versions, and a presented approval token
- replay envelopes are not trusted as raw mutable payloads
- resume uses registered trusted descriptors/contracts
- exactly-once tool execution is guaranteed through engine-provided idempotency keys

## Local/Cloud Routing

Sovereign routing is a runtime security decision, not a configuration convenience:

- application code declares a model and provider
- the policy layer checks whether the route is permitted for the current workload
- restricted data must not silently fall back to cloud providers
- local model artifacts require registry verification before use
- unknown models, providers, and routes are denied by default

## File-Backed Persistence

Encrypted durable stores exist for local sovereign runtime state:

- store types: `ApprovalStore`, `ApprovalContinuationStore`, `AuditStore`, `SuspendedInvocationStore`, `SovereignOpsAuditOutboxStore`
- each record is individually encrypted with AES-GCM, stored as a single file
- stores rebuild in-memory indexes from the filesystem on every open
- persistence is intended for audit, recovery, and operational resilience — not a replacement for all application databases
- activated through the `tramai.sovereign.persistence.type=file` property and a provided AES key

## Audit Outbox

The audit outbox provides durable audit emission that survives process restarts:

```
business operation commits governance decision
  -> audit outbox record is written as PREPARED (not dispatchable)
  -> business mutation commits
  -> record is marked PENDING (dispatchable)
  -> dispatcher claims PENDING/FAILED_RETRYABLE records
  -> dispatcher emits audit events from pre-digested outbox data
  -> on success: marked EMITTED
  -> on failure: marked FAILED_RETRYABLE (retryable)
  -> stuck EMITTING records expire and are re-claimed
  -> stuck PREPARED records are recoverable through a resolver SPI
```

The outbox never stores raw approval IDs, raw reason text, tokens, envelopes, prompts, or tool arguments. Only pre-digested identifiers and bounded metadata.

## Background Outbox Worker

A Spring-managed background worker can periodically recover and dispatch outbox records:

- **disabled by default** (`tramai.sovereign.ops.outbox.worker.enabled=false`)
- opt-in through configuration
- recovers stuck PREPARED records before dispatching PENDING/FAILED_RETRYABLE records
- rethrows `CancellationException` for coroutine correctness
- catches `RuntimeException` and checked `Exception` as sanitized failure summaries
- lifecycle logs sanitized warnings (action + error code only, no throwable/stacktrace)
- fail-closed by default when dispatch is enabled but a dispatcher bean is missing

## Design Principles

- **Fail closed** for unsafe governance paths.
- **Prefer explicit contracts** over implicit prompt conventions or model-name heuristics.
- **Keep sensitive values out** of logs, summaries, replay envelopes, and audit views.
- **Make recovery deterministic and testable** — `runOnce()` is directly callable in tests.
- **Treat local/cloud routing as a runtime security decision**, not a fallback chain.
- **Keep operational tooling separate** from workflow business logic.
- **Deny by default** — tools, providers, network destinations are unavailable until explicitly authorized.
