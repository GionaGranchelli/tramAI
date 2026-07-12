# Module: `tramai-scheduler`

> **One-liner:** Time-based workflow triggers with cron expressions, delay wakeups, and a durable tick-claiming scheduler loop — backed by in-memory or JDBC stores.
> **Module type:** `optional`
> **Group:** `dev.tramai`
> **Source files:** 5, **LOC:** 2,560
> **Dependencies:** `tramai-orchestration`, `kotlinx-coroutines-core`, `HikariCP`

---

## L1: Quick Start (30-second read)

### What

`tramai-scheduler` provides **time-based workflow triggers** for the Tramai orchestration engine. It solves two complementary problems:

1. **Recurring schedule ticks** — fire a workflow on a cron schedule, with calendar-aware skip rules and business-hours-only policies
2. **Delay wakeups** — resume a suspended workflow after a timed delay (the "alarm clock" for `delayStep` in `tramai-orchestration`)

The module is built around a **polling timer** (`ScheduledWorkflowTimer`) that periodically claims due ticks from a scheduler store (`WorkflowSchedulerStore`), launches the registered workflow, and advances the schedule to the next fire time.

### Why

Without `tramai-scheduler`, workflows have no built-in clock. A workflow that should run "every morning at 9 AM" or "after a 1-hour approval wait" would require external cron daemons, manual scheduling logic, or fragile polling. `tramai-scheduler` provides:

- **Cron-as-first-class-citizen** — workflows declare their schedule via a `CronSchedule` object, not external config
- **Calendar-aware skipping** — holidays and business calendars are defined alongside the schedule, not bolted on
- **Business-hours enforcement** — `businessHoursOnly` automatically adjusts off-hours fires to the next 9 AM start
- **Durable tick claiming** — distributed-safe via claim tokens and expiry, supports multiple scheduler instances
- **Delay wakeup bridge** — the `WorkflowSchedulerStore` doubles as `WorkflowDelayWakeupScheduler` for `tramai-orchestration`'s `delayStep`

### When to use

Use `tramai-scheduler` when you need:

- **Recurring workflows** — `every(1, MINUTES)`, `dailyAt(9, 0)`, or arbitrary cron expressions
- **Skipped-date awareness** — skip public holidays with `CalendarRule.FixedDate` or `CalendarRule.NthWeekdayOfMonth`
- **Business-hours scheduling** — `businessHoursOnly = true` ensures workflows only fire during Mon–Fri 9 AM–6 PM
- **Delay-based resumption** — `delayStep` in orchestration workflows needs a scheduler store to wake up after the wait
- **Distributed scheduling** — multiple `ScheduledWorkflowTimer` instances can share a JDBC-backed store and coordinate via claim tokens

Do **not** use it for:
- One-shot ad-hoc scheduling — just call `workflow.run()` directly; you don't need the scheduler infrastructure
- Millisecond-precision timing — the default poll interval is 1 second, and cron resolution is at the second level
- Schedules that cannot be expressed as cron — the module currently supports only cron-based `WorkflowScheduleDefinition`

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-scheduler:0.4.0")
}
```

**Maven:**

```xml
<dependency>
    <groupId>dev.tramai</groupId>
    <artifactId>tramai-scheduler</artifactId>
    <version>0.4.0</version>
</dependency>
```

**Bill of Materials:**

```kotlin
implementation(platform("dev.tramai:tramai-bom:0.4.0"))
implementation("dev.tramai:tramai-scheduler")
```

### Where to go next

| Topic | Link |
|-------|------|
| Workflow basics with checkpoints and delays | `docs/specs/spec-005.md` |
| Agent CLI step types | `docs/specs/spec-009.md` |

---

## L2: Usage Guide (5-minute read)

### Defining schedules — `CronSchedule`

The entry point for cron definitions is the `at()` and `every()` top-level functions:

```kotlin
import dev.tramai.scheduler.*
import java.time.ZoneId

// Five-field (min hour day-of-month month day-of-week)
val schedule1 = at("0 9 * * 1-5", zone = "America/New_York")

