# SPEC-013: Workflow Scheduling

- Status: proposed
- Owner: maintainer
- Last updated: 2026-05-03
- Related roadmap milestone: Phase 6 — Schedule
- Related ADRs:
- Related docs: [Orchestrator Vision](../architecture/orchestrator-vision.md)

## Problem

TramAI workflows can only execute on demand via `workflow.run()`. There is no
built-in mechanism to trigger a workflow at a specific time, on a recurring
schedule, or after a delay. Users must bring their own cron daemon, Quartz
configuration, or external scheduler to bridge this gap.

A first-class scheduling module would let workflows define their own schedule
inline, integrating with the existing checkpoint and lease infrastructure so
timed executions are durable and observable.

## Scope

- a new `tramai-scheduler` module
- an SPI for pluggable clock/timer backends (in-process, Quartz, JDBC-poll)
- inline schedule definition on workflow build: `at("0 9 * * 1")`, `every("30m")`
- delay steps: `after(5, MINUTES)` — pause execution for a duration
- timezone-aware scheduling
- calendar-aware scheduling (skip holidays, business-hours-only)
- full integration with `WorkflowObserver` for observability
- integration with the checkpoint/lease stores for durable scheduling

## Non-Goals

- distributed cron coordination across multiple scheduler nodes (deferred to SPEC-016)
- dynamic schedule changes at runtime (stop the workflow, change the definition, resume)
- complex calendar rules beyond simple holiday sets in v1

## Functional Requirements

- A workflow must be able to declare a cron expression at build time.
- A workflow must be able to pause between steps via a `delayStep`.
- The scheduler must fire workflow runs on schedule even if the JVM restarts
  (durable scheduling via WorkflowStore).
- The scheduler must support timezone-aware cron expressions.
- The scheduler must support a "skip on" calendar for holidays and off-hours.
- The schedule must be part of the workflow definition fingerprint so changing
  it invalidates resumed checkpoints.
- The scheduler must emit observer events for scheduled, skipped, started,
  and missed ticks. When `tramai-observability` is present on the classpath,
  these events bridge to OpenTelemetry spans and metrics.

## Quality Requirements

- Startup: scheduler recovery must scan and re-arm all pending schedules
  within 5 seconds for up to 500 workflows.
- Precision: scheduled ticks must fire within 1 second of the target time under
  normal load.
- Testing: every schedule trigger path must have a unit test with a
  deterministic clock.

## Design Notes

### Unified WorkflowStore

The scheduler uses a single `WorkflowStore` interface — not separate checkpoint and
scheduler stores. Workflow durability requires shared transactional semantics across
checkpoint writes, schedule updates, lease acquisition, cancellation requests, and
step attempt records. Splitting the interface early would pretend these boundaries
are independent when they are not.

