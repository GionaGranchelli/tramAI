# Workflow Lifecycle Model

> **Status:** Documentation — describes the conceptual lifecycle of a governed TramAI workflow.
> **Phase:** Phase 1 / Epic 1 of the [Post-Sovereignty Roadmap](POST-SOVEREIGNTY-ROADMAP.md).
> **Depends on:** [Workflow API Stability Boundary](workflow-api-stability-boundary.md) for the stability level of each stage's APIs.

---

## Purpose

This document explains how a governed TramAI workflow moves from an application request to a typed result, denial, approval requirement, failure, audit record, and future runtime evidence export.

It is the conceptual bridge between:

- The [workflow API stability boundary](workflow-api-stability-boundary.md) (which APIs are safe to use)
- The structured output contract lifecycle (how typed contracts are generated, validated, and repaired)
- The approval workflow (how human gates suspend and resume execution)
- The policy engine (how security decisions are enforced before execution)
- Audit and persistence (how runtime decisions are recorded)
- Future runtime evidence export (how runtime decisions map to reviewable evidence)

This document describes a **model**, not a new runtime API. The stages below correspond to existing TramAI capabilities, classified by the workflow API stability boundary.

---

## Lifecycle Overview

```
┌──────────────┐
│   Request    │  Stage 1
└──────┬───────┘
       │
       ▼
┌──────────────────┐
│ Contract Binding │  Stage 2
└──────┬───────────┘
       │
       ▼
┌────────────────────┐
│ Policy Evaluation  │  Stage 3
└──────┬────────┬────┘
       │        │
       │        └── Denied → Stage 7 (Audit) → Stage 8 (Failure)
       │
       ▼
┌──────────────────────────┐
│ Provider / Tool          │  Stage 4
│ Execution                │
└──────┬───────────────────┘
       │
       ▼
┌──────────────────────────┐
│ Structured Output        │  Stage 5
│ / Repair                 │
└──────┬──────────┬────────┘
       │          │
       │          └── Failed → Stage 7 (Audit) → Stage 8 (Failure)
       │
       ▼
┌──────────────────┐
│  Approval Gate?  │  Stage 6
└──────┬──────┬────┘
       │      │
       │      └── Required → Suspended → Approved / Denied / Expired
       │
       ▼
┌──────────────────────┐
│ Audit / Persistence  │  Stage 7
└──────┬───────────────┘
       │
       ▼
┌──────────────────────┐
│ Result / Failure     │  Stage 8
└──────────────────────┘
       │
       ▼
┌──────────────────────┐
│ Runtime Evidence     │  Stage 9 (future)
│ Export (optional)    │
└──────────────────────┘
```

---

## Stage 1 — Request

An application initiates a TramAI workflow by calling a workflow-facing API. Inputs are bound to typed Kotlin or Java parameters through the declared method signature.

### What happens

- The application calls a method on an `@AiService`-annotated interface.
- The method signature defines typed parameters and a typed return value.
- `@UserMessage` and `@SystemMessage` templates bind method parameters to prompt context.
- `@ConversationId` may attach a conversation identifier for stateful workflows.
- `@Operation` specifies model selection, temperature, max tokens, and other execution parameters.

### Stable concepts involved

| Concept | Stability |
|---------|-----------|
| `@AiService` | Stable |
| `@Operation` | Stable |
| `@UserMessage` / `@SystemMessage` | Stable |
| `@ConversationId` | Stable |
| Typed method parameters | Stable |

### Failure modes

- Invalid or missing parameter bindings.
- Unresolvable model or provider reference in `@Operation`.

---

## Stage 2 — Contract Binding

TramAI derives or resolves the expected typed input and output contract before executing the model or tool. This ensures the workflow knows what shape of data it expects.

### What happens

- For structured outputs, TramAI derives a JSON Schema contract from the Kotlin/Java return type.
- Validation annotations (`@AIRange`, `@AIMinItems`) are incorporated into the contract.
- The contract is used later in Stage 5 (Structured Output and Repair) to validate the provider's response.

### Stable concepts involved

| Concept | Stability |
|---------|-----------|
| Typed return values | Stable |
| `@AIRange` | Stable |
| `@AIMinItems` | Stable |
| Contract derivation internals | Internal |

### Boundary

Contract binding improves typed handling but does **not** guarantee model correctness, factual accuracy, or complete validation coverage. Extension points for custom validators remain Preview (see roadmap Phase 2).

---

