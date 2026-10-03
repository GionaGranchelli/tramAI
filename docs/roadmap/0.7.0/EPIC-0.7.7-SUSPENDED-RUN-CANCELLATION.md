# Epic 0.7.7 — Authoritative Persisted Suspended-Run Cancellation

**Branch:** `epic/0.7.7-suspended-run-cancellation`  
**Status:** ⚪ Planned  
**Dependencies:** 0.7.6 HARD

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

## Tasks

| ID | Candidate | Required result |
|---|---|---|
| 0.7.7a | Suspended-run lifecycle audit | Map checkpoint, approval, resume, recovery and cancellation race paths |
| 0.7.7b | Terminal cancellation contract | Durable terminal state/identity and transition legality |
| 0.7.7c | Authoritative cancel command | Integrate 0.7.6 authorization/control path with persisted lifecycle authority |
| 0.7.7d | Resume fencing | Every future resume/recovery path fails closed after cancellation |
| 0.7.7e | Late-approval fencing | Pending/late approval continuation cannot reactivate cancelled execution |
| 0.7.7f | Atomic race/restart/idempotency | One cancel/resume winner; persistence survives restart; repeated cancel stable |
| 0.7.7g | Evidence/reconstruction | Terminal cancellation remains explainable after cleanup/restart |
| 0.7.7h | Adversarial/mutation/integration proof | Concurrency/restart/late approval/discriminator suite and exact-head gate |

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
