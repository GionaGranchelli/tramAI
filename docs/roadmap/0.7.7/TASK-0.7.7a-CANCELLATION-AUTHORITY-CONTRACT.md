# TASK-0.7.7a — Authoritative cancellation & resume fencing: contract and threat model

Status: **ratified; slice (a) implemented, slices (b)-(d) held.** D1-D5 frozen by owner ruling (review
5727941344) and recorded in §10. Slice (a) — durable lifecycle state, generation-fenced CAS cancellation,
migration, retention, store-level adversarial tests — is implemented in this PR. fences B1-B5 are held until
the execution-admission race has the contract in §11.
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

### 3.1 ABA protection: the generation is part of the precondition

A revision alone is not enough. A run's identity can be re-established with a rotated
`checkpointGeneration`, so a privileged command holding only a matching revision could adopt a generation it
never observed and cancel a run it does not describe. `WorkflowCancellationRequest` therefore carries
`expectedRevision` **and** `expectedGeneration`, and the store's conditional write matches both
(`AND checkpoint_generation = ?`, or `IS NULL` for legacy rows that predate the column). A generation
mismatch is a conflict and records nothing.

Idempotency is defined so it cannot smuggle in a second linearization point: once a cancellation is
committed, a repeated command returns the recorded state **without advancing the revision**, including a
retry that presents the now-current revision. There is exactly one commit, and the returned
`WorkflowLifecycleState.revision` identifies it.

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

## 8b. Slice (a) as implemented

| Element | Where | Behaviour |
| --- | --- | --- |
| `WorkflowLifecycleStatus` | `WorkflowLifecycle.kt` | `CANCELLED`, `CANCELLED_UNCERTAIN` (wire names persisted) |
| `WorkflowLifecycleState` | same | status, revision, generation, timestamp, actor, reason |
| `WorkflowCancellationRequest` | same | identity + expected revision **and** generation + actor + reason + uncertain |
| `lifecycle()` / `cancel()` | `WorkflowCheckpointStore` | default **refuses** with `WorkflowCancellationUnsupportedException` |
| In-memory store | `WorkflowPersistence.kt` | full implementation; cancel bumps the row revision; cancelled rows refuse deletion |
| JDBC store | `JdbcWorkflowCheckpointStore` | four nullable columns, one conditional `UPDATE … WHERE revision AND generation AND lifecycle_status IS NULL`; `lifecycleMigrationSql()` adds the columns |
| Markdown / file stores | unchanged | inherit the refusal: they cannot answer atomically, so they do not answer at all |

Store-level tests: `WorkflowCancellationAuthorityTest` (13 cases) covers commit-at-next-revision, one
linearization point under repeat and under a retry against the committed revision, stale revision, stale
generation, uncertain-not-clean, retention (both stores), unknown run, unsupported profile refusal, durability
across a new store instance, and the pre-migration upgrade path.

Mutations, each of which must fail a named test: ignoring the generation in the in-memory cancel; removing
`lifecycle_status IS NULL` from the JDBC cancel; removing the in-memory retention refusal; removing the JDBC
retention refusal; recording an uncertain cancellation as a clean one. Five of five killed — the JDBC
`lifecycle_status` guard needed a test that retries **against the committed revision** before it was
distinguishable from an equivalent mutant, which is why that case exists.

## 8a-bis. Terminality of ordinary writes

Terminal means terminal for the *row*, not just for the lifecycle column. Slice (a) refused deletion of a
cancelled checkpoint and slice (b) refused admission, but an ordinary checkpoint write could still overwrite a
cancelled run: the writer holds the revision the cancellation left behind, so the CAS precondition passes and
nothing else said no.

Both write paths now carry the guard **in the statement**:

| Path | Guard |
| --- | --- |
| `UPDATE` (checkpoint save with an expected revision) | `AND lifecycle_status IS NULL` |
| `DELETE` | `AND lifecycle_status IS NULL` |

The earlier delete implementation asked a probe question first and deleted second; a cancellation committing
between the two would have found the evidence removed. There is no probe now: the same statement decides
whether the row is still live, so a delete either removes a live row or removes nothing, and a zero-row delete
of a row that still exists is reported as a conflict rather than as a silent success.

Consequence an operator must plan for:

- **The migration is a precondition for writes, not only for cancellation.** A table without the lifecycle
  columns rejects the guarded `UPDATE`/`DELETE`, and the store reports a persistence failure instead of
  deleting blind. Reads (`load`, `list`) do not reference the columns and are unaffected, and a first `save`
  inserts a fresh row, which needs none of them. So an un-migrated deployment keeps reading and keeps starting
  new runs, while completing or updating an existing run fails loudly until the migration runs.

Two further properties of slice (a) that an operator must know:

- **The lifecycle read fails closed.** On a table without the lifecycle columns it reports a read failure; it
  never reports "not cancelled" from a column that is not there.
- **`uncertain` was caller-supplied in slice (a).** Slice (b) closes that: the store now derives it from
  admission state, so a run that admitted work cannot be recorded as a clean cancellation even if the caller
  asks for one. What remains outside this slice is that *nothing calls `admit()` yet* — see §8c.

## 8c. Slice (b) as implemented: execution admission

Cancellation decides whether a run *may* continue; it cannot stop a call that starts a moment later. §11 defines
the boundary; this slice implements it as a store-level act.

