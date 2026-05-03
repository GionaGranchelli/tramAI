# SPEC-014: TramAI Server (REST API, MCP, Webhooks, SSE)

- Status: proposed
- Owner: maintainer
- Last updated: 2026-05-03
- Related roadmap milestone: Phase 7 — Server
- Related ADRs:
- Related docs: [Orchestrator Vision](../architecture/orchestrator-vision.md)

## Problem

TramAI workflows today can only be started from within a JVM process.

## Scope

- a new `tramai-server` module
- HTTP REST API for workflow run management
- MCP server (protocol core + in-process adapter + stdio/HTTP transports)
- Webhook receiver for external triggers
- SSE streaming for live execution traces
- Workflow registry for multiple workflow types
- Durable cancellation model
- Security defaults: authn/authz, allowlists, body limits, redaction
- Observer events (not direct OTel)
- Audit logging

## Non-Goals

- Visual dashboard (deferred to SPEC-017)
- Workflow editing via API (workflow definitions are compile-time)

## Functional Requirements

### REST API

- `POST /workflows/{name}/run` — start a new workflow run with JSON state, returns run ID
- `POST /workflows/{name}/runs/{id}/resume` — resume a workflow by ID
- `GET /workflows/{name}/runs` — list runs with status, pagination, sorting, filtering
- `GET /workflows/{name}/runs/{id}` — inspect a run (status, current step, history, I/O)
- `GET /workflows/{name}/runs/{id}/events?since=N` — stream events via SSE
- `DELETE /workflows/{name}/runs/{id}` — cancel a running workflow (returns 202)

All endpoints return JSON. Error responses follow RFC 7807 (Problem Details).

### Run State Model

- `pending` — workflow accepted but not yet started
- `running` — step execution in progress
- `delayed` — waiting on a delay step
- `waiting_for_gate` — paused at a gate step awaiting decision
- `cancelling` — cancellation requested, current step being interrupted
- `cancelled` — terminal cancellation checkpoint written
- `failed` — workflow terminated with an unhandled error
- `completed` — workflow reached its result selector successfully

Legal transitions:
- pending → running | cancelling
- delayed → running | cancelling
- waiting_for_gate → running | cancelling
- running → completed | failed | delayed | waiting_for_gate | cancelling
- cancelling → cancelled | failed

### Idempotency Rules

- `POST /workflows/{name}/run`: idempotent if client provides `Idempotency-Key` header.
  Duplicate keys return the existing run ID instead of creating a new run.
- `DELETE /workflows/{name}/runs/{id}`: idempotent — cancelling an already-cancelled
  run returns 202.
- `POST /webhooks/{name}`: idempotent via webhook delivery ID (from `X-Delivery-ID`
  or `X-GitHub-Delivery` header). Duplicate delivery IDs return 200 with the
  existing run ID.
- Schedule ticks: idempotent via `tick_id` — insert-if-absent prevents duplicates.

### Concurrency Rules

- A run can have at most one active worker at a time, enforced by lease.
- `resume` on a run with an active lease fails with 409 Conflict.
- `resume` on a completed/cancelled/failed run returns the terminal status (idempotent).

### MCP Protocol Layering

MCP is a peer protocol to REST, not a thin adapter over it.

```
┌──────────────────────────────────────┐
│          MCP Protocol Core            │  ← JSON-RPC message handling
│  (TASK-030A)                          │
├──────────────────────────────────────┤
│        In-Process Adapter             │  ← maps to workflow registry directly
│  (TASK-030C)                          │     (no HTTP needed for local agents)
├──────────┬───────────────────────────┤
│  Stdio   │       HTTP/SSE             │  ← transports
│ (local)  │       (remote)             │
└──────────┴───────────────────────────┘
```

- The protocol core handles JSON-RPC request/response, tool discovery, error codes.
- The in-process adapter maps `list_workflows`, `run_workflow`, `resume_workflow`,
  `get_workflow_status` directly to the workflow registry without requiring HTTP.
- Stdio transport is for local agents (Hermes, Codex CLI, Copilot CLI, Gemini CLI).
- HTTP/SSE transport is for remote MCP clients.

### Transport Compatibility

| Transport | Use case | Status |
|-----------|----------|--------|
| stdio (in-process) | Local agents | Phase 7 |
| SSE (HTTP) | Remote MCP clients | Phase 7 |
| WebSocket | Bidirectional streaming | Future |

### Webhook Receiver