// Six-field (sec min hour day-of-month month day-of-week)
val schedule2 = at("0 0 9 * * 1-5")  // same as above, system TZ

// Daily at 9:00 AM
val schedule3 = dailyAt(hour = 9, minute = 0)

// Every 30 minutes on the :00 and :30 marks
val schedule4 = every(30, MINUTES)

// Every 2 days (cron day-of-month step — resets at month boundaries)
val schedule5 = every(2, DAYS)
```

#### Cron expression rules

- **5-field**: `minute` `hour` `day-of-month` `month` `day-of-week` — seconds fixed to `0`
- **6-field**: `second` `minute` `hour` `day-of-month` `month` `day-of-week` — for tests and high-frequency timers
- Fields support `*`, ranges (`1-5`), steps (`*/15`), lists (`1,3,5`), and combinations
- Day-of-week: `0` and `7` both mean Sunday; `1` = Monday ... `6` = Saturday

#### Calendar rules

Holidays and business calendars are expressed as `CalendarRule` values that cause the schedule to skip matching dates:

```kotlin
at("0 9 * * 1-5", skipCalendar = listOf(
    CalendarRule.FixedDate(month = 1, dayOfMonth = 1),        // New Year's
    CalendarRule.NthWeekdayOfMonth(                           // 3rd Monday of January (MLK Day)
        month = 1,
        nth = 3,
        dayOfWeek = DayOfWeek.MONDAY,
    ),
    CalendarRule.DateRange(                                   // Dec 24 – Jan 1 holiday period
        startMonth = 12, startDayOfMonth = 24,
        endMonth = 1, endDayOfMonth = 1,
    ),
))
```

Available rule types:

| Type | What it matches | Example |
|------|----------------|---------|
| `FixedDate` | A specific month and day-of-month | `FixedDate(1, 1)` = January 1 |
| `NthWeekdayOfMonth` | The Nth occurrence of a weekday in a month | Third Monday of January |
| `DateRange` | A date range (supports year-wrapping) | Dec 24 – Jan 1 |

#### Business-hours mode

When `businessHoursOnly = true`, fires that land outside Mon–Fri 9 AM–6 PM are automatically adjusted to the next business-hour start (09:00 local time). The adjustment is a **post-cron policy** — the adjusted time is not revalidated against the cron expression.

```kotlin
// 2 AM fire → adjusted to 9 AM same day
at("0 2 * * 1-5", businessHoursOnly = true)

// Weekend fire → adjusted to Monday 9 AM
dailyAt(hour = 6, minute = 0, businessHoursOnly = true)
```

### Registering workflows — `ScheduledWorkflowTimer`

`ScheduledWorkflowTimer` is the runtime that polls for due ticks and launches workflows.

```kotlin
import dev.tramai.orchestration.*
import dev.tramai.scheduler.*
import java.time.Clock
import java.time.Duration

// 1. Define a scheduled workflow
val reportWorkflow = workflow<ReportState>(name = "daily-report") {
    localStep("generate") { state, _ -> generateReport(state) }
    aiStep("summarize",
        input = { state -> state.rawText },
        invoke = { text -> llmService.summarize(text) },
        merge = { state, summary -> state.copy(summary = summary) },
    )
}.build { it }

// 2. Create a scheduler store (in-memory for tests, JDBC for production)
val store = InMemoryWorkflowSchedulerStore()

// 3. Create the timer
val timer = ScheduledWorkflowTimer(
    store = store,
    ownerId = "timer-1",
    pollInterval = Duration.ofSeconds(1),
    claimDuration = Duration.ofSeconds(30),
    misfireThreshold = Duration.ofMinutes(5),
    batchSize = 50,
)

// 4. Register the workflow — the timer computes the initial nextFireAt and stores it
timer.register(
    workflow = reportWorkflow,
    initialState = { ReportState() },
)

// 5. Start the polling loop
timer.start()

