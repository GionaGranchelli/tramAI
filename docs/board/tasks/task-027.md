# TASK-027: Add Durable Scheduling with JDBC Checkpoint Integration

- Status: planned
- Priority: high
- Primary spec: [SPEC-013](../../specs/spec-013-scheduler.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Make scheduled workflow execution durable across JVM restarts by persisting
the next expected fire time in the JDBC checkpoint store.

## Scope

- Add `next_fire_time` column to the workflow checkpoint table
- On successful tick execution, update `next_fire_time` to the next schedule
- On startup, scan for workflows with `next_fire_time <= now()` and execute
  any missed ticks
- Handle missed ticks gracefully (emit `onMissedTick` event, don't catch up
  by firing past ticks — just schedule the next one)
- Recovery: re-arm all pending schedules within 5 seconds at startup

## Exit Criteria

- [ ] Scheduled workflow survives JVM restart and fires at the next expected time
- [ ] Recovery scans and re-arms up to 500 workflows within 5 seconds
- [ ] Missed ticks emit `onMissedTick` events with time delta
- [ ] Manual `workflow.run()` does not interfere with scheduled execution
