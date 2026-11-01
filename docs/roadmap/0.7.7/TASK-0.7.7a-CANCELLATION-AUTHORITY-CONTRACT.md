# TASK-0.7.7a — Authoritative cancellation & resume fencing: contract and threat model

Status: **contract only. No implementation.** The authority analysis below concludes that no existing store
can establish run-level cancellation authority atomically, so §7 states the required persistence change
instead of improvising a second lifecycle authority.
Base: `release/0.7.0` at `8de4f9d6`.
Roadmap: `docs/ROADMAP-0.7.0-RELEASE-CUT.md` §P0.9 (runtime control), P0.12 bypass list.
Owner decisions required: §10.

---

## 1. The invariant

```text
cancelled(run) => no subsequent authoritative execution(run)
```

Four terms, defined so the statement is testable:

- **run** — one `(workflowName, workflowId)` pair. Not a step, not an attempt, not a tool call.
- **cancelled** — a durable, run-scoped state that a privileged command establishes once and that cannot
  be overwritten by any later command except an explicit, separately authorized reinstatement (out of
  scope here; this contract makes cancellation terminal).
- **authoritative execution** — any of: a model invocation, a tool invocation (read-only or not), an
  approval continuation execution, a delayed wakeup resume, or a workflow step body, attributable to the
  run, that can produce an externally observable effect.
- **subsequent** — starting strictly after the linearization point (§3). Execution that *began* before it
  is governed by §5, not by the invariant.

The invariant is deliberately not "cancel stops the coroutine". A coroutine kill is a local scheduler
fact; the invariant is a statement about durable state and about every execution boundary in every
process, including a process that has never seen the cancel command.

---

## 2. What exists today, with evidence

Cancellation today (`WorkflowController.kt:180-188` → `WorkflowRunStore.cancel`, `WorkflowRunStore.kt:220-253`)
does three things: takes the in-process `Job`, appends two in-memory status events (`CANCELLING`,
`CANCELLED`), and calls `executionJob?.cancel()`.

| Component | Durable? | Atomic guard | Run-level cancel state | Verdict |
| --- | --- | --- | --- | --- |
| `WorkflowRunStore` (`tramai-server`, `:63-65`, `:460-468`) | **No** — `linkedMapOf` + `synchronized`, evicts beyond `maxHistorySize` | none (in-process monitor) | `CANCELLED` (`:15-23`) | **cannot be the authority** |
| `WorkflowCheckpointStore` (`JDBC`/file/in-memory) | Yes | `expectedRevision` + `checkpointGeneration` CAS (`WorkflowPersistence.kt:79-93`) | **none** — `WorkflowRecoveryState` is `Normal` \| `Required` only (`:37-40`) | authority-ready, missing the state |
| `WorkflowLeaseStore` + fence (`JdbcWorkflowLeaseStore.kt:158-172`) | Yes | one transaction: `lockLeaseRow` then checkpoint DML on the same connection | n/a | **real fence already exists** |
| `ApprovalContinuationStore` | Yes | versioned, digest-bound (0.7.6 track) | `CANCELLED`, `CANCELLED_UNCERTAIN` — **per continuation** | per-continuation, and it must never *authorize* a run |
| `SuspendedInvocationStore` (`JdbcSuspendedInvocationStore`, `remove(approvalId)`) | Yes | — | none; **delete on resume** | evidence destroyed, not a lifecycle |
| `ApprovalResumeCoordinator` (`:122`) | n/a | checks only `continuation.status == COMPLETED` | none | resume boundary has **no run-level fence** |

Three consequences, each independently sufficient to fail the invariant:

1. **`CANCELLED` lives only in memory.** A restart, a second node, or 1000 later runs make the run
   indistinguishable from a suspended one. A cancelled run *can* be resumed by a process that did not hold
   the cancel.