// 6. On shutdown
timer.stop()
// or
timer.close()
```

The timer's lifecycle:

1. `register()` validates the workflow's schedule, creates a `ScheduleRecord`, and persists it
2. `start()` launches a background coroutine that runs `pollOnce()` every `pollInterval`
3. Each `pollOnce()` call:
   - Claims due schedule ticks via `store.claimDueTicks()`
   - Claims due delay wakeups via `store.claimDueDelayWakeups()`
   - For each tick: checks expiry, misfire threshold, then starts the workflow
   - For each wakeup: resumes the suspended workflow

#### Misfire detection

A tick is misfired (skipped with `MISFIRED` status) if the time between `scheduledFireAt` and `now` exceeds `misfireThreshold`. This prevents backlogged ticks from executing long after their intended fire time.

```kotlin
ScheduledWorkflowTimer(
    misfireThreshold = Duration.ofMinutes(10),
    // any tick > 10 min late is recorded as misfired
)
```

### Scheduler store — `WorkflowSchedulerStore`

The store interface (`WorkflowSchedulerStore`) defines the contract for durable schedule and tick storage:

```kotlin
interface WorkflowSchedulerStore : WorkflowDelayWakeupScheduler {
    // Schedule lifecycle
    suspend fun upsertSchedule(schedule: ScheduleRecord)
    suspend fun getSchedule(scheduleId: String): ScheduleRecord?
    suspend fun listScheduleStatus(): List<ScheduleStatusView>

    // Tick lifecycle
    suspend fun claimDueTicks(now, ownerId, claimDuration, limit): List<ClaimedScheduledTick>
    suspend fun markTickStarted(tickId, claimToken, runId)
    suspend fun markTickCompleted(tickId, claimToken)
    suspend fun markTickSkipped(tickId, claimToken, reason)
    suspend fun markTickMisfired(tickId, claimToken, reason)
    suspend fun releaseTickClaim(tickId, claimToken)

    // Delay wakeups
    override suspend fun scheduleDelayWakeup(runId, stepId, resumeAt)
    suspend fun claimDueDelayWakeups(now, ownerId, claimDuration, limit): List<ClaimedDelayWakeup>
    suspend fun releaseDelayWakeupClaim(runId, stepId, claimToken)
    suspend fun markDelayWakeupCompleted(runId, stepId, claimToken)
}
```

#### In-memory store

`InMemoryWorkflowSchedulerStore` uses a synchronized `LinkedHashMap` for all records. Suitable for tests and single-process local use. Not durable across restarts.

```kotlin
val store = InMemoryWorkflowSchedulerStore()
```

#### JDBC store (production)

`JdbcWorkflowSchedulerStore` requires a `javax.sql.DataSource` (typically HikariCP) and provides a `createTableSql()` method for the required tables:

```kotlin
val dataSource = HikariDataSource().apply {
    jdbcUrl = "jdbc:postgresql://localhost:5432/tramai"
    username = "tramai"
    password = "secret"
}

val store = JdbcWorkflowSchedulerStore(dataSource = dataSource)

// Install tables
store.createTableSql().forEach { sql -> /* execute */ }
```

Three tables:

| Table | Purpose |
|-------|---------|
| `workflow_schedules` | Schedule definitions with cron expression, timezone, next fire time, calendar rules, version counter |
| `workflow_schedule_ticks` | Tick records with claim ownership, status, and occurrence deduplication |
| `workflow_delay_wakeups` | Delay wakeup registrations for workflow resumption |

The JDBC store also provides a `recover()` method for startup recovery — it materializes missed ticks after downtime and reports them via `WorkflowObserver.onMissedTick()`.

---

## L3: Architecture & Mechanics (15-minute read)

### Design philosophy

`tramai-scheduler` follows a **claim-based polling** pattern. Rather than pushing work directly (which would require tight coupling between scheduler and workflow executor), the scheduler:

1. **Claims** — atomically marks a tick as owned by a specific timer instance
2. **Executes** — runs the workflow with the claimed tick
3. **Marks terminal** — records completion, skip, or misfire

This pattern naturally supports:
- **Multiple timer instances** — each polls the same store; `claimDueTicks` uses optimistic concurrency (in-memory locking or `FOR UPDATE SKIP LOCKED`)
- **Lease-based recovery** — expired claims are reclaimed by other timers
- **Observability** — every tick state transition is persisted, enabling dashboards and audit

```
                 ┌──────────────────────────────────┐
                 │      ScheduledWorkflowTimer       │
                 │  (coroutine loop, pollInterval)   │
                 └──────────┬───────────────────────┘
                            │ pollOnce()
           ┌────────────────┼───────────────────┐
           ▼                ▼                    ▼
   claimDueTicks()    claimDueDelayWakeups()   listScheduleStatus()
           │                │
           ▼                ▼
   WorkflowSchedulerStore
    ┌──────────────────┐
    │  InMemoryStore   │  ← synchronized LinkedHashMap
    │  JdbcStore        │  ← PostgreSQL/MySQL/H2 with SKIP LOCKED
    └──────────────────┘
