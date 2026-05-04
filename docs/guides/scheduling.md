# Workflow Scheduling

`tramai-scheduler` adds durable time-based execution to explicit Tramai workflows.

This module sits above `tramai-orchestration`. It does not replace the workflow DSL; it decides when a workflow run should start or resume.

## What It Gives You

- cron-backed workflow schedules
- timezone-aware execution
- skip calendars and business-hours filters
- durable tick claiming through `WorkflowSchedulerStore`
- delay-step wakeups for suspended workflows
- misfire handling and observer events

## When To Add It

Add `tramai-scheduler` when one of these is true:

- a workflow must run on a clock rather than from application code
- a delayed workflow must resume after a persisted timer
- you need schedule state in JDBC instead of an external cron wrapper

If you only need `workflow.run(...)` from application code, stay with `tramai-orchestration`.

## Schedule DSL

The current scheduling API is cron-based.

### `at(...)`

Use `at(...)` when you already know the cron expression:

```kotlin
val schedule = at(
    expression = "0 9 * * 1",
    zone = "Europe/Rome",
)
```

Current code supports:

- five-field cron expressions: minute, hour, day-of-month, month, day-of-week
- six-field expressions with seconds as the first field
- `ZoneId` or zone string overloads

### `dailyAt(...)`

Use `dailyAt(...)` for one fixed local time:

```kotlin
val schedule = dailyAt(
    hour = 9,
    minute = 30,
    zone = "Europe/Rome",
)
```

### `every(...)`

Use `every(...)` for simple recurring intervals:

```kotlin
val fiveMinutes = every(5, ChronoUnit.MINUTES, zone = "UTC")
val hourly = every(1, ChronoUnit.HOURS, zone = "UTC")
```

Current supported units:

- `SECONDS`
- `MINUTES`
- `HOURS`
- `DAYS`

Important caveat from code and spec:

- `every(N, DAYS)` is implemented with cron day-of-month stepping
- that means it resets at month boundaries
- it is not a true continuous “every N days” timer

## Calendar And Business-Hour Policies

The schedule helpers accept:

- `skipCalendar = listOf(...)`
- `businessHoursOnly = true`

Example:

```kotlin
val weekdayMorning = dailyAt(
    hour = 9,
    minute = 0,
    zone = "Europe/Rome",
    skipCalendar = listOf(FixedDate(12, 25)),
    businessHoursOnly = true,
)
```

Current behavior to know:

- business-hours mode is a post-cron adjustment
- when a cron fire lands outside business hours, the scheduler returns the next business-hour start directly
- that adjusted time is not revalidated against the cron expression before returning

That is intentional in the current implementation and should be treated as part of the contract for now.

## Delay Steps

Scheduling is also what makes `delayStep(...)` useful across process boundaries.

```kotlin
workflow<MyState>("invoice-follow-up") {
    localStep("prepare") { state, _ -> state.copy(prepared = true) }
    delayStep("wait-15-minutes", duration = 15, unit = TimeUnit.MINUTES)
    aiStep(
        name = "follow-up",
        input = { state -> state.prompt },
        invoke = followUpService::send,
        merge = { state, result -> state.copy(result = result) },
    )
}
```

Current runtime behavior:

- the workflow checkpoints itself and throws `WorkflowSuspendedException`
- the scheduler store records a delay wakeup
- a later scheduler poll resumes the workflow from persisted state

## Timer Model

The current runtime entry point is `ScheduledWorkflowTimer`.

It:

- registers workflows that declare a schedule
- writes schedule state to `WorkflowSchedulerStore`
- polls for due ticks
- claims work with fencing tokens
- starts new workflow runs
- resumes delayed runs when their wakeups mature

## Persistence Model

Two levels matter:

1. workflow state persistence from `tramai-orchestration`
2. schedule/tick persistence from `tramai-scheduler`

Typical durable setup:

- `WorkflowPersistence` for checkpoints and optional leases
- `JdbcWorkflowSchedulerStore` for schedule ticks and delay wakeups

The scheduler module does not hide those boundaries. You still choose the persistence strategy explicitly.

## Misfires

The scheduler compares the scheduled fire time to `misfireThreshold`.

Current default in `ScheduledWorkflowTimer`:

- poll interval: `1s`
- claim duration: `30s`
- misfire threshold: `5m`
- batch size: `50`

When a claimed tick is too old, the timer marks it as misfired and emits observer callbacks instead of running the workflow.

## Observer Surface

The workflow observer already includes scheduler hooks:

- `onScheduledTick(...)`
- `onSkippedTick(...)`
- `onMissedTick(...)`

That keeps scheduling observable without forcing an OpenTelemetry dependency.

## What The Module Does Not Do

Current non-goals and practical limits:

- it does not provide distributed cron coordination across multiple scheduler nodes
- it currently supports cron schedules only
- it does not offer runtime editing of workflow definitions
- it does not give you true continuous “every N days” semantics

## Related Pages

- [Orchestration](./orchestration.md)
- [Orchestration Persistence](./orchestration-persistence.md)
- [SPEC-013: Workflow Scheduling](../specs/spec-013-scheduler.md)
