# TASK-039H: Dashboard Worker and Schedule Views

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-039](../tasks/task-039.md)
- Last updated: 2026-05-03

## Purpose

Build the worker and schedule management views for the dashboard. The worker list shows registered runtime workers and their health, the schedule list shows cron-style workflows and upcoming ticks, and live updates are pushed via SSE (Server-Sent Events).

## Scope

- Worker list: table of registered workers with worker ID, status (online/offline), last heartbeat, active run count, version
- Schedule list: table of scheduled workflows with name, cron expression, next tick time, last tick time, last run status
- Upcoming ticks panel: visual timeline of the next N scheduled ticks across all schedules
- Live updates via SSE — worker status changes and tick events push to the UI without polling

## Exit Criteria

- [ ] Worker list displays all registered workers with real-time status indicators
- [ ] Schedule list shows every registered schedule with cron expression and next/previous tick
- [ ] Upcoming ticks panel renders the next 10 scheduled events in chronological order
- [ ] SSE endpoint pushes worker online/offline and schedule tick events to subscribed clients
- [ ] SSE connection is resilient — clients reconnect automatically on disconnect
- [ ] Tests cover: worker list query, schedule list query, SSE event delivery