```

### Module boundary

```
tramai-scheduler
  ├── api: tramai-orchestration (WorkflowScheduleDefinition, WorkflowDelayWakeupScheduler, Workflow, WorkflowContext, WorkflowObserver)
  ├── impl: kotlinx-coroutines-core
  ├── impl: HikariCP (JDBC store)
  └── test: H2 database for JDBC store tests
```

**Owns:**
- `CronSchedule` — cron expression parser and next-fire-time calculator
- `CalendarRule` — date-based skip rules: `FixedDate`, `NthWeekdayOfMonth`, `DateRange`
- `ScheduledWorkflowTimer` — the polling coroutine loop that registers workflows, claims ticks, and launches executions
- `WorkflowSchedulerStore` — the store interface for schedule and tick persistence
- `ScheduleRecord`, `ScheduleStatusView`, `ClaimedScheduledTick`, `ClaimedDelayWakeup` — data transfer objects
- `InMemoryWorkflowSchedulerStore` — in-memory store backed by `LinkedHashMap`
- `JdbcWorkflowSchedulerStore` — JDBC store with table creation SQL and startup recovery

**Does not own:**
- `Workflow`, `WorkflowObserver`, `WorkflowScheduleDefinition` — owned by `tramai-orchestration`
- `WorkflowPersistence`, checkpoint store, lease store — owned by `tramai-orchestration`
- Coroutine scope / dispatcher configuration — caller provides via `ScheduledWorkflowTimer.scope`
- Framework integration — owned by adapters (`tramai-spring`, `tramai-standalone`)

### Dependency graph

```
tramai-scheduler
  └── api: tramai-orchestration
        └── api: tramai-core
              └── kotlinx-coroutines-core
```

### Inner mechanics

#### 1. Cron schedule computation (`CronSchedule`)

The `CronSchedule` class parses a cron expression into six `CronField` instances (second, minute, hour, day-of-month, month, day-of-week). Each `CronField` is a set of matching integer values plus a `isWildcard` flag for efficient day-of-week/day-of-month logic.

**`nextFireAfter(after)` algorithm:**

```
nextFireAfter(after):
  1. Start search at `after + 1 second`, truncated to second (or minute if 5-field)
  2. Loop up to 5 years ahead:
     a. Compute nextCronFireAfter(searchAfter):
        - Walk forward through month, day, hour, minute, second
        - Returns the first `ZonedDateTime` matching all six fields
     b. Check against calendar rules:
        - If any `CalendarRule.matches(fireAt)` → schedule falls through
        - Call `onSkippedTick(fireAt, "calendar_skip:...")` and continue
     c. Check business-hours policy:
        - If `businessHoursOnly && !isBusinessHour(fireAt)`:
          - Return `nextBusinessHourStart(fireAt)` instead
          - The adjusted time is NOT revalidated against cron
     d. Return fireAt as the next valid fire time
  3. If no fire within 5 years → throw IllegalArgumentException
