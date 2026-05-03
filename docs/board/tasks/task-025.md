# TASK-025: Implement Cron Schedule DSL and In-Process Timer

- Status: done
- Priority: high
- Primary spec: [SPEC-013](../../specs/spec-013-scheduler.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Add inline schedule declarations to the workflow builder and an in-process
timer that fires workflow runs at the specified times.

## Scope

- Extend `WorkflowBuilder` with `schedule` parameter
- Parse cron expressions (standard 5- or 6-field, with seconds optional)
- Cron DSL: `at()` for expressions, `every()` for intervals, `dailyAt()` for daily times
- Timezone-aware scheduling via `ZoneId`
- Add in-process `ScheduledWorkflowTimer` that polls for due workflows
- Misfire policy: `FIRE_ONCE` for cron, `SKIP` for high-frequency intervals
- Integrate with `WorkflowObserver` for scheduled, skipped, missed, and completed tick events
- Validate schedule expressions at workflow build time
- DST-aware via IANA timezone rules

## Exit Criteria

- [x] A workflow with `at("0 9 * * 1")` fires at 9 AM Monday
- [x] Invalid cron expressions are rejected at build time
- [x] Timer emits `onScheduledTick` observer events
- [x] Timer stops gracefully on workflow shutdown
- [x] Tests cover: basic cron, every-N-seconds for testing, edge-second alignment
- [x] Workflow builder validates assigned schedule at registration time
- [x] Timer handles missed ticks with misfire policy
- [x] Timer resumes workflows from due delay wakeups
