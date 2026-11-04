# TASK-0.7.7c — E10: recovery re-execution fencing

**Status: inspection complete, crash-window matrix agreed, no production change yet.** E9 (E9-INT-001) landed at
`cd1d8bd8`. D7 physical-dispatch evidence remains a separate track.

## 1. The claim E10 has to earn

Recovering a workflow after an interruption must not bypass its durable execution authority:

1. every recovered external effect takes a **fresh** durable admission before it executes;
2. a **cancelled** workflow cannot dispatch effects during recovery;
3. recovery uses the **original** persisted workflow and governed-run identity;
4. recovery cannot **reuse a stale permit** or **silently repeat** an operation;
5. proven by tests over real recovery orchestration and real checkpoint stores, which **fail** when a guard is removed.

E10 establishes **permission**, not exactly-once execution. A fresh grant says "this attempt is authorized", never "the
previous attempt did not land". Exactly-once needs idempotency or external-effect reconciliation, and neither is claimed
here.

## 2. Recovery path, as it exists

```
CheckpointPoller / WorkerLifecycleController
  └─ WorkflowExecutionSupervisor.supervise(...)
       ├─ lease acquisition (LeaseCoordinator, WorkflowLease: workerId + generation)
       ├─ ExecutionTracker.recoverAttemptIfNeeded(checkpoint) → StepAttemptRecord?
       │    ├─ RETRY_APPROVED      → WorkflowRecoveryCoordinator.consumeRetryApproval(...)
       │    ├─ WORKFLOW_FAILED     → refuses to execute
       │    └─ null (unknown)      → WorkflowRecoveryCoordinator.recoverUnknownAttempt(...)
       │                              └─ WorkflowReplayDecisionPolicy.decide(...)
       │                                   ├─ Replay                     → continue
       │                                   ├─ NON_REPLAYABLE_OUTCOME_UNKNOWN → requireRecovery + throw
       │                                   └─ EXTERNAL_IDEMPOTENCY_KEY_MISSING → requireRecovery + throw
       ├─ persistence = WorkflowPersistence(checkpointStore = LeaseFencedCheckpointStore(delegate))
       └─ typedWorkflow.resume(run = GovernedRun(context, restoredIdentity), observer, persistence)
            └─ WorkflowRunner.resume()
                 ├─ loadResumeCheckpoint() — read-only continuity preconditions
                 ├─ withContext(effectAdmissionContext(session, identity, context))   ← E11/E1–E8 capabilities
                 └─ executeTopLevelSteps(startIndex = checkpoint.nextStepIndex, ...)
                      └─ per step: grant(...) → store.admit(...) → dispatch  →  then saveCheckpoint(...)
```

What already holds, with evidence:

- **Fresh admission per attempt** — `WorkflowRunner.resume` installs `effectAdmissionContext(...)`
  (`WorkflowRunner.kt:436`), and every dispatch site reaches the store through `admitEffect(...)`
  (`WorkflowEffectGrant.kt`). Permits are per-call values consumed once, never persisted, so nothing can be replayed
  from durable state.
- **Cancellation enforced in recovery** — `admitEffect` presents the session's revision to `store.admit(...)`, and the
  store refuses on recorded cancellation; the refusal is an authoritative refusal, not a sanitized failure.
- **Identity continuity** — the supervisor restores `governedRunIdentity` from the durable checkpoint and never
  re-derives it (`WorkflowExecutionSupervisor.kt:233-247`, 0.7.1d).
