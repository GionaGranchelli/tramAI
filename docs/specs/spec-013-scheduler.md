# SPEC-013: Workflow Scheduling

- Status: implemented
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
- inline schedule definition on workflow build via DSL functions:
  - `at("cron expression", zone, skipCalendar, businessHoursOnly)` — cron expression
  - `every(amount, ChronoUnit, zone, skipCalendar, businessHoursOnly)` — interval-based (seconds, minutes, hours, days)
  - `dailyAt(hour, minute, second, zone, skipCalendar, businessHoursOnly)` — daily at specific time
- delay steps: `delayStep("stepName", duration, timeUnit)` — pause execution for a duration
- timezone-aware scheduling via IANA `ZoneId`
- calendar-aware scheduling:
  - `skipCalendar: List<CalendarRule>` — skip specific dates/ranges/weekdays
  - `businessHoursOnly: Boolean` — restrict to 09:00-18:00 Mon-Fri
  - Calendar rules: `FixedDate(month, dayOfMonth)`, `NthWeekdayOfMonth(month, nth, dayOfWeek)`, `DateRange(startMonth, startDay, endMonth, endDay)`
- `onSkippedTick` callback parameter on `nextFireAfter()` for observability
- full integration with `WorkflowObserver` for scheduled/skipped/missed tick events
- integration with `WorkflowSchedulerStore` for durable JDBC scheduling

## Non-Goals

- distributed cron coordination across multiple scheduler nodes (deferred to SPEC-016)
- dynamic schedule changes at runtime (stop the workflow, change the definition, resume)
- true "every N days" continuous semantics — `every(N, DAYS)` uses `*/N` day-of-month cron which resets at month boundaries; documented behavior

## Functional Requirements

- A workflow must be able to declare a cron expression at build time.
- A workflow must be able to pause between steps via a `delayStep`.
- The scheduler must fire workflow runs on schedule even if the JVM restarts
  (durable scheduling via WorkflowStore).
- The scheduler must support timezone-aware cron expressions via `ZoneId`.
- The scheduler must support a "skip on" calendar (`skipCalendar`) for skipping
  specific dates (FixedDate), nth weekdays of a month (NthWeekdayOfMonth), and
  inclusive date ranges (DateRange).
- The scheduler must support business-hours-only mode (`businessHoursOnly`):
  restrict execution to 09:00-18:00 Monday-Friday.
- Business-hours adjustment returns the next business-hour start directly
  without re-validating against the cron expression (documented behavior).
- The schedule DSL must include convenience functions: `at()`, `every(amount, ChronoUnit)`,
  and `dailyAt(hour, minute)`.
- `every(N, DAYS)` uses `*/N` day-of-month cron stepping — this resets at month
  boundaries. For example, `every(2, DAYS)` fires on Jan 31, then Feb 1 (1-day 
  gap), then Feb 3 — not true "every 2 days" continuous intervals.
- Calendar rules are validated at build time: month/day combinations that are
  not valid every year (e.g., Feb 29) are rejected.
- Invalid timezone IDs are rejected at build time with `DateTimeException`.
- `every()` validates `amount >= 1` and `amount <= Int.MAX_VALUE`.
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

### Scheduler Store Interface

The scheduler uses a dedicated `WorkflowSchedulerStore` interface for durable
tick management. The store handles schedule registration, tick claiming with
lease-based fencing, and delay wakeup scheduling:

```kotlin
interface WorkflowSchedulerStore {
    suspend fun upsertSchedule(schedule: ScheduleRecord)
    suspend fun getSchedule(scheduleId: String): ScheduleRecord?
    suspend fun claimDueTicks(now, ownerId, claimDuration, limit): List<ClaimedScheduledTick>
    suspend fun markTickStarted(tickId, claimToken, runId)
    suspend fun releaseTickClaim(tickId, claimToken)
    suspend fun markTickCompleted(tickId, claimToken)
    suspend fun markTickSkipped(tickId, claimToken, reason)
    suspend fun markTickMisfired(tickId, claimToken, reason)
    suspend fun scheduleDelayWakeup(runId, stepId, resumeAt)
    suspend fun claimDueDelayWakeups(now, ownerId, claimDuration, limit): List<ClaimedDelayWakeup>
    suspend fun releaseDelayWakeupClaim(runId, stepId, claimToken)
    suspend fun markDelayWakeupCompleted(runId, stepId, claimToken)
}
```

