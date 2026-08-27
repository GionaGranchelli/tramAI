# Module: `tramai-server`

> **One-liner:** HTTP REST API surface for Tramai workflows — start, inspect, resume, cancel, and stream runs; receive webhooks; monitor workers and schedules; query audit logs.

---

> **Classification / layer / maturity / publishability / release:** see [`config/quality/module-catalog.yml`](../../config/quality/module-catalog.yml) and the [module matrix](../../docs/reference/module-matrix.md)

## Architecture

### Responsibility

Spring Boot server: workflow/worker/audit/schedule HTTP endpoints, webhook verification, request-size limits, in-memory stores.

### Public entry points

This module is **internal** (manifest: `publishability: internal`, `apiStability: internal`, `releaseInclusion: internal_only`) — there is **no published consumer API**. The following are repository-facing JVM-public entry points only (visible in `tramai-server/api/tramai-server.api`, not for external consumption):

- `TramaiServerApplication` — server entry point
- Controllers: `WorkflowController`, `WorkerController`, `AuditController`, `ScheduleController`
- `GitHubWebhookSignatureVerifier`, `RequestBodySizeLimitFilter`, `ReplayCache`
- Stores: `InMemoryAuditLogStore`, `InMemoryWorkerRegistry`
- Exceptions: `BadWorkflowRequestException`, `InvalidWebhookSignatureException`, `RequestBodyTooLargeException`

### Internal extension points

- Audit/worker-registry store implementations

### Significant dependencies

- `api(tramai-orchestration)`; `implementation(tramai-scheduler)`; Spring web/validation, Jackson, coroutines (implementation) — see [module-catalog.yml](../../config/quality/module-catalog.yml)

### Lifecycle ownership

- Spring context lifecycle; server start/stop via the Spring Boot application

### Thread-safety and concurrency

- Spring singletons; controllers must be safe for concurrent HTTP requests

### Failure semantics

- Invalid requests surface as typed HTTP error responses; signature failures are explicit

### Contract tests / TCKs

- `WorkflowControllerTest`, `WorkerControllerTest`, `AuditControllerTest`, `ScheduleControllerTest`, `WorkerRegistryTest`, `AuditLogStoreTest`

### Do not

- Do not add provider adapters here — server is a hosting surface

### Related architecture

- [ARCHITECTURE.md](../../ARCHITECTURE.md) — operations-observability layer

## L1: Quick Start (30-second read)

### What

`tramai-server` wraps a running Tramai application in a **Spring Boot HTTP server**. It exposes every registered workflow as a REST endpoint so that external systems — CLI tools, dashboards, CI/CD pipelines, other microservices — can start, inspect, resume, cancel, and observe workflow runs without importing any Tramai library or sharing a JVM process.

Beyond core workflow lifecycle, the module also provides:

- **Webhook receiver** — start a workflow from a verified external payload (e.g., GitHub webhooks with HMAC signature verification)
- **SSE streaming** — subscribe to live per-run event feeds using `Server-Sent Events`
- **Worker registry** — track distributed worker pool status (online, stale, offline) via REST + SSE
- **Schedule overview** — list cron-triggered schedules and their next/last fire times via REST + SSE
- **Audit log** — query an append-only, in-memory audit trail of API operations
- **OpenAPI document** — auto-generated `GET /openapi.json` reflecting all registered workflows and their routes
- **Optional dashboard** — when `tramai-dashboard` is on the classpath, the admin SPA is served at `/`

### Why

Workflows defined with `tramai-orchestration` run inside a JVM process. Without `tramai-server`, they are invisible to the outside world. You need this module when:

- **External triggers** — a CI/CD hook, a GitHub webhook, or a cron tick needs to start a workflow
- **Cross-language callers** — Python, TypeScript, or Go services need to kick off a Tramai workflow
- **Operational visibility** — operators need to see what's running, cancel stuck runs, or replay failed ones
- **Real-time monitoring** — you need to stream workflow progress to a dashboard or a log aggregator
- **Distributed execution** — you need to observe remote workers and their health from a central API

`tramai-server` is the **operational layer** above `tramai-orchestration` and `tramai-scheduler`. It does not re-define workflow semantics — it exposes them over HTTP.

### When to use