```

**Day-of-week/day-of-month matching** uses the standard cron rule: if neither field is a wildcard, a date matches if *either* field matches (logical OR). If one is a wildcard, only the other is checked.

**Calendar rule matching** is done post-cron. If a fire falls on a skipped date, the next fire is computed by advancing `searchAfter` to the skipped fire time and re-entering the cron search. This means skipped dates can lengthen the interval, but the cron pattern is always respected.

**Business-hours adjustment** is intentionally a post-cron policy. When a fire is outside 9 AM–6 PM Mon–Fri, the timer returns the next business-hour start without re-validating against the cron expression. A 2 AM schedule with `businessHoursOnly=true` will always return 9 AM that day (or Monday if weekend).

**Key limitation with `every(N, DAYS)` for N > 1:** Cron cannot represent continuous "every N days" intervals because day-of-month step semantics reset at each month boundary. `every(2, DAYS)` fires on every second selected day within the current month, but restarts the counter on the 1st of the next month. For truly continuous intervals, use `ChronoUnit.HOURS` or `ChronoUnit.MINUTES` instead.

#### 2. Tick claiming protocol

The tick lifecycle has five states, defined by the `TickStatus` enum (internal to `InMemoryWorkflowSchedulerStore`):

```
                  ┌──────────┐
                  │  CLAIMED │  ← claimed by a timer; claim has token + expiry
                  └────┬─────┘
                       │ markTickStarted()
                       ▼
                  ┌──────────┐
                  │  STARTED │  ← workflow execution has begun
                  └────┬─────┘
                       │
               ┌───────┼───────────┐
               ▼       ▼           ▼
          ┌────────┐ ┌────────┐ ┌─────────┐
          │COMPLETED│ │SKIPPED │ │MISFIRED │  ← terminal states
          └────────┘ └────────┘ └─────────┘
```

**Claim protocol:**

1. `claimDueTicks(now, ownerId, claimDuration, limit)`:
   a. First, reclaim any expired claims (status `CLAIMED` or `STARTED` with `claimExpiresAt <= now`)
   b. Then, find enabled schedules with `nextFireAt <= now`, up to `limit`
   c. For each: compute a SHA-256 `tickId` from `(scheduleId, scheduledFireAt)`, generate a unique `claimToken` (UUID), and insert a tick record with `status = CLAIMED`
   d. Advance the schedule: compute the next `nextFireAt` via `CronSchedule.nextFireAfter(scheduledFireAt)`
   e. Return the claimed ticks

2. `markTickStarted(tickId, claimToken, runId)` — transitions from `CLAIMED` to `STARTED`, records the `workflowRunId`

3. Terminal marks (`markTickCompleted`, `markTickSkipped`, `markTickMisfired`) — transitions to terminal state; requires matching `claimToken`

4. `releaseTickClaim` — keeps status as `CLAIMED` but clears `ownerId`, `claimToken`, and sets `claimExpiresAt` to epoch, making it eligible for reclamation

In the JDBC store, all state transitions use `WHERE status IN ('CLAIMED', 'STARTED') AND claim_token = ?` to prevent:
- Double-processing by two timer instances
- Race conditions between tick start and expiry

#### 3. Store mechanics

##### InMemoryWorkflowSchedulerStore

- Backed by four `LinkedHashMap`s: `schedules`, `ticks`, `delayWakeups`
- All mutations are synchronized on `this` (the store instance)
- Tick claiming uses a retry loop: it snapshots due schedules, checks the tick doesn't already exist, inserts the tick, and advances the schedule — all under `synchronized`
- SHA-256 `tickId = digest("$scheduleId:$scheduledFireAtEpochMillis")`
- Suitable for single-process, non-durable scheduling (tests, local dev)

##### JdbcWorkflowSchedulerStore

- Requires a `javax.sql.DataSource` (HikariCP recommended)
- All operations run inside `transaction { connection -> ... }` blocks with `autoCommit = false` and explicit `commit()` / `rollback()`

**Three tables:**

```sql
-- Schedule registry
CREATE TABLE workflow_schedules (
    schedule_id        VARCHAR(255) PRIMARY KEY,
    workflow_name      VARCHAR(255) NOT NULL,
    cron_expression    VARCHAR(255) NOT NULL,
    timezone           VARCHAR(255) DEFAULT 'UTC',
    next_fire_at       TIMESTAMP NOT NULL,
    enabled            BOOLEAN DEFAULT TRUE,
    skip_calendar      TEXT,         -- JSON array of CalendarRule
    business_hours_only BOOLEAN DEFAULT FALSE,
    version            BIGINT DEFAULT 0  -- optimistic concurrency
);