- `POST /webhooks/{workflow-name}` — generic webhook endpoint
- Request body deserialized into the workflow's initial state type
- Returns 202 Accepted with workflow ID immediately
- Signature verification: GitHub HMAC, timestamp + nonce for replay prevention
- Configurable clock skew tolerance (default: 5 minutes)
- Body size limit enforced before buffering
- Duplicate delivery prevention via delivery ID header

### SSE Streaming

- `GET /workflows/{name}/runs/{id}/events?since=N` — SSE endpoint
- Events: step started, step completed, step failed, workflow completed, workflow cancelled
- Each event includes: step name, duration, status, error message
- `Last-Event-Id` reconnection: takes precedence over `since=N` when both present
- Event retention: configurable (default: 1 hour, max: 7 days)
- Events older than retention are silently dropped
- Stream closes when the workflow reaches a terminal state

### Cancellation Model

```kotlin
enum class CancellationSource { REST_API, WORKER_SHUTDOWN, LEASE_EXPIRED, MCP_CLIENT, SCHEDULER, DELAY_ABORT, INTERNAL_TIMEOUT }
data class CancellationRequest(val runId, val source, val reason: String?, val deadline: Instant?)
```

- `DELETE /workflows/{name}/runs/{id}` writes a durable cancellation request to WorkflowStore
- Returns 202 Accepted immediately (not waiting for cancellation to complete)
- Worker polls for cancellation requests during step execution
- Shell subprocess: close stdin → SIGTERM → wait terminationGracePeriod → SIGKILL
- MCP call: send MCP cancellation notification for the active request ID
- Delay step: mark delay wakeup as cancelled
- Lease expiry: worker must stop checkpoint writes (fencing token rejection)
- Cancellation deadline: if the step doesn't acknowledge within deadline, forceful
- Failed cancellation: run moves to `failed` with cancellation error context
- Cleanup hooks: steps can register `onCancel` handlers

### Security

- Tool exposure: MCP tools require explicit allowlist — no auto-exposure
- Webhook signature verification with timestamp tolerance + nonce
- Request body size limits on all endpoints
- Response body size limits on run history (truncated state/output)
- Secrets redacted in observer events, logs, and saved state by default
- CORS: denied by default for production, configurable for dashboard access
- SSRF: HTTP steps and webhooks restricted to configurable network allowlist
- Authn/authz: pluggable middleware (JWT, API key, OAuth2). No auth in v1 dev mode,
  enforced in production mode.
- Structured error redaction: error messages do not leak internal paths, tokens, or state

### Observability

- Every operation emits stable TramAI observer events
- Events are defined at the module level, not as OTel spans
- The `tramai-observability` module bridges observer events to OTel when present
- Observer payloads redact secrets by default

### Audit Logging

- Every API call, webhook receipt, schedule tick, and cancellation is logged
- Record: timestamp, actor (API key ID / user / system), action, resource, status
- Append-only, configurable retention

### Versioning

- Workflow definitions are versioned (SemVer)
- In-flight runs complete on the version they started on
- New runs use the latest registered version
- Run payload schemas are versioned alongside workflow definitions
- API responses include workflow definition version

## Quality Requirements

- REST API response: < 100ms for list operations
- SSE reconnection: at most one missed event with Last-Event-Id
- MCP connection: < 1 second handshake
- Webhook processing: acknowledge immediately, queue execution
- Event retention: configurable, minimum 1 hour

## Acceptance Criteria

- [ ] POST /workflows/{name}/run with valid JSON starts the workflow and returns run ID
- [ ] POST /workflows/{name}/run with client idempotency key returns existing run ID
- [ ] POST /workflows/{name}/runs/{id}/resume resumes from checkpoint
- [ ] DELETE /workflows/{name}/runs/{id} moves run to cancelling → cancelled
- [ ] DELETE on already-cancelled run returns 202 (idempotent)
- [ ] SSE streams events for each step; reconnection catches up correctly
- [ ] Webhook POST starts workflow, returns 202; duplicate delivery is idempotent
- [ ] MCP client discovers workflows and starts a run via run_workflow tool
- [ ] MCP in-process adapter works without HTTP server running
- [ ] Cancelled shell step terminates the subprocess within grace period
- [ ] Observer events fire for all lifecycle transitions without OTel on classpath
- [ ] With OTel on classpath, observer events bridge to spans/metrics
- [ ] Secrets are redacted in observer payloads and saved state
- [ ] Audit log records every API call with actor, action, resource, status
- [ ] Workflow definition version is included in API responses