```kotlin
interface WorkflowStore {
    // ── Checkpoint operations ──
    suspend fun saveCheckpoint(runId, state, nextStepIndex, lastCompletedStep, stepExecutions)
    suspend fun loadCheckpoint(runId): WorkflowCheckpoint?
    suspend fun deleteCheckpoint(runId)

    // ── Run metadata operations ──
    suspend fun createRun(run: RunRecord)
    suspend fun updateRunStatus(runId, status: RunStatus)
    suspend fun getRun(runId): RunRecord?
    suspend fun listRuns(workflowName, status, limit, cursor): Page<RunRecord>

    // ── Step attempt operations ──
    suspend fun saveStepAttempt(attempt: StepAttemptRecord)
    suspend fun getLastAttempt(runId, stepName): StepAttemptRecord?

    // ── Schedule operations ──
    suspend fun upsertSchedule(schedule: ScheduleRecord)
    suspend fun getSchedule(scheduleId): ScheduleRecord?
    suspend fun claimDueTicks(now, ownerId, claimDuration, limit): List<ClaimedTick>
    suspend fun markTickStarted(tickId, claimToken, runId)
    suspend fun markTickCompleted(tickId, claimToken)
    suspend fun markTickSkipped(tickId, claimToken, reason)
    suspend fun markTickMisfired(tickId, claimToken, reason)
    suspend fun scheduleDelayWakeup(runId, stepId, resumeAt)
    suspend fun claimDueDelayWakeups(now, ownerId, claimDuration, limit): List<ClaimedWakeup>

    // ── Lease operations ──
    suspend fun acquireLease(workflowName, runId, workerId, leaseDuration): Boolean
    suspend fun renewLease(workflowName, runId, workerId, leaseDuration): Boolean
    suspend fun releaseLease(workflowName, runId, workerId)

    // ── Cancellation operations ──
    suspend fun requestCancellation(runId, source, reason)
    suspend fun getCancellationRequest(runId): CancellationRequest?

    // ── Worker management operations ──
    suspend fun registerWorker(worker: WorkerRecord)
    suspend fun updateWorkerHeartbeat(workerId)
    suspend fun listWorkers(): List<WorkerRecord>
n
    // ── Run claiming operations ──
    suspend fun claimPendingRun(workflowName, workerId, leaseDuration): RunRecord?
    suspend fun listExpiredLeases(now, limit): List<ExpiredLease>
    suspend fun updateStepAttemptStatus(runId, stepName, attemptId, status)
}
```

### JDBC Implementation

The JDBC adapter may use separate tables internally but presents a single
transactional boundary. Tick creation uses insert-if-absent semantics.
`claimDueTicks` must be a single atomic operation using
`SELECT ... FOR UPDATE SKIP LOCKED` or equivalent.

### Misfire Policy

| Policy | Behavior |
|--------|----------|
| `SKIP` | Mark missed ticks as misfired; compute next future fire time |
| `FIRE_ONCE` | Create one tick for the latest missed fire time; skip older |
| `CATCH_UP_BOUNDED` | Create up to `maxCatchUpTicks` missed ticks, then skip rest |
| `FAIL_SCHEDULE` | Disable the schedule and emit terminal schedule failure |

Default: `FIRE_ONCE` for cron schedules, `SKIP` for high-frequency intervals.

### DST Handling

- Use IANA `ZoneId`, store claim instants in UTC.
- Spring-forward (skipped local time): do not fire; mark `skipped_dst_gap`.
- Fall-back (repeated local time): fire once per cron occurrence using earlier
  offset by default. Configurable via `repeatedTimePolicy`.

### Duplicate-Tick Prevention

- `tick_id = sha256(schedule_id + scheduled_fire_at_utc + occurrence_index)`
- Unique constraint on (schedule_id, scheduled_fire_at, occurrence_index)
- Insert-if-absent semantics for tick creation
- Atomic conditional update for tick claiming
- Starting a run from a tick checks existing `workflow_run_id` first

### Relation Between Scheduler and Checkpoints

1. Scheduler store claims a due tick and receives a fencing token.
2. Runtime starts a workflow run with trigger metadata.
3. Runtime writes the initial checkpoint in `WorkflowStore`.
4. Scheduler marks the tick started with the `workflow_run_id`.
5. If the runtime fails before step execution, the tick remains claimable
   after claim expiry.
6. A failed workflow run is stored in `WorkflowStore`; the tick links to the
   run and does not create a second run.

## Acceptance Criteria

- A workflow with `at("0 9 * * 1")` executes every Monday at 9 AM in the
  configured timezone.
- A workflow with `delayStep` pauses mid-execution and resumes after the
  delay elapses.
- If the JVM restarts between ticks, the schedule is recovered from the
  WorkflowStore and fires at the next expected time.
- The schedule is included in the workflow definition digest so a changed
  schedule causes a clean break (reject resume, require fresh run).
- Observer receives `onScheduledTick`, `onSkippedTick`, `onMissedTick` events.

## Risks and Follow-Ups

- Calendar-aware scheduling (holidays, business hours) adds significant
  complexity. Defer to a follow-up if the v1 scope is tight.
- Distributed coordination across multiple scheduler nodes is deferred to
  SPEC-016.