-- Tick ledger
CREATE TABLE workflow_schedule_ticks (
    tick_id            VARCHAR(64) PRIMARY KEY,
    schedule_id        VARCHAR(255) NOT NULL,
    workflow_name      VARCHAR(255) NOT NULL,
    scheduled_fire_at  TIMESTAMP NOT NULL,
    occurrence_index   BIGINT NOT NULL DEFAULT 0,
    owner_id           VARCHAR(255),
    claim_token        VARCHAR(255),
    claim_expires_at   TIMESTAMP,
    status             VARCHAR(32) NOT NULL,
    workflow_run_id    VARCHAR(255),
    terminal_reason    VARCHAR(1024),
    FOREIGN KEY (schedule_id) REFERENCES workflow_schedules(schedule_id),
    UNIQUE (schedule_id, scheduled_fire_at, occurrence_index)
);

-- Delay wakeups (bridges to tramai-orchestration delayStep)
CREATE TABLE workflow_delay_wakeups (
    run_id             VARCHAR(255) NOT NULL,
    step_id            VARCHAR(255) NOT NULL,
    resume_at          TIMESTAMP NOT NULL,
    status             VARCHAR(32) NOT NULL,  -- PENDING | CLAIMED | COMPLETED
    owner_id           VARCHAR(255),
    claim_token        VARCHAR(255),
    claim_expires_at   TIMESTAMP,
    PRIMARY KEY (run_id, step_id)
);
```

**Key design decisions in the JDBC store:**

- **`FOR UPDATE SKIP LOCKED`** — used in `claimDueTicks` and `claimDueDelayWakeups` to safely claim rows across concurrent timers without blocking. This is a PostgreSQL 9.5+ feature; MySQL 8.0+ and H2 also support it.
- **Upsert compatibility** — the store detects the database product name: H2 uses `MERGE INTO` syntax, other databases use `INSERT ... ON CONFLICT DO UPDATE`.
- **Calendar rule encoding** — `CalendarRule` objects are serialized to a lightweight JSON array string (no JSON library dependency). The store includes its own JSON parser (`splitJsonObjects`, `parseCalendarRuleObject`, `readJsonString`) for this purpose.
- **Tick deduplication** — `insertTickIfAbsent` uses `INSERT` with a unique constraint on `(schedule_id, scheduled_fire_at, occurrence_index)`. If the tick already exists (unique violation detected by SQL state `23505`), the insert is silently skipped.
- **Startup recovery** — `recover(now, limit)` scans enabled schedules with `next_fire_at <= now`, inserts a tick for each, and advances the schedule. It calls `observer.onMissedTick()` for every recovered tick so operators can track backlog. This is intentionally **not** part of the `WorkflowSchedulerStore` contract — it's a JDBC-specific extension for production environments.
- **Observation hooks** — the store accepts an optional `WorkflowObserver`. During tick claiming, skipped ticks (due to calendar rules) emit `onSkippedTick` events. During recovery, missed ticks emit `onMissedTick` events with a `scheduler_startup_recovery` reason.

#### 4. Delay wakeup mechanics

Delay wakeups are the bridge between `tramai-orchestration`'s `delayStep` and the scheduler. The flow:

```
workflow reaches delayStep(duration=1, unit=HOURS)
  ├── checkpoint is saved
  ├── WorkflowPersistence.delayWakeupScheduler.scheduleDelayWakeup(runId, stepId, resumeAt)
  │     └── WorkflowSchedulerStore.scheduleDelayWakeup(runId, stepId, resumeAt)
  │           └── INSERT INTO workflow_delay_wakeups (run_id, step_id, resume_at, status='PENDING')
  └── WorkflowSuspendedException is thrown → workflow yields

