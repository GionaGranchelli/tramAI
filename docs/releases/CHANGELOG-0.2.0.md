# 0.2.0 — Orchestrator Platform

## New Modules

### tramai-server
REST API server for workflow management. Trigger workflows via HTTP endpoints, receive webhooks, stream live traces via SSE, and serve an optional admin dashboard.

- `POST /workflows/{name}/run` — trigger workflow execution
- `POST /webhooks/{name}` — receive GitHub-style webhooks with signature verification
- `POST /workflows/{name}/runs/{id}/resume` — resume suspended workflows
- `GET /workflows/{name}/runs` — list runs with pagination
- `GET /workflows/{name}/runs/{id}` — get run detail with step history
- `GET /workflows/{name}/runs/{id}/events` — SSE streaming for live traces
- `DELETE /workflows/{name}/runs/{id}` — cancel running workflows
- `GET /openapi.json` — generated OpenAPI 3.1 route document
- Idempotency-key support for exactly-once execution
- Spring Boot auto-configuration with `@ConditionalOnProperty`
- webhook HMAC verification and request-body limits

### tramai-scheduler
Cron-based workflow scheduling with durable storage.

- Cron DSL with timezone and calendar-aware scheduling
- Delay step support for time-based workflow suspension
- JDBC-backed durable scheduling with PostgresSQL migrations
- Skip calendars and business-hours-only scheduling
- Misfire detection and recovery

### tramai-orchestration
Distributed workflow execution engine.

- Worker pool with lease-based work stealing
- Workflow checkpoint persistence (JDBC, in-memory, file system)
- Lease fencing for crash safety
- Worker registry with heartbeat-based health tracking
- Graceful shutdown with in-flight run draining

### tramai-platform (new)
Multi-tenancy and plugin system.

- Tenant model with isolated workflow registries
- API key authentication with BCrypt hashing
- Rate limiting with time-aware token bucket
- Plugin system with runtime registry
- Audit logging with paginated query API

### tramai-dashboard (new)
Vue.js 3 admin dashboard following the Spring Boot Admin pattern.

- 7 pages: Workflow list, run history, run detail, worker list, schedule list, audit log, settings
- Vue 3 + Vite + TypeScript, built with Gradle node-gradle plugin
- SSE-powered live worker status updates
- Optional module — add to classpath to enable, absent → headless server
- Dynamic runtime configuration via `tramai-settings.js`
- served under `/dashboard/**` in the current implementation

### tramai-mcp (new)
MCP server adapter exposing workflows as tools to AI agents.

- Full MCP protocol support (tools/list, tools/call)
- stdio and HTTP+SSE transports
- Workflow-aware tool schema generation
- Security matrix for transport-level access control

## Backend Endpoints (new)
- `GET /workflows` — list registered workflow names
- `GET /workers`, `GET /workers/events` — worker monitoring with SSE
- `GET /schedules`, `GET /schedules/events` — schedule monitoring with SSE
- `GET /audit` — paginated audit log with actor/action filters
- `GET /openapi.json` — OpenAPI 3.1 spec auto-generated from registered workflows

Note: in the current codebase, the worker, schedule, and audit endpoints are grouped behind the dashboard feature flag.

## What Changed Since 0.1.0

| Module | 0.1.0 | 0.2.0 |
|--------|-------|-------|
| tramai-core | Workflow DSL, engine | Unchanged |
| tramai-structured | JSON/Pydantic output | Unchanged |
| tramai-anthropic | Anthropic provider | Unchanged |
| tramai-ollama | Ollama provider | Unchanged |
| tramai-openai | OpenAI provider | Unchanged |
| tramai-spring | Spring Boot starter | Unchanged |
| tramai-testing | Test utilities | Unchanged |
| tramai-server | — | **NEW** REST API server |
| tramai-scheduler | — | **NEW** cron scheduling |
| tramai-orchestration | — | **NEW** distributed workers |
| tramai-platform | — | **NEW** multi-tenancy |
| tramai-dashboard | — | **NEW** admin dashboard |
| tramai-mcp | — | **NEW** MCP adapter |

## Test Results
- 72 test tasks across 15 modules — all passing
- `publishToMavenLocal` — 14 modules published
- Example project smoke test — passing
- Dashboard Vite build — 51 modules, 19 frontend assets in JAR
