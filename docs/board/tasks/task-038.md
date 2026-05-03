# TASK-038: Implement Admin Dashboard

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Build a web-based admin dashboard for monitoring and managing workflow
executions across the TramAI deployment.

## Scope

- Vue.js SPA (or server-rendered HTMX if SPA scope is too large)
- Workflow list page: all registered types with schedule, version, last run
- Run history page: searchable table with status, duration, worker, trigger
- Run detail page: step-by-step trace with timing, I/O, retries, error context
- Worker list page: registered workers, heartbeats, lease counts
- Schedule calendar: upcoming scheduled runs
- Settings page: API key management, webhook config
- Real-time updates via SSE connection
- REST API consumed from TramaiServer (SPEC-014 / TASK-029)

## Exit Criteria

- [ ] Dashboard shows all registered workflow types with status
- [ ] Run history is searchable by workflow name, status, and date range
- [ ] Run detail page shows each step with timing, input, and output
- [ ] Worker list shows all registered workers with heartbeat freshness
- [ ] SSE-connected live view updates when a workflow progresses
- [ ] Dashboard loads in < 2 seconds for up to 1000 runs