| You need this | Use `tramai-server` |
|---|---|
| Start workflows from outside the JVM | ✅ `POST /workflows/{name}/run` |
| React to GitHub/Slack/external webhooks | ✅ `POST /webhooks/{name}` with signature verification |
| Stream run progress to a UI | ✅ `GET /workflows/{name}/runs/{id}/events` (SSE) |
| Cancel a stuck run remotely | ✅ `DELETE /workflows/{name}/runs/{id}` |
| Resume a suspended/delayed run | ✅ `POST /workflows/{name}/runs/{id}/resume` |
| List all registered workflows / runs | ✅ `GET /workflows`, `GET /workflows/{name}/runs` |
| Monitor distributed worker health | ✅ `GET /workers`, `GET /workers/events` (SSE) |
| View schedule status | ✅ `GET /schedules`, `GET /schedules/events` (SSE) |
| Audit API operations | ✅ `GET /audit` |

### How to add

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation("dev.tramai:tramai-server")  // version from the TramAI BOM
}
```

**Bill of Materials:**

```kotlin
// tramaiVersion is the canonical version property (see gradle.properties)
val tramaiVersion: String by project
implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))
implementation("dev.tramai:tramai-server")
```

**Using the BOM with Spring Boot:**

```kotlin
plugins {
    id("org.springframework.boot")
}

dependencies {
    val tramaiVersion: String by project
    implementation(platform("dev.tramai:tramai-bom:$tramaiVersion"))
    implementation("dev.tramai:tramai-server")
    implementation("dev.tramai:tramai-orchestration")
    // your workflow definitions
}
```

Workflows are registered via the `WorkflowRegistration` functional interface:

```kotlin
@Bean
fun registerInvoiceWorkflow(): WorkflowRegistration = WorkflowRegistration { registry ->
    registry.register(
        workflow = invoiceWorkflow,
        stateCodec = JacksonStateCodec(InvoiceState::class.java),
    )
}
```

### Where to go next

| Topic | Link |
|---|---|
| Workflow orchestration (steps, checkpointing, resume) | `docs/specs/spec-012-orchestration-and-coordination.md` |
| Scheduling (cron, delays, business calendars) | `tramai-scheduler` module doc |
| Dashboard (admin UI) | `docs/architecture/modules.md` |
| Server spec | `docs/specs/spec-014-server.md` |

---

## L2: Core Concepts (5-minute read)

### Run lifecycle and status model

Every workflow run passes through a state machine. The wire-level status values (which appear in API responses) are:

| Status | Meaning |
|---|---|
| `pending` | Accepted by the server, execution coroutine not yet started |
| `running` | Step execution is in progress |
| `delayed` | Workflow suspended itself via a delay step; waiting for a wakeup |
| `waiting_for_gate` | Paused at a gate step; awaiting an external decision |
| `cancelling` | Cancellation requested; current step being interrupted (cooperative) |
| `cancelled` | Terminal — run was cancelled |
| `failed` | Terminal — run exited with an unhandled error |
| `completed` | Terminal — run reached its result successfully |

Legal transitions:

```
pending ──► running ──► completed
                  ├──► failed
                  ├──► delayed ──► running
                  ├──► waiting_for_gate ──► running
                  └──► cancelling ──► cancelled
```

### API endpoints

#### Workflow lifecycle (core)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/workflows` | List registered workflow names |
| `POST` | `/workflows/{name}/run` | Start a new run from raw JSON state |
| `POST` | `/workflows/{name}/runs/{id}/resume` | Resume a persisted run from checkpoint |
| `GET` | `/workflows/{name}/runs` | List runs with `offset` and `limit` pagination |
| `GET` | `/workflows/{name}/runs/{id}` | Fetch run detail (status, current step, event history, result/error) |
| `DELETE` | `/workflows/{name}/runs/{id}` | Cancel a running workflow (returns `202 Accepted`) |
| `GET` | `/workflows/{name}/runs/{id}/events` | Stream run events over SSE; supports `Last-Event-ID` replay |

#### Webhooks and discovery

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/webhooks/{name}` | Start a workflow from a verified webhook payload (returns `202 Accepted`) |
| `GET` | `/openapi.json` | Auto-generated OpenAPI 3.1 document |

#### Dashboard-adjacent endpoints (behind `tramai.dashboard.enabled`)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/workers` | List all registered workers with status, capabilities, heartbeat |
| `GET` | `/workers/events` | SSE stream for worker online/offline transitions |
| `GET` | `/schedules` | List schedule summaries (cron, next tick, last tick, misfire count) |
| `GET` | `/schedules/events` | SSE stream for schedule ticks and misfires |
| `GET` | `/audit` | Query the in-memory audit log by actor, action, time range |