## Stage 3 — Policy Evaluation

Before executing a sensitive operation (provider call, tool invocation, or workflow transition), TramAI evaluates applicable policy rules.

### What happens

- `PolicyEngine` evaluates configured policies against the current request context.
- A `PolicyDecision` records the result: **allow**, **deny**, or a conditional decision requiring additional handling.
- Denied operations are blocked before reaching the provider or tool executor.
- Policy decisions are designed to be auditable.

### Stable concepts involved

| Concept | Stability |
|---------|-----------|
| `PolicyEngine` | Stable |
| `PolicyDecision` | Stable |
| `PolicyContext` | Stable |
| `EnforcementPoint` | Stable |
| Data classification enums | Stable |
| Policy violation exceptions | Stable |

### Outcomes

| Outcome | Next |
|---------|------|
| **Allow** | Proceed to Stage 4 (Provider/Tool Execution) |
| **Deny** | Proceed to Stage 7 (Audit) → Stage 8 (Failure) |

### Failure modes

- `PolicyViolationException` — operation blocked by policy.
- Unconfigured policy for a required domain.

### Boundary

Policy evaluation supports governed execution. It does **not** prove legal or regulatory compliance.

---

## Stage 4 — Provider or Tool Execution

If policy allows execution, TramAI routes the request to a configured provider (local or remote) or invokes an allowed tool.

### What happens

- TramAI resolves the provider from the `ProviderRegistry` based on the `@Operation` model reference.
- The request is dispatched to the selected provider or tool.
- Results stream back for structured output processing.
- Tool calls may include function invocations defined with `@AiTool`.

### Stable concepts involved

| Concept | Stability |
|---------|-----------|
| `ProviderRegistry` | Stable |
| Provider adapters | Stable |
| `@AiTool` | Stable |

### Preview concepts

| Concept | Stability |
|---------|-----------|
| Sovereign routing / trust zones | Preview |
| Tool governance (deny by policy, audit) | Preview |
| MCP adapter surface | Preview |

### Outcomes

| Outcome | Next |
|---------|------|
| **Provider response received** | Proceed to Stage 5 (Structured Output) |
| **Tool result received** | Proceed to Stage 5 (Structured Output) |
| **Provider/tool failure** | Proceed to Stage 7 (Audit) → Stage 8 (Failure) |

### Failure modes

- Provider unavailable or unreachable.
- Tool execution throws `ToolException`.
- Model or provider not registered.

### Boundary

Tool governance and MCP permission models are **Preview** areas in the current roadmap. Full MCP connector API stability is **Deferred**.

---

## Stage 5 — Structured Output and Repair

Provider output is parsed into the expected typed contract. If parsing or validation fails, TramAI may use repair feedback where supported.

### What happens

- The raw provider response is parsed into the typed return value.
- Validation annotations (`@AIRange`, `@AIMinItems`) are checked against the parsed result.
- On parse or validation failure, TramAI may send structured repair feedback to the provider for a retry.
- After repair attempts, the result is either a valid typed value or a terminal structured output failure.

### Stable concepts involved

| Concept | Stability |
|---------|-----------|
| `@AIRange` | Stable |
| `@AIMinItems` | Stable |
| Parse-failure exceptions | Stable |
| Structured output schema generation | Internal |

### Preview concepts

| Concept | Stability |
|---------|-----------|
| Custom validator extensions | Preview (Phase 2 of roadmap) |
| Repair feedback loop ergonomics | Preview |

### Outcomes

| Outcome | Next |
|---------|------|
| **Valid typed result** | Proceed to Stage 6 (Approval Gate) |
| **Terminal parse/validation failure** | Proceed to Stage 7 (Audit) → Stage 8 (Failure) |

### Failure modes

- Parse failure (output does not match expected schema).
- Validation failure (output fails `@AIRange` or `@AIMinItems` constraints).
- Repair loop exhaustion (maximum retry attempts reached without valid result).

### Boundary

Structured output improves typed handling but does **not** guarantee model correctness or factual truth. Validation checks structural constraints, not semantic correctness.

---

## Stage 6 — Approval Gate

Some workflows may require human approval before completion, resumption, or executing a high-risk action.

### What happens

- `ApprovalGateway` creates an approval request and returns a `SovereignWorkflowResult`.
- The workflow may be **suspended** pending a human decision.
- A human (or automated system) issues a decision: **approved**, **denied**, or the request **expires**.
- On approval, the workflow resumes with the approved continuation.
- On denial or expiry, the workflow terminates with the corresponding outcome.

