# Approval Failure Taxonomy

> **Status:** Guide — explains approval failure and terminal outcome categories.
> **Phase:** Phase 4 — Approval & Human Gates of the [Post-Sovereignty Roadmap](../POST-SOVEREIGNTY-ROADMAP.md).
> **Prerequisites:** Familiarity with the [Approval Workflow Ergonomics Guide](approval-workflow-ergonomics.md), the [Approval Gateway Golden Path](approval-gateway-golden-path.md), and the [Governed Workflow Quickstart](governed-workflow-quickstart.md).

---

## What This Guide Covers

This guide categorises the outcomes produced by TramAI's approval system at two boundaries:

1. **Workflow-facing request outcomes** — returned by `ApprovalGateway.requestApproval()` and mapped through `toWorkflowResult { ... }`.
2. **Decision-control-plane outcomes** — returned by `ApprovalDecisionControlPlane.approve()` and `ApprovalDecisionControlPlane.deny()`.

It explains which outcomes are terminal, which are retryable or operator-correctable, which emit mutation evidence, and what each outcome does and does not prove.

This guide is **not** a troubleshooting guide. For runtime workflow failures (policy gate, provider failures, structured output parse errors), see the [Governed Workflow Troubleshooting Guide](governed-workflow-troubleshooting.md).

---

## Quick Taxonomy

| Category | Outcome | Boundary | Retryable? | Emits Evidence? |
|----------|---------|----------|------------|-----------------|
| Pending approval | `Suspended` | Request gateway | Wait or resume later | Request evidence |
| Already approved | `AlreadyApproved` (gateway) / `AlreadyApproved` (decision plane) | Gateway / decision plane | No retry needed | Decision evidence (decision plane) |
| Approved | `Approved` | Decision plane | No retry needed | Decision evidence |
| Denied | `AlreadyDenied` (gateway) / `Denied` (decision plane) / `AlreadyDenied` (decision plane) | Gateway / decision plane | New request required | Decision evidence |
| Expired | `Expired` | Gateway / decision plane | New request required | Expiry/request evidence |
| Not found | `NotFound` | Decision plane | Check approval ID | No mutation evidence |
| Version conflict | `Conflict` | Decision plane | Re-fetch and retry | No successful mutation evidence |
| Unauthorised actor | `Conflict` | Decision plane | Use authorised actor | No successful mutation evidence |
| Missing role | `Conflict` | Decision plane | Supply correct role | No successful mutation evidence |
| Invalid state transition | `Conflict` | Decision plane | Cannot retry; inspect approval | No successful mutation evidence |
| Mutation store failure | `Conflict` | Decision plane | Inspect logs; retry if transient | No successful mutation evidence |

---

## Terminal Outcomes

Terminal outcomes represent a point beyond which the current approval cannot proceed without a new request.

| Outcome | Why Terminal |
|---------|-------------|
| `Approved` | The approval has been granted. Subsequent decision attempts return `AlreadyApproved`. |
| `Denied` | The approval has been rejected. Subsequent decision attempts return `AlreadyDenied`. |
| `AlreadyApproved` | The approval was already granted by a previous decision. No further state change is possible. |
| `AlreadyDenied` | The approval was already rejected by a previous decision. No further state change is possible. |
| `Expired` | The approval's time window has elapsed. The workflow cannot proceed through this approval. |
| `NotFound` | The referenced approval ID does not exist in the store. |

