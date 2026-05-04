# TASK-039H: Dashboard Worker and Schedule Views

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-038](task-038.md)
- Last updated: 2026-05-04

## Purpose

Build the worker and schedule management views for the dashboard. The worker list
shows registered runtime workers and their health via real-time SSE. The schedule
list shows cron-style workflows and upcoming ticks.

Built as part of the `tramai-dashboard` Vue.js 3 + Vite SPA.

## Scope

### Views (in `tramai-dashboard/src/main/frontend/src/views/`)

**WorkerListView.vue**
- Table of registered workers with: worker ID, status (online/offline), last heartbeat,
  active run count, version, pool name, capability labels
- Data from `GET /workers` (new REST endpoint in `tramai-server`)
- Real-time status updates via SSE — worker online/offline events push to the UI
- Status badge component: green pulse for online (< 30s since heartbeat), yellow (30-60s), red (> 60s)
- Stale worker detection: if last heartbeat > configured timeout, marked as offline

**ScheduleListView.vue**
- Table of scheduled workflows with: workflow name, cron expression, next tick time,
  last tick time, last run status
- Data from `GET /schedules` (new REST endpoint in `tramai-server`)
- Upcoming ticks panel: visual timeline of the next 10 scheduled events across all schedules,
  ordered chronologically
- SSE subscription for schedule tick events (schedule fired, schedule misfired)

### Components (in `tramai-dashboard/src/main/frontend/src/components/`)

**WorkerStatusBadge.vue**
- Pulse animation for online workers
- Stale threshold indicator
- Tooltip showing last heartbeat time and active run count

**CalendarHeatmap.vue** (replaces calendar view for upcoming ticks)
- Horizontal timeline showing next 10 scheduled events
- Color-coded by workflow type
- Tooltip with workflow name and exact tick time

### Composables

**useWorkerApi.ts**
- `fetchWorkers()` → `GET /workers`
- Polling fallback if SSE is unavailable: re-fetch every 5s

**useScheduleApi.ts**
- `fetchSchedules()` → `GET /schedules`
- `fetchAuditLog(params)` → `GET /audit` with filters

**useSSE.ts** (shared with TASK-039G)
- Extended with `subscribeToWorkers()` → SSE endpoint for worker events
- Extended with `subscribeToSchedules()` → SSE endpoint for schedule tick events
- Auto-reconnect with exponential backoff on all streams

### Stores (in `tramai-dashboard/src/main/frontend/src/stores/`)

**workerStore.ts** — Pinia store:
- `workers: WorkerInfo[]` — loaded list with computed `status` (online/offline/stale)
- `sseConnected: boolean`
- SSE events: `workerOnline` adds/updates, `workerOffline` marks stale

**scheduleStore.ts** — Pinia store:
- `schedules: ScheduleInfo[]` — loaded list
- `upcomingTicks: ScheduledTick[]` — next 10 events, computed sorted
- SSE event: `scheduleTick` updates `lastTickTime` and `lastRunStatus`

### Backend additions (in `tramai-server`)

New REST endpoints to be implemented alongside the dashboard:

**GET /workers**
```json
{
  "workers": [
    {
      "workerId": "node-1",
      "status": "online",
      "poolName": "default",
      "capabilityLabels": {"gpu": "true", "region": "eu"},
      "version": "1.0.0",
      "host": "10.0.1.5",
      "lastHeartbeat": "2026-05-04T12:00:00Z",
      "activeRunCount": 3,
      "draining": false
    }
  ]
}
```

**GET /schedules**
```json
{
  "schedules": [
    {
      "workflowName": "invoice-processor",
      "cronExpression": "0 */6 * * *",
      "nextTick": "2026-05-04T18:00:00Z",
      "lastTick": "2026-05-04T12:00:00Z",
      "lastRunStatus": "completed",
      "lastRunId": "wf-abc123",
      "misfireCount": 0
    }
  ]
}
```

**SSE endpoints:**
- `GET /workers/events` — pushes `workerOnline`, `workerOffline` events
- `GET /schedules/events` — pushes `scheduleTick`, `scheduleMisfire` events

These endpoints use the `WorkerRegistryStore` SPI (from TASK-037) and the
`tramai-scheduler` module's schedule tracking data.

## Exit Criteria

- [ ] Worker list displays all registered workers with real-time status indicators
- [ ] Worker status badge shows green/yellow/red based on heartbeat freshness
- [ ] Schedule list shows every registered schedule with cron expression and next/previous tick
- [ ] Upcoming ticks panel renders the next 10 scheduled events in chronological order
- [ ] SSE endpoint pushes worker online/offline events to subscribed clients
- [ ] SSE endpoint pushes schedule tick events to subscribed clients
- [ ] SSE connection auto-reconnects on disconnect with exponential backoff
- [ ] Worker list gracefully degrades to polling if SSE is unavailable
- [ ] Empty states rendered for deployments with no workers registered
- [ ] Tests cover: worker list query, schedule list query, SSE event delivery
