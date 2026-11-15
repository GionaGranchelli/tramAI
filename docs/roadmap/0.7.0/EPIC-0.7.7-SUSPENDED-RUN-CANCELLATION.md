# Epic 0.7.7 — Authoritative Persisted Suspended-Run Cancellation

**Branch:** `epic/0.7.7-suspended-run-cancellation`
**Status:** 🟡 Implementation Complete; Integration Rebaseline & Cancellation Boundary Pending
**Dependencies:** 0.7.6 HARD (Rebaselined under [DEPENDENCY-BASELINE-0.7.7.md](../0.7.7/DEPENDENCY-BASELINE-0.7.7.md))

## Executive decision

Make cancellation of a persisted suspended run an authoritative terminal lifecycle outcome, not checkpoint deletion or an application-local flag.

## Primary invariant

```text
cancelled(run) => no subsequent authoritative execution(run)
cancelled(run) != already-produced external effects compensated
```

Once cancellation wins, no model, tool, approval continuation, network action, or workflow side effect may resume that run.

## Scope

- terminal persisted cancellation state/record;
- authorized typed cancellation command;
- resume fencing;
- late-approval continuation fencing;
- cancel/resume atomic winner semantics;
- restart survival;
- idempotent repeated cancellation;
- terminal evidence/timeline/reconstruction support;
- cleanup behavior that cannot erase authoritative cancellation semantics;
- evidence semantics that distinguish fencing future execution from compensating or reversing side effects that already occurred.

## Non-goals

- redesigning all approval lifetime/replacement semantics;
- generic distributed workflow engine rewrite;
- application-emulated cancellation flags;
- generic saga/compensation engine;
- claiming that terminal cancellation automatically reverses an email, payment, deployment, external API mutation, or other already-completed side effect.

## Tasks & Delivery Ledger

The core engine, store, lease, admission, and evidence mechanisms were delivered through milestones D7-A through D7-E1, and integrated in milestone **D7-F** (PR #591, commit `b97e4179`):

| ID / Deliverable | Scope | Status | Key Artifacts |
|---|---|---|---|
| **D7-A** (0.7.7a/b) | Durable cancellation contract & lifecycle state | ✅ **Delivered** | [TASK-0.7.7a](../0.7.7/TASK-0.7.7a-CANCELLATION-AUTHORITY-CONTRACT.md) |
| **D7-B** (0.7.7b/c) | Per-effect admission fencing (E1–E8) & permit contract | ✅ **Delivered** | [TASK-0.7.7b](../0.7.7/TASK-0.7.7b-PER-EFFECT-ADMISSION-CONTRACT.md) |
| **D7-C** (0.7.7d) | Recovery re-execution fencing & lease store (E10) | ✅ **Delivered** | [TASK-0.7.7c](../0.7.7/TASK-0.7.7c-RECOVERY-REEXECUTION-FENCING.md) |
| **D7-D** (0.7.7g) | Per-effect evidence schemas (E1–E8) | ✅ **Delivered** | [TASK-0.7.7e](../0.7.7/TASK-0.7.7e-PER-EFFECT-EVIDENCE-CONTRACT.md)–[TASK-0.7.7i](../0.7.7/TASK-0.7.7i-EXTERNAL-EFFECT-EVIDENCE.md) |
| **D7-E1** (0.7.7e) | Approval resume continuation evidence (E9) | ✅ **Delivered** | [TASK-0.7.7j](../0.7.7/TASK-0.7.7j-E9-EVIDENCE-LINEAGE-CONTRACT.md), [TASK-0.7.7k](../0.7.7/TASK-0.7.7k-E9-DURABLE-APPROVAL-RESUME-EVIDENCE.md) |
| **D7-F** (0.7.7f/h) | Cross-boundary cancellation & evidence integration closure | ✅ **Delivered** | [TASK-0.7.7l-D7-F-INTEGRATION-CLOSURE.md](../0.7.7/TASK-0.7.7l-D7-F-INTEGRATION-CLOSURE.md) (PR #591) |
| **D7-Rebaseline** | Dependency rebaseline against delivered 0.7.6 | 🟡 **Active / Provisional** | [DEPENDENCY-BASELINE-0.7.7.md](../0.7.7/DEPENDENCY-BASELINE-0.7.7.md) |

### Release Blocker — Cancellation Control Entry Point Authority

While internal engine-level cancellation is fully fenced and qualified in D7-F (`WorkflowCheckpointStore.cancel(...)`), the whole-repository security inventory in 0.7.6h identified that **no authenticated, authorized, typed cancellation control endpoint exists in `tramai-spring-boot-starter-sovereign-ops-rest`**. The existing `DELETE /workflows/{name}/runs/{id}` endpoint in `tramai-server` is an unsupported, unauthenticated internal prototype.

**Implementing an authenticated REST cancellation endpoint or securing an explicit ADR scoping cancellation strictly to in-process JVM callers is an explicit release gate blocker for Epic 0.7.7 release promotion.**

## Acceptance criteria

- Cancelled persisted run can never resume authoritative execution.
- Competing cancel/resume has one authoritative winner.
- Cancellation survives process restart.
- Repeated cancellation is idempotent.
- Late approval cannot authorize continuation.
- Cancellation remains visible to timeline/reconstruction.
- Timeline/evidence never represents cancellation itself as proof that external side effects completed before cancellation were reversed or compensated.

## Adversarial proof

Required scenarios:

1. cancel then normal resume;
2. cancel then restart/recovery resume;
3. cancel then late approval;
4. cancel/resume race in both winner orders;
5. repeated cancel;
6. cleanup/checkpoint removal after cancellation;
7. stale worker attempts continuation after cancellation.

Any model/tool/network/workflow side effect after authoritative cancellation is a release blocker. A run cancelled after an earlier external side effect must also remain reconstructable without implying that cancellation compensated that already-completed effect.

## Mutation expectations

High priority: remove terminal-state check, invert transition guard, permit approval continuation, make cancellation non-durable, weaken atomic compare/version check, or treat missing checkpoint as equivalent to cancelled.
