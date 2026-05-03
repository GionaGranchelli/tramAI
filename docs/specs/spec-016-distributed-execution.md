# SPEC-016: Distributed Execution

- Status: proposed
- Owner: maintainer
- Last updated: 2026-05-03
- Related roadmap milestone: Phase 9 — Distributed
- Related ADRs:
- Related docs: [Orchestrator Vision](../architecture/orchestrator-vision.md)

## Problem

TramAI workflows currently execute within a single JVM. Distributed execution
enables horizontal scaling, automatic failover, and higher throughput.

## Scope

- Worker pool with lease-based work stealing via WorkflowStore
- Step attempt records with fencing tokens
- 4 replay policies for safe crash recovery
- Graceful shutdown: drain in-progress, release leases
- Worker registration and heartbeat
- Partitioning with stable hash

## Non-Goals

- Multi-region geo-distribution (single-region first)
- Dynamic auto-scaling (workers manually configured in v1)

## Why Resume-From-Checkpoint Is Unsafe

Without step attempt records, crash recovery is unsafe:

```
Worker A: starts shellStep("helm-deploy")
  → writes attempt record: status=started
  → runs "helm upgrade --install myapp ./chart"
  → command succeeds, pod is deployed
  → Worker A CRASHES before writing the checkpoint

Worker B: resumes from last checkpoint
  → sees shellStep has no completed checkpoint
  → assumes it never ran
  → re-executes "helm upgrade --install myapp ./chart"
  → DEPLOYS TWICE — the second may conflict or fail
```

The gap between "I started" and "I saved completion" is the danger zone.
The store knows what was durably recorded; it cannot infer what happened
in an external system after a crash boundary.

## Solution: Step Attempt Records + Replay Policies

### Step Attempt Records

Every step execution creates an attempt record in WorkflowStore:

| Field | Purpose |
|-------|---------|
| run_id | Owning run |
| step_name | Step being executed |
| attempt_id | Unique attempt identifier |
| worker_id | Worker that started the attempt |
| lease_token | Fencing token active when attempt started |
| status | started, completed, failed, cancelled, unknown |
| started_at | When the attempt began |
| completed_at | When the attempt finished (if known) |
| idempotency_key | Key passed to external systems |
| replay_policy | PURE, IDEMPOTENT, EXTERNALLY_IDEMPOTENT, NON_REPLAYABLE |
| input_fingerprint | Hash of the step input |
| output_summary | Truncated result or error summary |

Before executing a step, the worker writes `status = started`. After successful
execution and checkpoint write, it updates to `completed`. An attempt with
`status = started` and an expired lease is treated as `unknown`.

### Four Replay Policies

| Policy | Behavior on unknown attempt |
|--------|----------------------------|
| `PURE` | Re-run the step (no external side effects, e.g. local computation) |
| `IDEMPOTENT` | Re-run the step (e.g. GET request, PUT with same body) |
| `EXTERNALLY_IDEMPOTENT` | Re-run with the same idempotency key (e.g. POST with Idempotency-Key) |
| `NON_REPLAYABLE` | **Fail** with `NonReplayableStepStateUnknownException` |

Default replay policies:
- localStep: PURE (if no external IO)
- aiStep: IDEMPOTENT (LLM calls are stateless)
- HTTP GET/HEAD/OPTIONS: IDEMPOTENT
- HTTP PUT/DELETE: IDEMPOTENT
- HTTP POST/PATCH: NON_REPLAYABLE (unless idempotency key provided)
- Shell: NON_REPLAYABLE
- MCP: NON_REPLAYABLE (unless tool declares idempotency)
- Hermes/Codex: NON_REPLAYABLE

### Checkpoint Fencing

Every checkpoint write in distributed mode must include the active lease token.
The store rejects the write when the token no longer owns the run. This prevents
a stale worker from overwriting state after another worker has taken over.

### Worker Pool

- Workers register on startup via `WorkflowStore.registerWorker()`
- Each worker has: worker_id, version, pool name, capability labels, host
- Workers send heartbeats at configurable interval
- Workers poll WorkflowStore for pending/expired runs
- When claiming a run: acquire lease via `WorkflowStore.acquireLease()`
- Lease renewal: refresh at leaseDuration / 2 interval
- On crash: lease expires, another worker can claim after `leaseDuration`

### Graceful Shutdown

- On SIGTERM: stop accepting new work
- Complete in-progress steps up to configured drain timeout
- Release all leases
- Unregister from worker registry

### Partitioning

- Optional: pin work via `stableHash(runId) % workerCount`
- Stable hash: SHA-256 prefix, not `hashCode()`
- Rebalance: when worker count changes, only unowned runs are reassigned

### Failure Handling

- Leased work with expired lease and started attempt: follow replay policy
- Leased work with expired lease and no attempt: re-execute
- `NON_REPLAYABLE` step with unknown attempt: raise exception with run ID,
  step name, prior worker ID, attempt time, and recovery instructions
- Split-brain prevention: fencing tokens + stale write rejection

### Observability

- Observer events: onWorkerStarted, onWorkerStopped, onLeaseAcquired,
  onLeaseReleased, onLeaseExpired, onWorkTakenOver, onUnknownAttempt
- When tramai-observability present: bridge to OTel spans

## Acceptance Criteria

- [ ] Two workers claim different runs concurrently via WorkflowStore
- [ ] Worker A crashes mid-step, Worker B respects replay policy (fails on NON_REPLAYABLE)
- [ ] Worker A finishes step but loses lease before checkpoint; stale write rejected
- [ ] Graceful shutdown completes in-progress steps before exiting
- [ ] NonReplayableStepStateUnknownException includes full context for recovery
- [ ] Partitioning distributes runs evenly; adding/removing workers reassigns cleanly
- [ ] Worker heartbeats visible; stale workers detected and leases released
- [ ] Step attempt records persisted and inspectable
- [ ] All observer events fire without OTel on classpath