...later, timer polls...

ScheduledWorkflowTimer.pollOnce()
  └── store.claimDueDelayWakeups(now, ownerId, claimDuration, limit)
        └── SELECT ... FROM workflow_delay_wakeups
            WHERE resume_at <= ? AND (status = 'PENDING' OR ...)
            ORDER BY resume_at LIMIT ? FOR UPDATE SKIP LOCKED

  └── handleDelayWakeup(wakeup)
        ├── Find registration by runId (stored when workflow originally suspended)
        ├── Call registration.resume(context)
        │     └── Workflow.resume(persistence) → loads checkpoint → skips or executes delay step
        ├── If resumed successfully: markDelayWakeupCompleted
        └── If WorkflowSuspendedException again: releaseDelayWakeupClaim (the workflow re-checkpointed)
```

The delay wakeup's `status` transitions are:
- `PENDING` — waiting for the resume time
- `CLAIMED` — a timer has claimed the wakeup for processing
- `COMPLETED` — the workflow was successfully resumed (row is deleted on completion)

If the workflow is still not ready (its internal clock says the delay hasn't elapsed), the wakeup re-enters `PENDING` state and will be claimed again on the next poll.

#### 5. Timer loop internals (`ScheduledWorkflowTimer`)

`ScheduledWorkflowTimer` (270 LOC) runs a single coroutine loop:

```
start():
  scope.launch(start=LAZY) {
    while (isActive) {
      pollOnce()
      delay(pollInterval)  // default: 1 second
    }
  }

pollOnce():
  now = clock.instant()

  // 1. Claim schedule ticks
  ticks = store.claimDueTicks(now, ownerId, claimDuration, batchSize)
  for (tick in ticks):
    handleTick(tick, now)

  // 2. Claim delay wakeups (only if any delay registrations exist)
  if (hasDelayRegistrations()):
    wakeups = store.claimDueDelayWakeups(now, ownerId, claimDuration, batchSize)
    for (wakeup in wakeups):
      handleDelayWakeup(wakeup)

handleTick(tick, now):
  // Guard 1: claim expired during processing?
  if (!tick.claimExpiresAt.isAfter(now)) → releaseTickClaim; return

  // Guard 2: workflow registered?
  registration = registrationFor(tick.scheduleId)
  if (registration == null) → markTickSkipped("workflow_not_registered"); return

  // Guard 3: misfire threshold exceeded?
  if duration(tick.scheduledFireAt, now) > misfireThreshold:
    → markTickMisfired("misfire_threshold_exceeded"); return

  // Execute
  runId = context.workflowId
  observer.onScheduledTick(...)
  store.markTickStarted(tick.tickId, tick.claimToken, runId)
  registration.run(tick, context)
  store.markTickCompleted(tick.tickId, tick.claimToken)