**Important:** A denied approval is not a system failure. It is a valid terminal business outcome. The workflow should handle denial explicitly, just as it handles a successful continuation. See [Denial as a First-Class Outcome](approval-workflow-ergonomics.md#pattern-3--denial-as-a-first-class-outcome).

---

## Retryable / Operator-Correctable Outcomes

These outcomes occur when the decision request itself is malformed or the approval is in an unexpected state. They may be correctable by the caller without creating a new approval request.

| Outcome | Typical Cause | Correction |
|---------|--------------|------------|
| `Conflict` — version mismatch | Concurrent modification; the approval was changed between fetch and decision. | Re-fetch the approval, verify the intended state, and retry with the new version. |
| `Conflict` — unauthorised actor | The actor does not have the required role or permission. | Verify the actor identity and role, then retry with a correctly authorised actor. |
| `Conflict` — invalid state | The approval is not in `PENDING` state. | Inspect the current approval status; terminal states cannot be acted upon. |
| `Conflict` — mutation store failure | A transient persistence or transactional error occurred. | Inspect the store logs; retry if the error appears transient. |

Operator-correctable outcomes should be presented to an operator or automated retry logic, not to a workflow. They indicate that the decision delivery mechanism needs attention, not that the approval itself should be reconsidered.

---

## Evidence Semantics

Each approval outcome has a specific evidence footprint in the audit outbox:

| Outcome | Evidence Created | Notes |
|---------|-----------------|-------|
| `Suspended` (via gateway) | `approval-requested.<id>` outbox record (when `ApprovalGatewayAuditIntentFactory` is present) | Created atomically with the three core suspension records in JDBC-backed deployments. |
| `Approved` (decision plane) | `approval-approved.<id>` outbox record | Created atomically with the approval state transition. Contains actor, correlation ID, workflow run ID, reason digest/length (not raw text). |
| `Denied` (decision plane) | `approval-denied.<id>` outbox record | Same structure as approve evidence. Denial is a first-class evidence-emitting outcome. |
| `AlreadyApproved` / `AlreadyDenied` / `Expired` / `NotFound` | No new evidence | These outcomes represent no state mutation — the approval was not changed, so no evidence record is created. |
| `Conflict` | No successful mutation evidence | The approval state was not changed. An unsuccessful outbox intent may remain as `PREPARED` (orphaned) if the mutation failed after the outbox append. |

**Evidence records prove process, not correctness.** They show that a decision was made, who made it, and what outcome was recorded. They do not prove:

- The decision was correct (legally, medically, financially, or otherwise).
- The reviewer had sufficient information to make an informed decision.
- The review process meets any specific regulatory or compliance standard.

See the [Approval Workflow Ergonomics Guide](approval-workflow-ergonomics.md#pattern-6--approval-evidence-and-audit-notes) for the full evidence discussion.

---

## Outcome Boundary Comparison

The two boundaries serve different purposes and have different contracts:

| Aspect | Gateway Outcomes | Decision-Control-Plane Outcomes |
|--------|-----------------|---------------------------------|
| API | `ApprovalGateway.requestApproval()` | `ApprovalDecisionControlPlane.approve()`, `.deny()` |
| Called by | Workflow code | Operator tooling, REST endpoints, automation |
| Caller drives | Workflow execution state | Approval lifecycle state |
| Outcomes | `Suspended`, `AlreadyApproved`, `AlreadyDenied`, `Expired` | `Approved`, `Denied`, `AlreadyApproved`, `AlreadyDenied`, `Expired`, `NotFound`, `Conflict` |
| Summary | Workflow-facing: what should the workflow do next? | Operator-facing: what happened to the approval state? |

A workflow calls `requestApproval()` to create a suspension point or check the current request status. Operator or automation code calls the decision control plane to transition a pending approval. The two boundaries share the same underlying stores but expose different outcome sets.

---

## Non-Claims

- This taxonomy does not represent a formal classification system (such as error codes, retry policies, or SLA guarantees).
- It does not enumerate application-specific validation failures (such as domain policy violations, schema validation errors, or business rule rejections).
- It does not add runtime behavior, API changes, new approval states, or new persistence semantics.
- Terminal outcomes are not necessarily error states — denial and expiry are expected business outcomes for many workflows.
- Approval evidence proves process, not correctness. Evidence records show that a decision was recorded; they do not prove the decision was right, compliant, or sufficiently reviewed.

---

## Where to Look Next

| Topic | Link |
|-------|------|
| Approval Workflow Ergonomics Guide | [Ergonomics Guide](approval-workflow-ergonomics.md) |
| Approval Gateway Golden Path | [Golden Path Guide](approval-gateway-golden-path.md) |
| Runnable approval example | [`examples/approval-resume`](../../examples/approval-resume) |
| Governed Workflow Troubleshooting | [Troubleshooting Guide](governed-workflow-troubleshooting.md) |
| Workflow lifecycle model | [Lifecycle Model](../workflow-lifecycle-model.md) |
| Approval decision evidence tests | [JdbcSovereignOpsApprovalDecisionControlPlaneTest](../../tramai-spring-boot-starter-sovereign-persistence-jdbc/src/test/kotlin/dev/tramai/spring/sovereign/persistence/jdbc/JdbcSovereignOpsApprovalDecisionControlPlaneTest.kt) |
| Sovereign Runtime Quickstart | [Quickstart](sovereign-runtime-quickstart.md) |