2. **The durable row is unchanged by cancellation.** `WorkflowRunner.kt:190-194` aborts the session on
   `CancellationException`; `WorkflowPersistenceSession.abort()` (`:106-108`) releases the lease and keeps
   the checkpoint. Retaining the row is correct — but nothing on that row records *why*, so
   `WorkflowRunStore.requireResumable` (`:255-267`) would accept it after a restart.
3. **The approval path has no run-level check.** A continuation cancelled at its own level protects that
   continuation; it does not protect a run whose approval arrives after cancellation, because no durable
   run state exists to consult.

**Finding: no current store can establish cancellation authority atomically.** The checkpoint row is the only
candidate (it already has CAS and an atomic lease fence), and it has nowhere to put the decision.

---

## 3. The linearization point

The authority is one write, and the contract is only meaningful if that write is single and total:

> **LP:** the commit of the conditional write that records terminal cancellation on the run's durable
> lifecycle row, at the next revision, when every precondition it presents (revision, generation) is still
> current.

Two mechanisms can produce that write. They are not equivalent:

**L-A — the canceller acquires or steals the lease, then writes through the fence.**
Reuses `saveCheckpointIfLeaseOwner`. Cost: a live worker holds an unexpired lease, so cancellation requires
a *forcible lease invalidation*, which is a privileged primitive with its own blast radius (it also revokes
the right to persist ordinary progress). It answers "who may write" rather than "what is true".

**L-B — the canceller writes the lifecycle state by CAS on the existing row, without taking the lease.**
The running worker's next fenced write already fails on either the rotated generation or a revision
mismatch. No lease stealing, one authority, no new control path.

**Recommendation: L-B.** The reason is not simplicity: L-A makes the fence authorize the *writer*, while the
invariant is about the *state of the run*. A lease-holding worker that has already lost the authority race
would still be authorized by L-A until its lease is invalidated, and invalidating the lease is precisely the
write that must be atomic with the state change.

Under L-B the race resolves to exactly two outcomes, and both are safe:

- **Cancel wins the CAS** (revision → N+1, generation rotated). A worker's pending save at N throws
  `WorkflowCheckpointConflictException`; the worker must treat that as *stop*, not as retryable contention.
  Nonexistence of a retry path here is a hard requirement, not a preference: a retrying worker could win the
  next revision and resume execution after the cancel.
- **The worker wins the CAS.** The cancel's precondition is stale; it reloads and re-issues against N+1. The
  invariant holds as long as the cancel commits before the *next* side-effecting boundary — which is why
  §4's fence points bound the retry loop rather than allowing it to run unbounded.

---

## 4. Fencing points

Fencing is not one check; it is a property of every boundary that can start an authoritative execution. Each
of these must **deny** (not warn, not log-and-continue) when the durable lifecycle state is `CANCELLED`, and
must deny when the row is *absent* but a checkpoint is expected (a deleted row is not evidence of legality):

| # | Boundary | Today | Required |
| --- | --- | --- | --- |
| B1 | Resume admission (`WorkflowController.resume` → `requireResumable`) | in-memory status only | consult the durable row |
| B2 | Execution start after lease claim (`executeClaimedWorkflow`, `WorkflowExecutionSupervisor.kt:127-170`) | no check | load row post-claim, refuse before the first step |
| B3 | Every checkpoint write (`LeaseFencedCheckpointStore`, `:611`; fence `:630`, `:672`) | generation/revision only | also refuse when the row is cancelled |
| B4 | Step boundary — before step *k* executes | no check | re-verify per step; a cancel that commits mid-run must stop the next step |
| B5 | Approval continuation execution (`ApprovalResumeCoordinator.executeClaimedResume`) | continuation status only | refuse when the run is cancelled — this is the late-approval case |
| B6 | Delayed wakeup resume (`WorkflowDelayWakeupScheduler` → resume) | no check | routed through B1/B2, never straight into execution |
| B7 | Reconstruction/replay (`ReplayAuthorizationService`, `TramaiEngine`) | no side effects today | must refuse to *resume*; replay must remain side-effect free |