### Idempotency

The server supports idempotent run creation:

- `POST /workflows/{name}/run` — pass `Idempotency-Key` header; duplicate keys return the existing run record
- `POST /webhooks/{name}` — reuses `X-GitHub-Delivery` (or `X-Delivery-ID`) as the idempotency key; duplicate delivery IDs return `202` with the existing run ID
- `DELETE /workflows/{name}/runs/{id}` — cancelling an already-terminal run returns `202` (idempotent)

### Webhook signature verification

Webhook endpoints use **GitHub-style HMAC-SHA256** signature verification:

```
X-Hub-Signature-256: sha256=<hex-encoded HMAC>
```

The verifier reads `tramai.server.webhooks.secret`. If the secret is blank or the header is missing/invalid, the request is rejected with `401 Unauthorized` (via `InvalidWebhookSignatureException`).

The default verifier is `GitHubWebhookSignatureVerifier`. The `WebhookSignatureVerifier` interface is pluggable for custom verification schemes.

### SSE streaming

Per-run SSE streams (`GET /workflows/{name}/runs/{id}/events`) provide:

- Real-time step lifecycle events: `tramai.step.started`, `tramai.step.completed`, `tramai.step.failed`
- Workflow lifecycle events: `tramai.workflow.running`, `tramai.workflow.completed`, `tramai.workflow.failed`, `tramai.workflow.cancelling`, `tramai.workflow.cancelled`
- Replay support via `Last-Event-ID` header
- Bounded in-memory event buffer (configurable via `tramai.server.sse-event-buffer-size`)
- Stream auto-closes when the run reaches a terminal state

### Error model

All error responses follow **RFC 7807 Problem Details** (Spring `ProblemDetail`):

| Condition | HTTP Status | Problem title |
|---|---|---|
| Invalid JSON state / bad request | `400` | `Invalid workflow request` |
| Invalid webhook signature | `401` | `Invalid webhook signature` |
| Workflow not registered / run not found | `404` | `Workflow resource not found` |
| Resume on non-suspended run | `409` | `Workflow conflict` |
| Request body too large | `413` | `Request body too large` |
| Unexpected server error | `500` | `Workflow execution failed` |

### Configuration

```yaml
tramai:
  server:
    max-request-body-bytes: 1048576          # 1 MB — global body limit
    max-run-history-size: 1000                # max completed runs kept in memory
    sse-event-buffer-size: 100                # events buffered per run for replay
    max-audit-entries: 10000                  # max audit log entries before eviction
    webhooks:
      secret: ""                              # HMAC secret (blank = verification disabled)
      max-request-body-bytes: ${tramai.server.max-request-body-bytes}
  dashboard:
    enabled: true                             # exposes worker/schedule/audit endpoints + SPA
```

---

## L3: Architecture and internals (15-minute read)

### Package structure

All classes live under `dev.tramai.server`:

```
├── TramaiServerApplication.kt          # @SpringBootApplication entry point
├── ServerConfiguration.kt              # Auto-configuration: beans, webhook settings, worker registry
│
├── WorkflowController.kt               # REST controller for workflow lifecycle + webhooks + OpenAPI
├── ScheduleController.kt               # REST controller for schedule listing + SSE
├── WorkerController.kt                 # REST controller for worker listing + SSE
├── AuditController.kt                  # REST controller for audit log queries
│
├── WorkflowRegistry.kt                 # Registry of registered workflows + JDBC table setup
├── WorkflowRunStore.kt                 # In-memory run state store with SSE event buffer
├── InMemoryWorkerRegistry.kt           # In-memory worker registry with heartbeat + SSE push
├── InMemoryAuditLogStore.kt            # In-memory append-only audit log with filtered queries
│
├── WebhookSignatureVerifier.kt         # Interface + GitHub HMAC-SHA256 implementation
└── RequestBodySizeLimitFilter.kt       # Servlet filter enforcing per-route body size limits
```

### Controllers

#### `WorkflowController`

The main controller, mapped as `@RestController`. Key responsibilities:

