# TASK-025: Implement Cron Schedule DSL and In-Process Timer

- Status: planned
- Priority: high
- Primary spec: [SPEC-013](../../specs/spec-013-scheduler.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Add inline schedule declarations to the workflow builder and an in-process
timer that fires workflow runs at the specified times.

## Scope

- Extend `WorkflowBuilder` with `schedule` parameter
- Parse cron expressions (standard 5- or 6-field)
- Add in-process `ScheduledWorkflowTimer` that polls for due workflows
- Integrate with `WorkflowObserver` for tick events
- Validate schedule expressions at workflow build time

## Exit Criteria

- [ ] A workflow with `at("0 9 * * 1")` fires at 9 AM Monday
- [ ] Invalid cron expressions are rejected at build time
- [ ] Timer emits `onScheduledTick` observer events
- [ ] Timer stops gracefully on workflow shutdown
- [ ] Tests cover: basic cron, every-N-seconds for testing, edge-second alignment
