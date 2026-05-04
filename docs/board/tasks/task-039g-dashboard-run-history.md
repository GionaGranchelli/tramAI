# TASK-039G: Dashboard Run History Slice

- Status: planned
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Parent task: [TASK-038](task-038.md)
- Last updated: 2026-05-04

## Purpose

Build the run history UI slice for the dashboard, comprising three Vue.js views:
a workflow list, a run list scoped to a workflow, and a run detail view with
step-by-step trace. All views apply redaction and payload truncation.

Built as part of the `tramai-dashboard` Vue.js 3 + Vite SPA.

## Scope

### Views (in `tramai-dashboard/src/main/frontend/src/views/`)

**WorkflowListView.vue**
- Paginated table of registered workflows (name, version, last run time, status summary)
- Data from `GET /workflows` (new endpoint or existing registry introspection)
- Click a row → navigates to `RunHistoryView`

**RunHistoryView.vue**
- Paginated table of runs for a selected workflow (run ID, status, duration, started-at, triggered-by)
- Data from `GET /workflows/{name}/runs` (existing REST endpoint)
- Filters: status, date range
- Sort by started-at descending (default)
- Click a row → navigates to `RunDetailView`

**RunDetailView.vue**
- Single-run view with step timeline, input/output per step, error traces, duration breakdown
- Data from `GET /workflows/{name}/runs/{id}` (existing REST endpoint)
- Live updates via SSE `EventSource` to `/workflows/{name}/runs/{id}/events`
- Expandable step blocks showing full input/output

### Components (in `tramai-dashboard/src/main/frontend/src/components/`)

**SearchableTable.vue** — PrimeVue DataTable with:
- Server-side pagination, sorting, filtering
- Column configuration from parent props
- Loading states and empty states

**StepTrace.vue** — PrimeVue Timeline showing:
- Step name, status badge (success/failure/running/pending)
- Duration per step
- Expandable input/output panels
- Error stack traces for failed steps

**PayloadViewer.vue** — handles:
- Redaction: sensitive fields marked with metadata annotation → `****`
- Truncation: payloads > 10 KB → `[truncated N bytes]` + "Download raw" link
- JSON pretty-printing for structured payloads

### Composables (in `tramai-dashboard/src/main/frontend/src/composables/`)

**useWorkflowApi.ts** — wrapped fetch calls:
- `fetchWorkflows()` → `GET /workflows`
- `fetchRuns(workflowName, params)` → `GET /workflows/{name}/runs`
- `fetchRunDetail(workflowName, runId)` → `GET /workflows/{name}/runs/{id}`

**useSSE.ts** — SSE subscription with auto-reconnect:
- `subscribeToRun(workflowName, runId)` → `EventSource` to events endpoint
- Exponential backoff on disconnect (1s, 2s, 4s, 8s, max 30s)
- Emits reactive Pinia store updates

### Stores (in `tramai-dashboard/src/main/frontend/src/stores/`)

**workflowStore.ts** — Pinia store:
- `workflows: Workflow[]` — loaded list
- `selectedWorkflow: string | null` — currently viewed workflow
- `loadWorkflows()` action
- `runs: Record<string, WorkflowRun[]>` — runs per workflow
- `loadRuns(workflowName, params)` action

**runStore.ts** — Pinia store:
- `currentRun: WorkflowRunDetail | null` — detail view data
- `sseConnected: boolean` — live update status
- `loadRunDetail(workflowName, runId)` action
- SSE events update `currentRun.steps[]` in-place

## Exit Criteria

- [ ] Workflow list loads and paginates with server-side query
- [ ] Run list filters by workflow, paginates, and sorts by started-at descending
- [ ] Run detail shows step-by-step timeline with expandable input/output
- [ ] Live SSE updates reflect step progress without page reload
- [ ] Sensitive fields marked with `@Secret` annotation are redacted (`****`)
- [ ] Payloads over 10 KB truncation limit show `[truncated N bytes]` with raw download link
- [ ] Empty states rendered for workflows with no runs
- [ ] Loading skeletons shown during API calls
- [ ] Tests cover: list loading, pagination, redaction patterns, truncation boundary