| Method | Endpoint | Behavior |
|---|---|---|
| `listWorkflows` | `GET /workflows` | Returns `[{name: "..."}]` for each registered workflow |
| `runWorkflow` | `POST /workflows/{name}/run` | Decodes JSON body as the workflow's initial state, creates a run record (with idempotency check), launches execution on the `CoroutineScope`, returns `WorkflowRunResponse` |
| `receiveWebhook` | `POST /webhooks/{name}` | Verifies `X-Hub-Signature-256`, decodes body as initial state, starts the workflow using `X-GitHub-Delivery` as idempotency key, returns `202 Accepted` |
| `resumeWorkflow` | `POST /workflows/{name}/runs/{id}/resume` | Checks the run is in a resumable state (`delayed`/`waiting_for_gate`), creates a `WorkflowPersistence` from the entry's factory, launches resume execution |
| `listRuns` | `GET /workflows/{name}/runs` | Paginated run list from `WorkflowRunStore` |
| `getRun` | `GET /workflows/{name}/runs/{id}` | Full run detail including event history |
| `cancelRun` | `DELETE /workflows/{name}/runs/{id}` | Cooperative cancellation via `Job.cancel()`, returns `202 Accepted` |
| `getRunEvents` | `GET /workflows/{name}/runs/{id}/events` | SSE endpoint; supports `Last-Event-ID` replay; stream closes on terminal state |
| `openApi` | `GET /openapi.json` | Generates OpenAPI 3.1 document from registered workflows |

The controller also includes `@RestControllerAdvice` error handler (`WorkflowErrorHandler`) that maps all known exceptions to RFC 7807 `ProblemDetail` responses.

Execution safety: runs are wrapped in `executeRunSafely` / `executeResumeSafely` which catch `WorkflowSuspendedException` (→ `delayed`), `CancellationException` (→ `cancelled`), and generic `Throwable` (→ `failed`).

#### `ScheduleController`

Conditional controller (behind `tramai.dashboard.enabled`). Exposes:

- `GET /schedules` — lists all schedule statuses from the `WorkflowSchedulerStore`
- `GET /schedules/events` — SSE stream for schedule ticks and misfires

Push methods (`onScheduledTick`, `onMissedTick`, `pushScheduleTick`) are called by `ScheduleEventObserver`, which is wired via Spring's `ObjectProvider<ScheduleController>` to avoid circular dependencies when the dashboard is disabled.

#### `WorkerController`

Conditional controller (behind `tramai.dashboard.enabled`). Exposes:

- `GET /workers` — lists all registered workers from `InMemoryWorkerRegistry` with their status, pool, capabilities, version, host, heartbeat, active run count, and draining flag
- `GET /workers/events` — SSE stream for worker online/offline transitions; sends full worker list as initial state

#### `AuditController`

Conditional controller (behind `tramai.dashboard.enabled`). Exposes:

- `GET /audit` — queries the `InMemoryAuditLogStore` with filters: `actor`, `action`, `from`/`to` (ISO datetime), `page`, `size`

### Webhook signature verification

The `WebhookSignatureVerifier` interface defines:

```kotlin
interface WebhookSignatureVerifier {
    val name: String
    fun verify(payload: String, headers: Map<String, String>): Boolean
}
```

The shipped implementation is `GitHubWebhookSignatureVerifier`:

- Computes `HmacSHA256` of the raw payload using the configured secret
- Compares against the `X-Hub-Signature-256` header value (`sha256=<hex>`)
- Uses `MessageDigest.isEqual` for constant-time comparison (timing-attack resistant)
- Returns `false` (→ `401`) if the secret is blank, the header is missing, or the signature doesn't match

The verifier is configured via `tramai.server.webhooks.secret`. A blank secret causes all webhook requests to fail verification — there is no "unsigned mode" for production webhooks.

### Audit logging

The `InMemoryAuditLogStore` is an append-only, bounded, in-memory store. Each `AuditRecord` captures:

| Field | Type | Description |
|---|---|---|
| `timestamp` | `Instant` | When the operation occurred |
| `actor` | `String` | Who performed it (API key ID, user, system) |
| `action` | `String` | What operation (e.g., `workflow.run`, `workflow.cancel`) |
| `resourceType` | `String` | e.g., `workflow`, `schedule` |
| `resourceId` | `String` | e.g., workflow name or run ID |
| `status` | `String` | e.g., `success`, `failure` |
| `metadata` | `Map<String, String>` | Extensible key-value payload |

Queries support filtering by `actor`, `action`, time range (`from`/`to`), and pagination (`page`/`size`). The store evicts the oldest entries when `maxEntries` (default: 10,000) is exceeded.

### Worker registry

