# Workflow Delivery Semantics

TramAI provides **at-least-once step execution** for replay-safe steps, **fenced checkpoint advancement**, and does **not** guarantee exactly-once execution of external side effects.

This document defines the execution contract, the failure recovery model, and what application code must provide to use it safely.

## Execution Contract

| Property | Guarantee | Notes |
|----------|-----------|-------|
| Step execution | At least once | A step may execute more than once if the worker crashes after execution but before checkpoint persistence. |
| Checkpoint advancement | Exactly once per persisted checkpoint | The checkpoint revision acts as an optimistic lock — stale writes are rejected. |
| External side effects | No guarantee | A side effect (API call, file write, email send) may occur zero, one, or multiple times depending on crash timing. |
| Idempotency key reuse | Guaranteed when provided | An EXTERNALLY_IDEMPOTENT step that recorded an idempotency key will replay with the same key. |

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

- `retryStep(workflowName, workflowId, expectedRevision, reason)` — clears the recovery state and lets the worker retry the unresolved step. The original unknown attempt record remains as audit evidence. The stored idempotency key is reused.
- `failWorkflow(workflowName, workflowId, expectedRevision, reason)` — clears the recovery state and permanently deletes the checkpoint. Step attempt records remain as audit evidence.

Both operations use optimistic concurrency — the `expectedRevision` must match the current checkpoint revision or the operation throws `WorkflowCheckpointConflictException`.

## Recovery Record

When a checkpoint enters `Required` state, the following information is persisted:

| Field | Description |
|-------|-------------|
| `reason` | Machine-readable cause: `NON_REPLAYABLE_OUTCOME_UNKNOWN` or `EXTERNAL_IDEMPOTENCY_KEY_MISSING` |
| `stepName` | The step that failed |
| `attemptId` | The unresolved attempt identifier |
| `priorWorkerId` | The worker that started the failed attempt |
| `detectedAtEpochMillis` | When recovery was triggered |
| `idempotencyKey` | The stored idempotency key (if any) for reuse on retry |
| `instructions` | Optional operator guidance |

## Idempotency Keys

An `EXTERNALLY_IDEMPOTENT` step must provide an idempotency key at execution time. The key is:

1. **Recorded** in `StepAttemptRecord.idempotencyKey` on the first attempt.
2. **Reused** on any retry — the worker passes the same key to the step implementation.

If no key was recorded (e.g., an older worker version without key generation), the checkpoint enters `Required` state. The operator must verify the external system state before deciding to retry or fail.

## Worker Shutdown and Abandonment

When a worker shuts down:

1. New polling stops immediately.
2. Active executions enter a drain period (`drainTimeoutMillis`).
3. If an execution does not complete within the drain window, its coroutine is cancelled.
4. The worker release its leases and records `CancellationException` as the attempt status.
5. If a cancelled step was NON_REPLAYABLE and left in UNKNOWN status, the checkpoint enters `Required` state on the next worker poll.

## Persistence Store Contract

All recovery state transitions are fenced by the checkpoint revision:

- `requireRecovery(name, id, expectedRevision, record)` — atomically persists the recovery record. Throws `WorkflowCheckpointConflictException` on revision mismatch.
- `clearRecovery(name, id, expectedRevision)` — atomically reverts to Normal. Throws on revision mismatch.

Default implementations use `load` + `save` under the store's existing optimistic concurrency. JDBC stores should override with a transactional implementation.

## Audit Trail

Step attempt records persist independently of checkpoint state. After `failWorkflow` deletes the checkpoint, the attempt records remain queryable through `StepAttemptRecordStore.listStepAttempts(runId)`.

## Configuration Properties

Recovery behaviour is not configurable in this release. Future releases may add:

- Auto-recovery timeout (transition Required → Normal after N minutes)
- Max retry count per recovery record
- Recovery webhook / notification channel