### Stable concepts involved

| Concept | Stability |
|---------|-----------|
| `ApprovalGateway` | RC+ Stable |
| `ApprovalRequestResult` | RC+ Stable |
| `SovereignWorkflowResult` | RC+ Stable |
| `ApprovalRequestResult.toWorkflowResult` | RC+ Stable |
| `ApprovalWorkflowResults` | RC+ Stable |
| `ApprovalRequestResults` | RC+ Stable |
| `HumanApprovalDecisions` | RC+ Stable |

### Preview concepts

| Concept | Stability |
|---------|-----------|
| REST/control-plane surfaces | Preview |
| Reviewer UI | Preview |

### Outcomes

| Outcome | Next |
|---------|------|
| **Approved** | Proceed to Stage 7 (Audit) → Stage 8 (Result) |
| **Denied** | Proceed to Stage 7 (Audit) → Stage 8 (Failure) |
| **Expired** | Proceed to Stage 7 (Audit) → Stage 8 (Failure) |
| **Suspended (pending)** | Await decision — no immediate next stage |
| **Not required** | Skip Stage 6 — proceed to Stage 7 |

### Failure modes

- Invalid or missing approval actor.
- Expired approval request.
- Approval denied by human decision.

### Boundary

Human approval records a decision. It does **not** prove the business or legal correctness of the approved action.

---

## Stage 7 — Audit and Persistence

Runtime decisions, policy evaluations, approval outcomes, and workflow transitions may be recorded by configured audit and persistence modules.

### What happens

- Policy decisions may emit audit events.
- Approval decisions may be recorded in an approval store.
- Workflow state may be persisted for resumption or replay.
- Audit outbox dispatch is handled by internal worker mechanisms.

### Stable concepts involved

| Concept | Stability |
|---------|-----------|
| Audit decision concepts | Stable |
| Approval store SPIs | RC+ Stable |

### Internal concepts

| Concept | Stability |
|---------|-----------|
| JDBC store implementations | Internal |
| Audit outbox internals | Internal |
| Worker lease internals | Internal |
| Resume credential custody | Internal |

### Boundary

Audit records decisions and structural changes. They do **not** certify compliance or guarantee completeness of external regulatory requirements.

---

## Stage 8 — Result or Failure

The workflow terminates with a typed result or a well-defined failure outcome.

### Outcomes

| Outcome | Meaning |
|---------|---------|
| **Typed success** | Workflow completed with the expected typed result |
| **Policy denial** | `PolicyViolationException` — policy blocked the operation |
| **Approval required** | Workflow suspended pending human decision (replay-safe) |
| **Approval denied** | Human decision denied continuation |
| **Approval expired** | Approval window expired before decision |
| **Structured output failure** | Output could not be parsed or repaired into the typed contract |
| **Provider/tool failure** | External or local provider/tool failed |
| **Internal failure** | Runtime, persistence, or unexpected failure |

### Stable concepts involved

| Concept | Stability |
|---------|-----------|
| `TramaiException` | Stable |
| `PolicyViolationException` | Stable |
| `ApprovalRequiredException` | Stable |
| `ApprovalSuspendedException` | Stable |
| `ToolException` | Stable |
| `ModelRegistryException` | Stable |

---

## Stage 9 — Optional Runtime Evidence Export (Future)

**This is a roadmap phase, not an implemented feature in this document.**

The lifecycle model reserves a place for runtime evidence export so that future work (Phases 5 and beyond) can map directly onto the existing lifecycle.

When implemented, Stage 9 would:

- Export policy decisions (allow/deny) into reviewable evidence records.
- Export approval decisions (approved/denied/expired) into reviewable evidence records.
- Export provider routing decisions (local/cloud) into reviewable evidence records.
- Map runtime events to the existing sovereign lab evidence bundle sections.

