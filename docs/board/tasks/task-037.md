# TASK-037: Implement Worker Pool with Lease-Based Work Stealing

- Status: planned
- Priority: high
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Enable horizontal scaling by allowing multiple TramAI worker processes to
coordinate execution via a shared PostgreSQL checkpoint store.

## Scope

- `TramaiWorker` class: standalone process that polls for pending workflows
- Worker registration: heartbeat table with worker ID, version, start time
- Lease-based work claiming via `WorkflowLeaseStore.claim()`
- Lease renewal at configurable interval
- Poll loop: query for pending/expired workflows, claim, execute
- Graceful shutdown: stop accepting, drain in-progress, release leases
- Step idempotency marker: validate at build time, enforce on resume
- Optional partition pinning: `workerIndex = hash(workflowId) % workerCount`
- OpenTelemetry events: worker start/stop, lease claim/release/renewal,
  workflow takeover
- Configuration: poll interval, lease duration, worker ID, partition count

## Exit Criteria

- [ ] Two workers on separate JVMs claim different workflows concurrently
- [ ] If worker A crashes mid-step, worker B resumes workflow A within
  `leaseDuration + pollInterval`
- [ ] Graceful shutdown completes in-progress steps before exiting
- [ ] Non-idempotent step refuses to resume and fails with clear error
- [ ] Optional partition pinning distributes workflows evenly across workers
- [ ] OpenTelemetry traces attribute each step to the worker that ran it
- [ ] Worker heartbeats are visible and stale workers are detectable