```

**Key behaviors:**

- **Lazy start** — `start()` uses `CoroutineStart.LAZY` so the caller can prepare before the loop begins
- **Claim expiry guard** — if a tick's `claimExpiresAt` has passed before the timer processes it, the tick is released immediately (another timer may have already reclaimed it)
- **Unregistered workflow guard** — if a tick fires for a workflow that was removed from the in-memory registrations, it's skipped with `workflow_not_registered`
- **Misfire threshold** — configurable; defaults to 5 minutes. Ticks older than this are marked `MISFIRED` rather than executed, preventing backlog floods after downtime
- **Delay registration tracking** — when a workflow suspends (throws `WorkflowSuspendedException`), its registration is moved to `delayRegistrations` so the timer can route delay wakeups back to the correct workflow instance
- **Owner identity** — each timer instance generates a random `ownerId` (UUID) on construction, used for claim ownership tracking
- **Configurable scope** — callers can supply their own `CoroutineScope` for integration with existing supervision hierarchies

#### 6. `ScheduleStatusView` — status reporting

The store provides a `listScheduleStatus()` method that returns a snapshot of every schedule:

| Field | Description |
|-------|-------------|
| `scheduleId` | Unique schedule identifier |
| `workflowName` | Workflow name |
| `cronExpression` | The raw cron expression |
| `nextTick` | Next scheduled fire time |
| `lastTick` | Most recent tick's fire time |
| `lastRunStatus` | Status of the most recent run (`completed`, `skipped`, `misfired`) |
| `lastRunId` | Workflow run ID of the most recent execution |
| `misfireCount` | Total misfired ticks for this schedule |

In the JDBC store, this is computed via a single query that left-joins `workflow_schedules` with the latest tick and a misfire count subquery.

#### 7. Thread safety and concurrency model

| Component | Model |
|-----------|-------|
| `InMemoryWorkflowSchedulerStore` | `synchronized` on `this` for all mutations. `claimDueTicks` uses a retry loop: snapshot schedules, then atomically insert ticks + advance nextFireAt |
| `JdbcWorkflowSchedulerStore` | Per-connection transactions with `autoCommit=false`. Row-level locking via `FOR UPDATE SKIP LOCKED`. Unique constraints enforce tick idempotency |
| `ScheduledWorkflowTimer` | `synchronized(monitor)` for `registrations`, `delayRegistrations`, and `loopJob` access. Coroutine-based concurrency for the main loop |

The JDBC store's use of `SKIP LOCKED` is critical for multi-timer deployments — it avoids head-of-line blocking where one timer waits for another's lock to release on rows it doesn't need.

---

### Configuration reference

#### `ScheduledWorkflowTimer`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `store` | `WorkflowSchedulerStore` | *(required)* | Backend store for schedules and ticks |
| `ownerId` | `String` | random UUID | Identity used for claim ownership |
| `clock` | `Clock` | `Clock.systemUTC()` | Clock source for `now` |
| `pollInterval` | `Duration` | `1s` | Interval between `pollOnce()` calls |
| `claimDuration` | `Duration` | `30s` | Duration of claim ownership per tick |
| `misfireThreshold` | `Duration` | `5m` | Max delay before a tick is considered misfired |
| `batchSize` | `Int` | `50` | Max ticks/wakeups claimed per poll |
| `observer` | `WorkflowObserver` | `NoOpWorkflowObserver` | Observer for lifecycle events |
| `scope` | `CoroutineScope` | `SupervisorJob() + Dispatchers.Default` | Coroutine scope for the timer loop |

#### `CronSchedule`

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `expression` | `String` | *(required)* | 5- or 6-field cron expression |
| `zoneId` | `ZoneId` | system default | Time zone for schedule evaluation |
| `skipCalendar` | `List<CalendarRule>` | `emptyList()` | Dates to skip (holidays, etc.) |
| `businessHoursOnly` | `Boolean` | `false` | Only fire Mon–Fri 9 AM–6 PM |

#### DSL functions

| Function | Example | Description |
|----------|---------|-------------|
| `at(expr)` | `at("0 9 * * 1-5")` | Parse a cron expression with optional zone/calendar |
| `dailyAt(hour, minute)` | `dailyAt(9, 0)` | Every day at the given time |
| `every(amount, unit)` | `every(30, MINUTES)` | Fixed-interval schedule (sec/min/hr/day) |

#### `CalendarRule` types

| Type | Properties | Description |
|------|------------|-------------|
| `CalendarRule.FixedDate` | `month: Int`, `dayOfMonth: Int` | Skip a specific calendar date |
| `CalendarRule.NthWeekdayOfMonth` | `month: Int`, `nth: Int`, `dayOfWeek: DayOfWeek` | Skip Nth weekday of a month (e.g., 3rd Monday) |
| `CalendarRule.DateRange` | `startMonth/endMonth`, `startDayOfMonth/endDayOfMonth` | Skip a date range (supports year-wrapping) |
