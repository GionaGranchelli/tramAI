# TASK-037F: Graceful Shutdown and Cancellation

- Status: planned
- Priority: high
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Parent task: [TASK-037](../tasks/task-037.md)
- Last updated: 2026-05-03

## Purpose

Ensure workers shut down cleanly on SIGTERM or application-requested stop by halting the poll loop, draining in-progress steps to a checkpoint, releasing leases, and propagating cancellation to running workflow steps.

## Scope

- SIGTERM handler registration in `TramaiWorker`: install a JVM shutdown hook that calls `shutdown()` and awaits graceful completion
- Stop accepting new work: on shutdown signal, set an `accepting` flag to `false`; the poll loop checks this flag before calling `claim()`
- Drain to checkpoint: for each in-progress workflow run, wait for the current step to complete and persist the checkpoint (respecting a configurable `drainTimeout`)
- Lease release: after draining, call `release(workflowRunId, fencingToken)` so another worker can pick up immediately rather than waiting for lease expiry
- Cancellation propagation: if the `drainTimeout` is exceeded for a step, interrupt the step's execution thread and mark the workflow run as CANCELLED
- Worker deregistration: after draining, call `unregister(workerId)` on the `WorkerRegistry`
- Observer events: emit `onWorkerShutdownStart`, `onWorkerDrainProgress(done, pending)`, `onWorkerShutdownComplete`

## Exit Criteria

- [ ] SIGTERM triggers graceful shutdown within the configured drain timeout
- [ ] No new work is claimed after shutdown starts
- [ ] In-progress steps complete and checkpoint before shutdown
- [ ] Leases are released on the draining worker, not left to expire
- [ ] Steps exceeding `drainTimeout` are cancelled and marked CANCELLED
- [ ] Worker deregistration runs as the final step
- [ ] Observer events fire at each stage of the shutdown lifecycle
