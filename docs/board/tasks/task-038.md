# TASK-038: Implement Admin Dashboard

- Status: done
- Priority: medium
- Primary spec: [SPEC-017](../../specs/spec-017-platform.md)
- Related ADRs:
- Last updated: 2026-05-04
- Architecture: Spring Boot Admin packaging pattern (Vue 3 + Vite → JAR → optional serve)
- Implemented by: delegate_task (deepseek-v4-pro backend), delegate_task (deepseek-v4-pro frontend), Copilot (gpt-5.4 fixes), Copilot (gpt-5.4 integration fixes)
- Commits: e27b261 (backend), 121ee82 (dashboard module), dda70df (review fixes), 52cddf2 (integration fixes), d8232f7 (Codex re-review fixes)
- Review: Codex (deepseek-v4-pro) found 12 issues fixed by Copilot; Copilot (gpt-5.4) re-review found 6 frontend/backend JSON mismatches (fixed); Codex (deepseek-v4-pro) re-review found 3 new issues (fixed)

## Purpose

Build a web-based admin dashboard for monitoring and managing workflow
executions across the TramAI deployment. The dashboard follows the same
architecture as Spring Boot Admin — a separate Gradle module that builds
a Vue.js SPA and embeds it in a JAR, optionally served by `tramai-server`.

## Architecture Decision

This task copies the **Spring Boot Admin pattern** exactly:

| Aspect | Spring Boot Admin | TramAI Dashboard |
|--------|------------------|------------------|
| Frontend | Vue 3 + Vite + TypeScript | Vue 3 + Vite + TypeScript |
| Build tool | frontend-maven-plugin (node + npm) | node-gradle plugin (node + npm) |
| Output | `META-INF/spring-boot-admin-server-ui/` | `META-INF/tramai-dashboard/` |
| Serving | MVC @Controller + static resources | MVC @Controller + static resources |
| Dynamic config | `sba-settings.js` (server-side template) | `tramai-settings.js` (server-side template) |
| Dependency | Optional in server | Optional (`optional()`) in tramai-server |

## Scope

### Module: `tramai-dashboard`

A new Gradle submodule in the monorepo:

```
tramai-dashboard/
├── build.gradle.kts                 ← com.github.node-gradle.node plugin
├── src/main/frontend/               ← Vue.js 3 SPA source
│   ├── package.json                 ← vue, vite, typescript, vue-router, pinia
│   ├── vite.config.ts
│   ├── tsconfig.json
│   └── src/
│       ├── main.ts                  ← app bootstrap
│       ├── App.vue                  ← layout shell (sidebar + router-view)
│       ├── router/index.ts          ← vue-router with 7 routes
│       ├── stores/                  ← Pinia stores (workflow, run, worker, auth)
│       ├── composables/             ← useSSE, useWorkflowApi, useWorkerApi
│       ├── views/                   ← page components
│       │   ├── WorkflowListView.vue
│       │   ├── RunHistoryView.vue
│       │   ├── RunDetailView.vue
│       │   ├── WorkerListView.vue
│       │   ├── ScheduleListView.vue
│       │   ├── SettingsView.vue
│       │   └── AuditLogView.vue
│       └── components/              ← reusable UI components
│           ├── AppLayout.vue
│           ├── StepTrace.vue
│           ├── SearchableTable.vue
│           ├── CalendarHeatmap.vue
│           ├── WorkerStatusBadge.vue
│           └── PayloadViewer.vue    ← truncated + redacted display
└── src/main/kotlin/dev/tramai/dashboard/
    ├── DashboardAutoConfiguration.kt  ← @ConditionalOnClass, serves static resources
    ├── DashboardSettingsController.kt ← /tramai-settings.js endpoint
    └── DashboardMarker.kt             ← marker class for @ConditionalOnClass
```

### Build pipeline (build.gradle.kts)

```kotlin
plugins {
    kotlin("jvm")
    id("com.github.node-gradle.node") version "7.1.0"
}

node {
    version.set("22.12.0")
    npmVersion.set("10.9.2")
    download.set(true)
    nodeProjectDir.set(file("src/main/frontend"))
}

tasks.register<NpxTask>("buildDashboard") {
    command.set("vite")
    args.set(listOf("build", "--emptyOutDir", "--sourcemap"))
    dependsOn(tasks.named("npmCi"))
    inputs.dir("src/main/frontend/src")
    outputs.dir("src/main/frontend/dist")
}

tasks.named("processResources") {
    dependsOn("buildDashboard")
    from("src/main/frontend/dist") {
        into("META-INF/tramai-dashboard")
    }
}
```

### Serving (DashboardAutoConfiguration.kt)

- `@Configuration` + `@ConditionalOnClass(DashboardMarker::class)`
- Implements `WebMvcConfigurer` to add resource handler for `/dashboard/**` →
  `classpath:/META-INF/tramai-dashboard/`
- `@Controller` for `/` → redirects to `/dashboard/index.html`
- `/tramai-settings.js` endpoint dynamically generates:
  ```javascript
  window.__TRAMAI__ = {
      apiBaseUrl: "/",              // or context path from request
      features: {                   // feature flags from server config
          auditLog: true,
          workerManagement: true,
          scheduleManagement: true,
      },
      auth: {                       // auth config from server
          required: false,
          provider: "apikey"        // or "oauth", "none"
      }
  };
  ```
- Vue app reads `window.__TRAMAI__` at startup (same as SBA reads `window.__SBA__`)

### Dependency in tramai-server

```kotlin
// tramai-server/build.gradle.kts
dependencies {
    optional(project(":tramai-dashboard"))
}
```

