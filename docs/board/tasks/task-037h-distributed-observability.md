# TASK-037H: Distributed Observability

- Status: planned
- Priority: medium
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Parent task: [TASK-037](../tasks/task-037.md)
- Last updated: 2026-05-03

## Purpose

Instrument distributed execution with structured events and optional OpenTelemetry integration so operators can trace workflow execution across worker boundaries, monitor lease health, and detect failovers and rebalance events.

## Scope

- Worker lifecycle events on `WorkflowObserver`:
  - `onWorkerStarted(workerId, hostname, version, definitionsDigest)`
  - `onWorkerStopped(workerId, reason: SHUTDOWN / CRASH / STALE)`
  - `onWorkerHeartbeat(workerId, uptime, claimedCount)`
- Lease events:
  - `onLeaseClaimed(workflowRunId, workerId, fencingToken, leaseDuration)`
  - `onLeaseReleased(workflowRunId, workerId, fencingToken, reason: COMPLETED / FAILED / SHUTDOWN)`
  - `onLeaseRenewed(workflowRunId, workerId, fencingToken, newExpiry)`
  - `onLeaseExpired(workflowRunId, previousWorkerId)`
  - `onLeaseContested(workflowRunId, claimantWorkerId, currentWorkerId)`
- Failover events:
  - `onWorkflowTakeover(workflowRunId, previousWorkerId, newWorkerId, fromStep)`
  - `onWorkflowAbandoned(workflowRunId, workerId, lastStep, timeout)`
- Optional OTel bridge attributes:
  - `tramai.worker.id`
  - `tramai.worker.version`
  - `tramai.workflow.run_id`
  - `tramai.lease.fencing_token`
  - `tramai.lease.duration_ms`
  - `tramai.partition.index`
- All events are no-op by default; OTel bridge is opt-in via a separate module or configuration flag
- Log-based fallback: when OTel is not present, events are logged at INFO level with structured key=value pairs

## Exit Criteria

- [ ] All worker lifecycle events fire at the correct points
- [ ] All lease lifecycle events fire at the correct points
- [ ] Failover events fire when a worker takes over an expired or abandoned lease
- [ ] OTel span attributes are set on workflow execution spans when the bridge is active
- [ ] Events are no-op by default and OTel bridge is opt-in
- [ ] Structured log output is present when OTel is not configured