| Element | Where | Behaviour |
| --- | --- | --- |
| `WorkflowAdmissionRequest` | `WorkflowLifecycle.kt` | run identity + expected revision **and** generation |
| `WorkflowAdmission` | same | `admitted`, `sequence`, and the cancellation that refused it (mutually exclusive, enforced in `init`) |
| `admit()` | `WorkflowCheckpointStore` | default **refuses**: a profile that cannot decide admission with a conditional write must not admit work |
| In-memory store | `WorkflowPersistence.kt` | synchronized count-and-check; cancelled runs refuse; the count survives across calls |
| JDBC store | `JdbcWorkflowCheckpointStore` | one conditional `UPDATE … SET admission_seq = admission_seq + 1 WHERE key AND revision AND generation AND lifecycle_status IS NULL`; `admission_seq BIGINT NOT NULL DEFAULT 0`; `lifecycleMigrationSql()` adds it |

Two properties make the ordering meaningful:

- **Admission is ordered against cancellation by the same row.** Cancellation's conditional write carries
  `lifecycle_status IS NULL`, and admission's carries it too. Whichever commits first wins; the loser sees the
  winner's state. Neither is a read followed by a write.
- **Cancellation's outcome is derived, not claimed.** The cancel `UPDATE` sets
  `lifecycle_status = CASE WHEN admission_seq > 0 THEN 'cancelled_uncertain' ELSE ? END`, so the uncertain verdict
  is computed by the same statement that commits the cancellation. No reader can observe "admitted but cleanly
  cancelled", because the two cannot be written by different statements.

Admission deliberately does **not** move the checkpoint revision: it is not a checkpoint write, and advancing the
revision would invalidate a concurrent saver's CAS for a state change that did not touch checkpoint content.

Store-level tests: `WorkflowAdmissionFenceTest` (8 cases) — admitted-then-cancelled is uncertain;
cancelled-then-admitted is refused with the recorded state; no admission cancels cleanly; admission is generation
fenced; admissions count up without moving the revision; the in-memory store derives the same verdicts; admission
fails closed on a pre-migration schema; a store that cannot fence refuses to admit.

Mutations, each of which must fail a named test: dropping `lifecycle_status IS NULL` from the admission write;
removing the `CASE WHEN admission_seq > 0` derivation from cancellation; ignoring the generation in the admission
precondition.

**What slice (b) does not do: nothing calls `admit()` yet.** The runner still dispatches model and tool calls
without asking the authority, so the invariant `cancelled(run) ⇒ no future authoritative execution(run)` is
*decidable* but not yet *enforced*. That wiring is B1-B5 and remains held, because it has to place the admission
call on the exact edges where a call leaves the process — and each of those edges needs its own test.

## 9. What this contract does not claim

- It does not claim cancellation is currently safe. §2 shows it is not: the only `CANCELLED` state is
  in-memory.
- It does not claim the fence prevents in-flight effects (§5).
- It does not claim that a cancelled run is *unreachable* by writers holding the current revision unless the
  guard is in the statement: that is why the update and delete paths carry it rather than re-reading the
  lifecycle state first.
- It does not claim that cancellation currently stops execution: the admission protocol exists and is tested at
  store level (§8c), but the runner does not call it yet, so no execution path is fenced in this slice.
- It does not claim the file/in-memory profiles give the same guarantee as JDBC: the file store is
  single-node, and "single-node" is a deployment assumption, not an invariant. Any claim must name the
  profile.
- It does not claim `0.7.7a` needs the dashboard, a second scheduler, retries, or a new policy evaluator.

---

## 11. Execution admission: the race the fence cannot read

The Socratic case from the owner review is the reason B1-B5 are held:

```text
worker reads lifecycle: ACTIVE
cancellation commits
worker dispatches model/tool call       <- a read-then-act gap; no read can close it
```

A read-check before each call is not a linearizable admission protocol. The contract therefore defines
admission as an **act**, not an observation:

- **ADMITTED** — a conditional write on the run's own lifecycle row that (a) presents the revision and
  generation the worker last observed, and (b) is guarded by `lifecycle_status IS NULL`. Because the
  cancellation commit is a conditional write on the *same* row with the *same* guard, admission and
  cancellation are totally ordered by the row: exactly one of them can present the revision that wins, and
  after a cancellation commits, no admission write can succeed at all.
- **DISPATCHED** — the in-process act of starting the call. Unobservable to the store.
- **LANDED** — the external effect. Knowable only if the tool reports it.

The guarantee is stated over ADMITTED, and only over ADMITTED:

> If a cancellation commits before call *k* is admitted, call *k* is never dispatched. If call *k* is admitted
> before the cancellation commits, the run terminates `CANCELLED_UNCERTAIN` — admission proves permission, never
> absence of effect.

Consequences that make this testable rather than aspirational:

- The admission write reuses the path that already exists (`LeaseFencedCheckpointStore`): no new mechanism, no
  second lifecycle authority, no per-call polling loop.
- The row needs an admission counter (a monotonically increasing `admission_seq`, plus the outstanding-attempt
  marker) so the cancellation commit can **derive** `CANCELLED` vs `CANCELLED_UNCERTAIN` instead of trusting a
  caller boolean. This is the slice (b) schema addition; it is deliberately not in slice (a).
- The physical gap between DISPATCHED and LANDED is not closed by any durable state, in this design or any
  other. Claims must stop at ADMITTED, and the evidence must say so.

Slice (b) is therefore gated on: the `admission_seq` column, the admittance write, derived uncertainty, and the
tests T1-T8 re-expressed against admission rather than against a read.

---

## 10. Owner decisions (ratified)

D1-D5 as ruled: dedicated lifecycle column; L-B (CAS without lease); strictly terminal; first-class
`CANCELLED_UNCERTAIN`; JDBC authoritative with the file profile failing closed. The table below is the record
of what was asked, not an open question.

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