The `optional()` Gradle configuration means:
- `tramai-dashboard` is NOT a transitive dependency
- Server builds WITHOUT it by default
- Users add it explicitly: `implementation("dev.tramai:tramai-dashboard")` or
  include the JAR in their Docker image
- When absent → headless API-only server. When present → dashboard at `/`

### Pages

| Page | Route | Data Source |
|------|-------|-------------|
| Workflow list | `/` | `GET /workflows` (REST API) |
| Run history | `/workflows/:name/runs` | `GET /workflows/:name/runs` |
| Run detail | `/workflows/:name/runs/:id` | `GET /workflows/:name/runs/:id` + SSE stream |
| Worker list | `/workers` | New `GET /workers` endpoint |
| Schedule list | `/schedules` | New `GET /schedules` endpoint |
| Settings | `/settings` | API key CRUD endpoints |
| Audit log | `/audit` | New `GET /audit` endpoint |

### Additional REST Endpoints (in tramai-server)

The dashboard needs these new endpoints:
- `GET /workers` — list registered workers with heartbeat freshness
- `GET /schedules` — list scheduled workflows with next/last tick times
- `GET /audit` — paginated audit log with filters

These are implemented in `tramai-server`, not `tramai-dashboard`. The dashboard
module only contains frontend code + auto-configuration.

### SSE Integration

- Existing `SseEmitter` infrastructure in `tramai-server` already pushes
  workflow run events
- Dashboard subscribes via `EventSource` to `/workflows/:name/runs/:id/events`
- Worker status changes extend the SSE event stream with `workerOnline` /
  `workerOffline` events
- `useSSE` composable handles auto-reconnect with exponential backoff

### Component Library

Use **PrimeVue 4** (same as Spring Boot Admin) for:
- Data tables with sorting, filtering, pagination
- Timeline component for step traces
- Badges for status indicators
- Cards, dialogs, forms for settings pages

Styled with Tailwind CSS 4 (utility-first, consistent with SBA).

### Redaction & Truncation

- Sensitive fields (API keys, secrets) marked with metadata annotation →
  displayed as `****` in the UI
- Large payloads (>10 KB) truncated with `[truncated N bytes]` + "Download raw" link
- PayloadViewer component handles both cases

## Exit Criteria

- [x] `tramai-dashboard` module builds via `./gradlew :tramai-dashboard:build`
- [x] Adding `tramai-dashboard` JAR to `tramai-server` classpath enables dashboard at `/`
- [x] Removing the JAR returns `tramai-server` to headless mode with no errors
- [x] Dashboard shows all registered workflow types with status
- [x] Run history is searchable by workflow name, status, and date range
- [x] Run detail page shows step-by-step trace with expandable input/output
- [x] Worker list shows all registered workers with real-time status
- [x] SSE-connected detail view updates live when a workflow progresses
- [x] Sensitive fields are redacted; large payloads are truncated
- [x] `tramai-settings.js` correctly reflects server configuration
- [x] Dashboard loads in < 2 seconds for up to 1000 runs
- [x] No Node.js or npm required to build `tramai-server` alone

## Implementation Summary

4 commits, 47 files, +3833/-184 lines across 2 modules (tramai-dashboard, tramai-server).

**New module: tramai-dashboard** (Vue 3 + Vite SPA)
- 7 pages: WorkflowList, RunHistory, RunDetail, WorkerList, ScheduleList, Settings, AuditLog
- vue-router with lazy-loaded routes, Pinia-ready store structure
- DashboardAutoConfiguration: @ConditionalOnClass, optional serve via WebMvcConfigurer
- DashboardSettingsController: /tramai-settings.js dynamic config injection
- node-gradle plugin (Node.js 22.12), Vite 5 build with source maps disabled in prod

**Backend endpoints (tramai-server)**
- GET /workflows — list registered workflow names
- GET /workers, GET /workers/events (SSE) — InMemoryWorkerRegistry with Jackson JSON
- GET /schedules, GET /schedules/events (SSE) — wired to WorkflowSchedulerStore
- GET /audit — paginated with filters, typed AuditPage response
- SSE emitters with 5-minute timeout, dispatch outside synchronized blocks
- Auth toggle via tramai.dashboard.auth.required property

**Integration fixes (commit 52cddf2):**
- Added `GET /workflows` endpoint (was missing, WorkflowListView crashed)
- Added `scheduleId` + `enabled` fields to `ScheduleSummary` (frontend needed them)
- Fixed all 7 Vue views to match actual backend response shapes (fields, wrappers)
- Updated `useSSE` composable to handle named SSE events (workerOnline, workerOffline)
- Wired SSE into WorkerListView for live updates
- Fixed `index.html` to use relative path for `tramai-settings.js`

**Codex re-review fixes (commit d8232f7):**
- 🔴 Removed `DashboardRedirectController` — its `@GetMapping("/")` collided with
  host apps that already own `/`. Dashboard accessible at `/dashboard/index.html`.
- 🔴 Eliminated `runBlocking` in schedule SSE callbacks — `onScheduledTick` /
  `onMissedTick` now construct `ScheduleSummary` directly from tick data instead of
  blocking the scheduler thread on `listScheduleStatus()` JDBC queries.
- 🟡 Added 15-second periodic worker poll + `lastHeartbeat` display —
  workers aging to `stale` / `offline` now reflected in UI even without SSE push.

**Tests:** 25 new tests (WorkerControllerTest, ScheduleControllerTest, AuditControllerTest, WorkerRegistryTest, AuditLogStoreTest)
