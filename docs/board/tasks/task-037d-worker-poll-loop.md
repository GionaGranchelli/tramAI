# TASK-037D: Worker Poll Loop and Execution Handoff

- Status: planned
- Priority: high
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Parent task: [TASK-037](../tasks/task-037.md)
- Last updated: 2026-05-03

## Purpose

Implement the `TramaiWorker` lifecycle — a background loop that polls the work queue store for due workflows, claims them via lease, and hands off execution to the workflow engine while reporting progress through observer events.

## Scope

- `TramaiWorker` class: constructor takes `WorkQueueStore`, `WorkerRegistry`, `WorkflowEngine`, `WorkflowObserver`, `WorkerConfig`
- `WorkerConfig`: `pollInterval`, `leaseDuration`, `workerId`, `maxClaimedPerPoll`
- Poll loop: on each tick, call `listDue(since = now)` → `claim()` each eligible row → pass claimed workflow runs to the engine
- Starting workflows: if the workflow run has no prior checkpoint, start from the beginning
- Resuming workflows: if a checkpoint exists and the lease is new (recovered), load the checkpoint and resume from the last completed step
- Observer events: emit `onWorkerPollStart`, `onWorkerClaim(workflowRunId)`, `onWorkerStart(workflowRunId)`, `onWorkerComplete(workflowRunId, success)`, `onWorkerPollEnd(claimed, skipped)`
- Error handling: a claim failure (contested) skips to the next workflow; an execution failure updates status to FAILED and releases the lease

## Exit Criteria

- [ ] `TramaiWorker` polls at the configured `pollInterval`
- [ ] Claimed workflows are handed to the engine for execution
- [ ] Checkpointed workflows resume from the last completed step
- [ ] Observer events fire at the correct lifecycle points
- [ ] A contested claim is silently skipped, not retried
- [ ] Execution failure marks the workflow as FAILED and releases the lease