B3 is the load-bearing one: it is already the only path that writes durable progress, and it already fails
closed on a stale fence. B1/B2/B5 are where a cancelled run can currently re-enter.

---

## 5. External side effects: what cannot be guaranteed

Cancellation cannot undo an irreversible tool call. This is a property of the world, not a limitation to be
fixed later, and the contract must say so in the same place it states the invariant:

- **Guaranteed.** No authoritative execution *begins* after the fence is observed at a checked boundary. In
  particular, no model call, no tool call, and no approval continuation starts for a cancelled run.
- **Guaranteed.** Every boundary that observes cancellation refuses, and the refusal is durable and
  attributable (`actor`, `reason`, `cancelledAt`, resulting revision).
- **Not guaranteed.** Completion of a call already dispatched before LP. The fence is a gate, not a brake.
- **Not guaranteed.** That an in-flight effect has not happened. Only that it is *recorded as uncertain* —
  the semantics the continuation store already carries as `CANCELLED_UNCERTAIN`. Reuse that vocabulary
  rather than inventing a second one.
- **Not guaranteed.** Anything about a non-idempotent external system's own state. Idempotency keys make
  retries safe; they say nothing about cancellation.
- **Bounded, not solved.** In-flight calls are bounded by attempt and lease timeouts. Timeout is not
  cancellation, and the contract must not present it as one.

Consequence for implementation: a run cancelled while an attempt is in flight must terminate in a state that
distinguishes "cancelled, clean" from "cancelled, effect may have landed". Collapsing those into one value
would manufacture exactly the false assurance this epic exists to remove.

---

## 6. One authority, explicitly

- The **run-lifecycle row** on the durable checkpoint store is the sole authority for "is this run
  cancelled".
- `WorkflowRunStore.CANCELLED` becomes a **projection/cache** of that row, for the API and SSE stream. It
  may be stale; it may never be the deciding fact.
- `ApprovalContinuationStore.CANCELLED` remains a statement about a continuation. A continuation may read the
  run lifecycle row; it may never authorize execution when the run says cancelled.
- No dashboard, application, or adapter emulates cancellation through local flags, checkpoint deletion, or
  approval-state mutation (roadmap P0.9, quoted directly).

---

## 7. Required persistence change

Small, and named precisely so it is reviewable before implementation:

1. **A run lifecycle state on the durable row** — extend the checkpoint record with terminal cancellation:
   `CANCELLED` (clean) and `CANCELLED_UNCERTAIN` (attempt in flight when the cancel committed), plus
   `cancelledAtEpochMillis`, `cancelledBy`, `cancellationReason`, and the resulting `revision`.
   **Owner decision D1:** a dedicated nullable lifecycle column, or widening `recoveryState`. Recommend the
   dedicated column: `recoveryState` means "an operator must resolve this before progress", which is the
   opposite of "this run is dead", and merging them makes a resolved recovery indistinguishable from a
   cancellation.
2. **A conditional write** `cancel(workflowName, workflowId, expectedRevision, actor, reason)` with store-level
   CAS, implemented for JDBC (`UPDATE … WHERE revision = ? AND checkpoint_generation = ?`), the file store, and
   in-memory for tests. Return type carries the new revision so the caller can prove the LP.
3. **A read used by every fence** — `loadLifecycle(workflowName, workflowId)`, cheap, no state payload decode.
4. **Retention.** A cancelled run's row is never deleted: `deleteCheckpointOnCompletion`
   (`WorkflowPersistence.kt:203`) must not apply to `CANCELLED`, and `SuspendedInvocationStore.remove` must
   become a status transition so the approval-side evidence survives too.
5. **Migration.** Append-only `api-migrations.yml` entry, new columns nullable, backfill absent → "not
   cancelled". Absence is fail-open *only* for runs that never had a row; a missing row where a checkpoint is
   expected is a refusal, not a permission.