The `InMemoryWorkerRegistry` tracks distributed workers via:

- `register(workerId, poolName, capabilityLabels, version, host)` — creates or updates a worker record
- `heartbeat(workerId)` — updates last heartbeat timestamp; if the worker was previously offline, pushes a `workerOnline` SSE event
- `unregister(workerId)` — removes the worker and pushes a `workerOffline` SSE event

Worker status is computed from heartbeat recency:

| Time since last heartbeat | Status |
|---|---|
| `< 15 seconds` | `online` |
| `15–30 seconds` | `stale` |
| `> 30 seconds` | `offline` |

SSE emitters receive the full worker list on subscription, then incremental `workerOnline`/`workerOffline` events.

### Request body size limits

The `RequestBodySizeLimitFilter` is a `OncePerRequestFilter` that enforces per-route body size limits:

- `/workflows/**` — `tramai.server.max-request-body-bytes` (default: 1 MB)
- `/webhooks/**` — `tramai.server.webhooks.max-request-body-bytes` (default: same as global)

Exceeding the limit raises `RequestBodyTooLargeException`, which maps to HTTP 413.

### Run state store (`WorkflowRunStore`)

The in-memory `WorkflowRunStore` manages all run state within the server process:

- **Thread-safe** — all mutations go through `synchronized(monitor)` blocks
- **Idempotency tracking** — maps `(workflowName, idempotencyKey)` → `RunKey` for duplicate detection
- **Event history** — each run maintains a bounded `MutableList<WorkflowRunEvent>` (configurable via `maxHistorySize`)
- **SSE buffering** — a separate bounded buffer (`sseEventBufferSize`) holds events for replay to late-connecting SSE subscribers
- **Emitter management** — tracks active `SseEmitter` instances per run; dispatches events in real-time; cleans up dead emitters
- **Terminal state cleanup** — on terminal transitions (`completed`/`failed`/`cancelled`), all emitters are completed and the execution `Job` is cleared

### Configuration (`ServerConfiguration`)

The `@Configuration` class auto-wires sensible defaults for all beans:

| Bean | Default | Notes |
|---|---|---|
| `WorkflowRegistry` | Auto-collected `WorkflowRegistration` beans | Scans all `WorkflowRegistration` instances, creates JDBC tables if `DataSource` present |
| `WorkflowRunStore` | `maxHistorySize=1000`, `sseEventBufferSize=100` | Configurable via `tramai.server.*` |
| `webhookSignatureVerifier` | `GitHubWebhookSignatureVerifier` | Reads secret from `tramai.server.webhooks.secret` |
| `InMemoryWorkerRegistry` | Enabled | Dashboard-conditional |
| `InMemoryAuditLogStore` | `maxEntries=10000` | Dashboard-conditional |
| `WorkflowSchedulerStore` | `JdbcWorkflowSchedulerStore` (if `DataSource` available) | Also wired with `ScheduleEventObserver` |

### Key design decisions

1. **Runs are in-memory by default** — run history lives in the server process unless workflow persistence is backed by JDBC (via `tramai-orchestration`'s `JdbcWorkflowCheckpointStore`). The server is stateless for run records; restarts lose in-memory run history.

2. **Cancellation is cooperative** — backed by coroutine `Job.cancel()`. Active work continues until the executing step observes cancellation. No hard-kill mechanism for arbitrary step types.

3. **Worker/schedule/audit endpoints are dashboard-adjacent** — grouped behind `tramai.dashboard.enabled` as an implementation choice, not an architectural truth. They share the SSE infrastructure pattern but are independently usable.

4. **OpenAPI is generated, not hand-maintained** — `GET /openapi.json` builds route shapes from registered workflow names. Request/response schemas are not fully reflected; the document is a discovery aid, not a schema repository.

5. **SSE is a run-lifecycle feed, not a tracing backend** — events are structured around step and workflow lifecycle, not arbitrary application-level log lines. For detailed tracing, pair with `tramai-observability`.

### Spring Boot integration

`tramai-server` is a Spring Boot application that expects:

- `spring-boot-starter-web` on the classpath (provides `@RestController`, `SseEmitter`, servlet filter infrastructure)
- `spring-boot-starter-validation` for `HandlerMethodValidationException` handling
- Jackson + `jackson-module-kotlin` for JSON serialization/deserialization

It does **not** require an embedded web server — it works with Spring Boot's reactive or servlet stacks and can be deployed as a standard Spring Boot fat JAR.