See roadmap [Phase 5 — Runtime Evidence Export](POST-SOVEREIGNTY-ROADMAP.md#phase-5--runtime-evidence-export).

---

## Lifecycle Outcomes Summary

```
┌──────────────┐     ┌──────────────────┐
│   Request    │────▶│ Contract Binding │
└──────────────┘     └────────┬─────────┘
                              │
                              ▼
                     ┌──────────────────┐
                     │ Policy Evaluation│
                     └──┬───────┬───────┘
                        │       │
                   Allow│       │Deny
                        │       ▼
                        │  ┌──────────────┐
                        │  │  Audit +     │
                        │  │  Failure     │
                        │  └──────────────┘
                        │
                        ▼
               ┌──────────────────┐
               │ Provider / Tool  │
               └──┬───────────────┘
                  │
                  ▼
          ┌──────────────────┐
          │ Structured       │
          │ Output / Repair  │
          └──┬───────┬───────┘
             │       │
        Valid│       │Failed
             │       ▼
             │  ┌──────────────┐
             │  │  Audit +     │
             │  │  Failure     │
             │  └──────────────┘
             │
             ▼
      ┌──────────────┐
      │Approval Gate │
      └──┬───────┬───┘
         │       │
    Not  │       │ Required →
   needed│       │ Suspended
         │       │
         │       ├─ Approved
         │       ├─ Denied
         │       └─ Expired
         │
         ▼
  ┌──────────────────┐
  │ Audit /          │
  │ Persistence      │
  └──┬───────────────┘
     │
     ▼
  ┌──────────────────┐      ┌────────────────────────┐
  │ Typed Result /   │ ────▶│ Runtime Evidence Export│
  │ Failure          │      │ (future, optional)     │
  └──────────────────┘      └────────────────────────┘
```

---

## Stable vs Preview Boundary

This lifecycle model is documentation only. The stability of the APIs involved in each stage follows the [Workflow API Stability Boundary](workflow-api-stability-boundary.md).

Key stability notes:

| Stage | Primary stability | Notes |
|-------|-------------------|-------|
| Request | Stable | `@AiService`, `@Operation`, prompt annotations |
| Contract Binding | Stable/Internal | Public types stable; schema derivation internal |
| Policy Evaluation | Stable | `PolicyEngine`, `PolicyDecision`, exceptions |
| Provider/Tool Execution | Stable/Preview | Provider dispatch stable; tool governance preview |
| Structured Output | Stable/Preview | Validator annotations stable; custom validators preview |
| Approval Gate | RC+ Stable | Gateway and result types stable per sovereign boundary |
| Audit/Persistence | Stable/Internal | SPIs stable; JDBC/worker internals internal |
| Result/Failure | Stable | Exception types stable |
| Evidence Export | Deferred | Not implemented until Phase 5 |

---

## Allowed Claims

It is allowed to say:

- TramAI has a documented workflow lifecycle model.
- The model explains request, contract binding, policy evaluation, provider/tool execution, structured output repair, approval, audit, persistence, and result/failure stages.
- The lifecycle model helps align examples, API boundaries, and future runtime evidence export.
- Some lifecycle stages are stable, while others are preview or deferred according to the workflow API stability boundary.

## Forbidden Claims

It is not allowed to say:

- The lifecycle model adds new runtime behavior.
- The lifecycle model proves production readiness.
- The lifecycle model proves legal or regulatory compliance.
- The lifecycle model provides EU AI Act conformity certification.
- Structured output guarantees factual correctness.
- Human approval proves business or legal correctness.
- Runtime evidence export is fully implemented by this document.

---

## Acceptance Criteria

This document is complete when:

1. It describes the lifecycle from request to result/failure.
2. It includes contract binding.
3. It includes policy evaluation.
4. It includes provider/tool execution.
5. It includes structured output and repair.
6. It includes approval gate states.
7. It includes audit and persistence.
8. It includes optional/future runtime evidence export.
9. It lists lifecycle outcomes.
10. It cross-links to the workflow API stability boundary.
11. The workflow API stability boundary links back to this document.
12. The post-sovereignty roadmap marks the lifecycle task complete.
13. The changelog records PR #167.
14. `./gradlew check` passes.
15. No runtime behavior, API, model, or compliance claims are added.

---

## Related Documents

| Document | Relationship |
|----------|--------------|
| [Post-Sovereignty Roadmap](POST-SOVEREIGNTY-ROADMAP.md) | Phase 1 defines the API stability epic that includes this lifecycle model |
| [Workflow API Stability Boundary](workflow-api-stability-boundary.md) | Defines the stability level of each lifecycle stage's APIs |
| [Sovereign Runtime API Stability Boundary](architecture/sovereign-api-stability-boundary.md) | Defines sovereign-runtime-specific stability for stores and SPIs |

---

*Part of Phase 1 / Epic 1 of the [Post-Sovereignty TramAI Roadmap](POST-SOVEREIGNTY-ROADMAP.md).*