6. **Conflict semantics.** `WorkflowCheckpointConflictException` raised against a cancelled row must be
   terminal for that worker — a compile-time or test-enforced prohibition on retry inside retry loops.

Nothing here introduces a second policy engine, a new scheduler, or a parallel lifecycle service. It adds a
state to the store that already holds the run, and a conditional write to the store that already has CAS.

---

## 8. Adversarial characterization (tests, not yet written)

Each row is a test with a named expected outcome; "no exception" is not a passing outcome.

| # | Scenario | Expected |
| --- | --- | --- |
| T1 | cancel while `WAITING_FOR_GATE`, then approve | approval recorded, **no execution**, run stays cancelled |
| T2 | cancel while `DELAYED`, then wakeup timer fires | resume refused, no model/tool call |
| T3 | cancel and resume issued concurrently | exactly one winner; if resume wins it is ordered before LP and the run is cancelled after; never both |
| T4 | two concurrent cancels | idempotent: one LP, one row state, two audited attempts |
| T5 | cancel, then restart the process, then resume | refused — the durable row decides, not memory |
| T6 | cancel, then a stale worker's next checkpoint write | conflict, worker stops, no retry, run still cancelled |
| T7 | cancel commits between two steps | step *k+1* never starts; step *k*'s completed checkpoint retained |
| T8 | cancel while a tool call is in flight | `CANCELLED_UNCERTAIN`, effect recorded as unknown, no new call |
| T9 | cancel with a forced CAS conflict (worker wins) | cancel retries once against N+1 and commits before the next boundary |
| T10 | checkpoint row deleted while cancelled | refuse to resume (deletion is not permission) |
| T11 | reconstruct the cancelled run | timeline and evidence render; no provider/tool/network call |
| T12 | cancel on an already-`COMPLETED` run | conflict, terminal state unchanged (no resurrection) |

Mutation requirement: each of T1–T6, T8 and T10 must be shown to fail when its fence is removed — forced
execution (`--rerun-tasks`), because a fence in a non-Gradle-input path can otherwise "survive" a mutation
campaign by never running (recorded during 0.7.6g1).

---

## 9. What this contract does not claim

- It does not claim cancellation is currently safe. §2 shows it is not: the only `CANCELLED` state is
  in-memory.
- It does not claim the fence prevents in-flight effects (§5).
- It does not claim the file/in-memory profiles give the same guarantee as JDBC: the file store is
  single-node, and "single-node" is a deployment assumption, not an invariant. Any claim must name the
  profile.
- It does not claim `0.7.7a` needs the dashboard, a second scheduler, retries, or a new policy evaluator.

---

## 10. Owner decisions required

**D1 — Cancellation state location.** Dedicated lifecycle column (recommended) vs widening
`WorkflowRecoveryState`.

**D2 — Canceller authority model.** L-B (CAS without lease, recommended) vs L-A (lease acquisition/steal).
The choice determines whether forcible lease invalidation is a supported privileged action.

**D3 — Terminality.** Is cancellation strictly terminal for a run (recommended: yes; reinstatement is a new
run with new attribution), or must a reinstatement path exist in 0.7.7?

**D4 — `CANCELLED_UNCERTAIN`.** Adopt it as a first-class run outcome (recommended, reusing the continuation
vocabulary) or terminate every cancellation as `CANCELLED` and record uncertainty only in evidence?

**D5 — Profile scope.** Does 0.7.7 claim the invariant for the JDBC profile only, or must the file profile
carry it too (single-node, no atomic cross-process fence candidate today)?

On approval of D1–D2 the implementation slices are: (a) store state + CAS + migration, (b) fence points
B1–B5 with T1–T7, (c) `CANCELLED_UNCERTAIN` + evidence retention with T8–T11, (d) reconstruction and
terminality with T12.