`ScheduleRecord` carries the schedule definition, next fire time, enabled flag,
and calendar metadata (`skipCalendar`, `businessHoursOnly`) for persistence.

Implementations:
- `InMemoryWorkflowSchedulerStore` — for testing; `nextFireAfter()` computed
  outside the synchronized block to avoid holding the lock during cron resolution.
- `JdbcWorkflowSchedulerStore` — production-grade with HikariCP connection pool,
  `FOR UPDATE SKIP LOCKED` for concurrent claim safety, and JSON-encoded
  calendar rules in `skip_calendar` text column. Calendar rules use a hand-rolled
  JSON tokenizer (not regex) for safe deserialization.

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
- Spring-forward (skipped local time): fire at the next valid instant; no
  duplicate or missed tick within the skipped hour.
- Fall-back (repeated local time): fire once at the earlier offset.
- DST transitions are tested for `every(1, HOURS)` across Europe/Rome boundaries.

### Duplicate-Tick Prevention

- `tick_id = sha256(schedule_id + scheduled_fire_at_utc + occurrence_index)`
- Unique constraint on (schedule_id, scheduled_fire_at, occurrence_index)
- Insert-if-absent semantics for tick creation
- Atomic conditional update for tick claiming
- Starting a run from a tick checks existing `workflow_run_id` first

### Relation Between Scheduler and Checkpoints

1. Scheduler store claims a due tick and receives a fencing token.
2. Runtime starts a workflow run with trigger metadata.
3. Runtime writes the initial checkpoint in `WorkflowPersistence`.
4. Scheduler marks the tick started with the `workflow_run_id`.
5. If the runtime fails before step execution, the tick remains claimable
   after claim expiry.
6. A failed workflow run is stored in the checkpoint store; the tick links to
   the run and does not create a second run.
7. Business-hours adjustment returns the next 09:00 business-day start 
   directly — subsequent `nextFireAfter()` calls re-evaluate against the cron
   expression from that adjusted time.

## Acceptance Criteria

- A workflow with `at("0 9 * * 1", zone = "Europe/Rome")` executes every Monday
  at 9 AM in the configured timezone.
- A workflow with `delayStep` pauses mid-execution and resumes after the
  delay elapses.
- If the JVM restarts between ticks, the schedule is recovered from the
  WorkflowSchedulerStore and fires at the next expected time.
- The schedule is included in the workflow definition digest so a changed
  schedule causes a clean break (reject resume, require fresh run).
- Observer receives `onScheduledTick`, `onSkippedTick`, `onMissedTick` events.
- `at("0 9 * * *", skipCalendar = listOf(FixedDate(12, 25)))` skips December 25th
  and fires on December 26th instead.
- `businessHoursOnly = true` skips ticks that fall outside 09:00-18:00 Mon-Fri
  and returns the next business-hour start.
- `every(5, ChronoUnit.MINUTES)` fires every 5 minutes aligned to the hour.
- Calendar rules are round-tripped through JDBC persistence without data loss.
- Malformed calendar rule payloads in the database are rejected at deserialization
  with a clear error message.

## Risks and Follow-Ups

- `every(N, DAYS)` uses `*/N` day-of-month cron which resets at month boundaries —
  callers expecting continuous "every N days" semantics may be surprised.
  Documented behavior; a non-cron scheduling mechanism would be needed for
  true continuous daily intervals.
- Business-hours adjustment returns 09:00 directly without re-validating the
  cron expression — subsequent `nextFireAfter()` calls re-evaluate from that
  adjusted time. This is documented behavior.
- Distributed coordination across multiple scheduler nodes is deferred to
  SPEC-016.