- **Silent repeat is already refused for unknown outcomes** — the replay-decision policy (#215–#218) splits
  `Replay` from `RequireRecovery`, with `NON_REPLAYABLE_OUTCOME_UNKNOWN` and `EXTERNAL_IDEMPOTENCY_KEY_MISSING` written
  as durable recovery records through the fenced store.

## 3. The gap: the fence does not cover the admission

`LeaseFencedCheckpointStore` (`WorkflowExecutionSupervisor.kt:611`) is
`class LeaseFencedCheckpointStore(...) : WorkflowCheckpointStore by delegate` and overrides exactly three members:
`save`, `delete`, `requireRecovery`.

`admit(...)` (`WorkflowPersistence.kt:184`) and `cancel(...)` (`:176`) are **interface defaults**, so the decorator
forwards them straight to the delegate, unfenced.

Consequence — the takeover window:

1. worker A's lease expires or is superseded; worker B takes over and resumes the run;
2. worker A is still inside a step coroutine and calls `admit` — the delegate is consulted directly, no lease check,
   so A receives a **grant**;
3. A dispatches the external effect;
4. A's `saveCheckpoint` is fenced and refused, and A learns it lost the lease **after** the effect already landed.

Two workers can therefore hold grants for the same run at the same time, and the losing one can still dispatch. That is
"recovery bypasses durable execution authority", the exact thing E10 exists to prevent. `cancel` forwarded unfenced is the
same class in a quieter form: a stale worker can commit a cancellation for a run it no longer owns.

The fix is the same fence the other three members already use — override `admit` and `cancel` to require the current
lease/generation, refusing stale workers before the effect instead of after it. No new abstraction, no new interface.

## 4. Crash-window test matrix

Each row is one test. "Safe" means the effect cannot dispatch.

| # | Window | Required behaviour | Current behaviour | Test |
|---|--------|--------------------|-------------------|------|
| W1 | Crash **after admission, before dispatch** (grant consumed, no effect) | recovery takes a **fresh** admission; the orphaned grant is not reusable | fresh admission ✓, no durable permit to reuse ✓ | kill between `admit` and dispatch, recover, assert a new admission sequence and exactly one dispatch |
| W2 | Crash **after dispatch, before checkpoint** | recovery does **not** silently repeat; unknown outcome requires recovery (`NON_REPLAYABLE_OUTCOME_UNKNOWN`) | policy decides on the durable attempt record ✓ | record an UNKNOWN attempt, recover, assert `requireRecovery` + refusal, zero dispatch |
| W3 | Crash **after dispatch, before checkpoint**, externally idempotent + key present | replay allowed, with a **fresh** admission | `Replay` ✓ | recover, assert one new admission, dispatch happens once more under a new sequence |
| W4 | Crash **after dispatch**, idempotent but **key missing** | refuse: `EXTERNAL_IDEMPOTENCY_KEY_MISSING` | ✓ | recover, assert durable recovery record + zero dispatch |
| W5 | **Lease takeover**: worker B holds the lease, worker A still mid-step | A's admission and cancellation are **refused** | **✗ A's grant is honoured** | hold a lease, expire/supersede it, have A admit and cancel → both refused, zero dispatch, recovery record intact |
| W6 | **Cancellation committed during recovery** | the next recovered effect is refused, run does not dispatch | ✓ via `admit` cancellation check | cancel between steps, resume, assert refusal names the recorded cancellation and dispatch is zero |
| W7 | **Identity continuity** | recovery resumes under the persisted governed identity; a substituted identity is refused | ✓ restored from checkpoint | resume with a mismatched identity, assert refusal before any dispatch |
| W8 | **Stale permit reuse** (hand-crafted attempt to present an old admission) | no durable permit exists to present; the only path is a fresh conditional `admit` | ✓ permits are per-call values | assert no API accepts a persisted permit; a repeated resume yields a new sequence number |

W5 fails today. W1–W4 and W6–W8 are expected to pass and exist to keep the claim honest — a matrix that only shows the
one broken row would not prove the other four properties survived the fix.

## 5. Mutation checks (each must fail the suite)

- M1: fence `admit` only when a lease is present — stale workers admit again.
- M2: fence `cancel` but not `admit`.
- M3: drop the `Replay`/`RequireRecovery` decision so unknown outcomes replay.
- M4: refuse on unknown only when idempotent (invert the policy).
- M5: restore the identity from the tracker instead of the checkpoint.
- M6: skip the cancellation check in `admitEffect`.

## 6. Out of scope

- **D7** per-effect physical-dispatch evidence: admission is not dispatch.
- Exactly-once execution, external-effect reconciliation, and idempotency enforcement beyond the existing replay
  decision.
- The deferred hardening item from E9: fail-fast composition validation that `approvalResumeAdmission` and
  `approvalRecoveryCoordinator` are wired together.
