# Workflow Delivery Semantics

TramAI provides **at-least-once step execution** for replay-safe steps, **fenced checkpoint advancement**, and does **not** guarantee exactly-once execution of external side effects.

This document defines the execution contract, the failure recovery model, and what application code must provide to use it safely.

## Execution Contract

| Property | Guarantee | Notes |
|----------|-----------|-------|
| Step execution | At least once | A step may execute more than once if the worker crashes after execution but before checkpoint persistence. |
| Checkpoint advancement | Exactly once per persisted checkpoint | The checkpoint revision acts as an optimistic lock — stale writes are rejected. |
| External side effects | No guarantee | A side effect (API call, file write, email send) may occur zero, one, or multiple times depending on crash timing. |
| Idempotency key stability | Guaranteed when provided | An EXTERNALLY_IDEMPOTENT step that recorded an idempotency key is never re-executed with a different key — a mismatch enters recovery-required state instead. |

## Checkpoint Lifecycle

```
     ┌──────────┐
     │  Normal  │ (default — worker executes normally)
     └────┬─────┘
          │
          │ worker encounters UNKNOWN attempt on NON_REPLAYABLE or
          │ EXTERNALLY_IDEMPOTENT (without key) step
          ▼
     ┌──────────┐
     │ Required │ (worker skips, operator must resolve)
     └────┬─────┘
          │
     ┌────┴────┐
     │        │
     ▼        ▼
  retry    fail
  (worker  (checkpoint
  retries) deleted)
```

### Normal State

The default state. Workers poll the checkpoint, claim a lease, and execute steps normally. Each step advances `nextStepIndex` and increments `revision`.

### Required State

Entered when a worker detects an unrecoverable previous attempt:

- **NON_REPLAYABLE** step completed with `UNKNOWN` status — the worker cannot safely retry because the external outcome is unknown.
- **EXTERNALLY_IDEMPOTENT** step without a recorded idempotency key — the worker cannot safely retry because replay might duplicate the external side effect.

Workers skip checkpoints in `Required` state — no lease is claimed, no execution starts.

### Resolution (operator action)

An operator resolves `Required` state through `WorkflowRecoveryController`:

- `retryStep(workflowName, workflowId, expectedRevision, reason)` — marks the exact unresolved attempt `FAILED` with the operator's resolution evidence, then clears the recovery state. The worker starts a NEW attempt on the next poll; the failed attempt remains in the attempt store as audit evidence.
  - **Supported only for `NON_REPLAYABLE_OUTCOME_UNKNOWN`.** Retrying `EXTERNAL_IDEMPOTENCY_KEY_MISSING` or `IDEMPOTENCY_KEY_MISMATCH` is rejected (`WorkflowRecoveryStateException`): the worker's idempotency-key guard only inspects UNKNOWN/STARTED attempts, so an unconditional retry would re-execute the step with a different or missing key — breaking the idempotency contract. The correct resolution for those reasons is a corrected workflow definition (re-submit) or `failWorkflow`.
  - The attempt transition is **mandatory** — persistence failures propagate and the checkpoint stays `Required`.
- `failWorkflow(workflowName, workflowId, expectedRevision, reason)` — permanently deletes the checkpoint in a single fenced operation. If the delete fails, the checkpoint remains in `Required` state and the workflow stays blocked — there is no window where a failed workflow becomes runnable. When a step-attempt store is available, the resolution reason and timestamp are recorded onto the exact failed attempt record as **best-effort** audit evidence (storage errors are logged, never propagated).

Both operations load and validate the checkpoint first — they throw `WorkflowCheckpointConflictException` on a stale revision and `WorkflowRecoveryStateException` when the checkpoint is not in `Required` state or the recovery reason is not retryable, so a normal (runnable) workflow can never be accidentally advanced or deleted.

## Recovery Record

When a checkpoint enters `Required` state, the following information is persisted:

| Field | Description |
|-------|-------------|
| `reason` | Machine-readable cause: `NON_REPLAYABLE_OUTCOME_UNKNOWN`, `EXTERNAL_IDEMPOTENCY_KEY_MISSING`, or `IDEMPOTENCY_KEY_MISMATCH` |
| `stepName` | The step that failed |
| `attemptId` | The unresolved attempt identifier |
| `priorWorkerId` | The worker that started the failed attempt |
| `detectedAtEpochMillis` | When recovery was triggered |
| `idempotencyKey` | The key recorded by the previous attempt, retained for stability verification during replay |
| `instructions` | Optional operator guidance |

## Idempotency Keys

An `EXTERNALLY_IDEMPOTENT` step must provide an idempotency key at execution time. The key is:

1. **Recorded** in `StepAttemptRecord.idempotencyKey` on the first attempt.
2. **Verified** on any retry — the worker recomputes the key from the current workflow definition and requires it to match the recorded key. If the recomputed key differs (e.g. after a deployment or definition change), the checkpoint enters `Required` state with reason `IDEMPOTENCY_KEY_MISMATCH`; the step is never re-executed with a different key.
3. **Applied** to the external call by the step implementation. TramAI verifies key stability but the application code remains responsible for using the recorded key when calling the external system.

If no key was recorded (e.g., an older worker version without key generation), the checkpoint enters `Required` state with reason `EXTERNAL_IDEMPOTENCY_KEY_MISSING`. The operator must verify the external system state before deciding to retry or fail.

## Worker Shutdown and Abandonment

When a worker shuts down:

1. New polling stops immediately.
2. Active executions enter a drain period (`drainTimeoutMillis`).
3. If an execution does not complete within the drain window, its coroutine is cancelled.
4. The worker releases its leases and records `CancellationException` as the attempt status.
5. If a cancelled step was NON_REPLAYABLE and left in UNKNOWN status, the checkpoint enters `Required` state on the next worker poll.

## Persistence Store Contract

All recovery state transitions are fenced by the checkpoint revision:

- `requireRecovery(name, id, expectedRevision, record)` — atomically persists the recovery record. Throws `WorkflowCheckpointConflictException` on revision mismatch.
- `clearRecovery(name, id, expectedRevision)` — atomically reverts to Normal. Throws on revision mismatch.

Default implementations use `load` + `save` under the store's existing optimistic concurrency. JDBC stores should override with a transactional implementation.

## Audit Trail

Step attempt records persist independently of checkpoint state. After `failWorkflow` deletes the checkpoint, the attempt records remain queryable through `StepAttemptRecordStore.listStepAttempts(runId)`. The resolution evidence (`resolutionReason`/`resolutionAtEpochMillis`, and `FAILED` status) is written to the exact attempt referenced by the recovery record when a step-attempt store is supplied:

- `retryStep` — the `FAILED` transition is **mandatory**: failures propagate and the checkpoint stays `Required`.
- `failWorkflow` — the evidence write is **best-effort** (storage errors are logged, not propagated), so treat it as audit *aid*, not a durability guarantee.

## Configuration Properties

Recovery behaviour is not configurable in this release. Future releases may add:

- Auto-recovery timeout (transition Required → Normal after N minutes)
- Max retry count per recovery record
- Recovery webhook / notification channel
