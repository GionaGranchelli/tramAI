# TASK-037: Implement Worker Pool with Lease-Based Work Stealing

- Status: done
- Priority: high
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Related ADRs:
- Last updated: 2026-05-04
- Implemented by: Copilot (gpt-5.4), reviewed by Codex (deepseek-v4-pro), fixed by Copilot (gpt-5.4)
- Commits: 18f27f7 (impl), c034237 (fix)

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

- [x] Two workers on separate JVMs claim different workflows concurrently
- [x] If worker A crashes mid-step, worker B resumes workflow A within
  `leaseDuration + pollInterval`
- [x] Graceful shutdown completes in-progress steps before exiting
- [x] Non-idempotent step refuses to resume and fails with clear error
- [x] Optional partition pinning distributes workflows evenly across workers
- [x] OpenTelemetry traces attribute each step to the worker that ran it
- [x] Worker heartbeats are visible and stale workers are detectable

## Implementation Summary

8 new/modified files, +2516/-236 lines across 2 commits.

**New files in `tramai-orchestration`:**
- `StepAttemptRecord.kt` — ReplayPolicy enum (PURE/IDEMPOTENT/EXTERNALLY_IDEMPOTENT/NON_REPLAYABLE),
  StepAttemptStatus enum, StepAttemptRecord data class, StepAttemptRecordStore SPI,
  NonReplayableStepStateUnknownException with full recovery context
- `WorkerConfig.kt` — workerId, poolName, capabilityLabels, poll/lease/drain timeouts, partition settings
- `WorkerRegistryStore.kt` — SPI for worker registration, heartbeat, unregistration, staleness detection
- `TramaiWorker.kt` — poll loop with lease-based work stealing, atomic lease fencing via
  WorkflowLeaseCheckpointFence, step-attempt tracking with async observer, graceful shutdown
  with bounded drain, partition pinning via SHA-256, transient error retry with backoff
- `TramaiWorkerTest.kt` — 10 test cases covering concurrent claims, NON_REPLAYABLE/PURE/IDEMPOTENT/
  EXTERNALLY_IDEMPOTENT replay policies, drain timeout, lease renewal failure, concurrent shutdown,
  partition pinning, heartbeats, attempt records

**Modified files:**
- `Workflow.kt` — step replay metadata, typed persistence binding for resume
- `WorkflowLease.kt` — WorkflowLeaseCheckpointFence for atomic fenced checkpoint mutations
- `WorkflowPersistence.kt` — in-memory checkpoint catalog and step-attempt store support
- `FileWorkflowCheckpointStore.kt`, `JdbcWorkflowCheckpointStore.kt` — checkpoint enumeration for polling

**Key design decisions:**
- Lease fencing uses atomic compare-and-swap at the store level, not validate-then-save
- Step-attempt recording is queued off the execution dispatcher via dedicated IO scope
- Shutdown guard uses AtomicBoolean CAS; post-drain join bounded by withTimeout
- Constructor accepts separate WorkflowCheckpointCatalog and StepAttemptRecordStore (ISP-compliant)
