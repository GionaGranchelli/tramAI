# Workflow Server

`tramai-server` exposes registered workflows over HTTP.

It is the runtime module for teams that want to trigger, inspect, resume, cancel, and observe workflows from outside JVM application code.

## What It Covers

Current implemented surface:

- workflow discovery
- workflow run creation
- webhook-triggered workflow runs
- workflow resume
- run listing and run detail
- per-run SSE event streams
- cancellation
- generated OpenAPI document

Related operational endpoints also exist for:

- workers
- schedules
- audit

Those endpoints are currently grouped behind the dashboard feature flag.

## Core Endpoints

### Workflow lifecycle

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/workflows` | List registered workflow names. |
| `POST` | `/workflows/{name}/run` | Start a new run from raw JSON state. |
| `POST` | `/workflows/{name}/runs/{id}/resume` | Resume a persisted run. |
| `GET` | `/workflows/{name}/runs` | List runs with `offset` and `limit`. |
| `GET` | `/workflows/{name}/runs/{id}` | Fetch run detail. |
| `DELETE` | `/workflows/{name}/runs/{id}` | Request cancellation. |
| `GET` | `/workflows/{name}/runs/{id}/events` | Stream run events over SSE. |

### Webhooks and discovery

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/webhooks/{name}` | Start a workflow from a verified webhook payload. |
| `GET` | `/openapi.json` | Generated OpenAPI 3.1 description. |

### Dashboard-adjacent endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/workers` | List worker status. |
| `GET` | `/workers/events` | Stream worker status changes over SSE. |
| `GET` | `/schedules` | List schedule summaries. |
| `GET` | `/schedules/events` | Stream schedule updates over SSE. |
| `GET` | `/audit` | Query the in-memory audit log. |

## Request And Response Shape

### Starting a run

`POST /workflows/{name}/run` accepts the raw JSON for the workflow's initial state type.

Example:

```http
POST /workflows/invoice/run
Content-Type: application/json
Idempotency-Key: inv-123

{"invoiceId":"inv-123","amount":125}
```

Current success payload:

```json
{
  "workflowId": "7f8c...",
  "status": "running",
  "definitionVersion": "1.0.0",
  "result": null
}
```

### Listing runs

`GET /workflows/{name}/runs?offset=0&limit=50`

Current limits enforced by code:

- `offset >= 0`
- `1 <= limit <= 200`

### Run detail

Run detail includes:

- workflow id
- status
- definition version
- current step
- event history
- result, when terminal and successful
- error message, when terminal and failed

## Run Status Model

Current wire values are:

- `pending`
- `running`
- `delayed`
- `waiting_for_gate`
- `cancelling`
- `cancelled`
- `failed`
- `completed`

In practice today:

- delayed runs are represented when a workflow suspends itself
- cancellation is cooperative and backed by coroutine cancellation

## Idempotency

The server supports idempotent run creation through `Idempotency-Key`.

Current behavior:

- if the key matches an earlier run for the same workflow, the existing run record is returned
- the server stores the association in the in-memory run store

Webhook idempotency currently reuses delivery identifiers:

- `X-GitHub-Delivery`
- or `X-Delivery-ID`

## Webhook Model

Current webhook endpoint:

```http
POST /webhooks/{name}
X-Hub-Signature-256: sha256=...
X-GitHub-Delivery: ...
```

Current implementation details:

- signature verification is GitHub-style HMAC SHA-256
- the verifier reads `tramai.server.webhooks.secret`
- the webhook body is decoded as the workflow's initial state JSON
- a successful webhook returns `202 Accepted`

The current server module ships one verifier path. It does not yet expose a general webhook-adapter plugin layer; that layer exists in `tramai-platform`.

## SSE

Per-run SSE streams come from:

`GET /workflows/{name}/runs/{id}/events`

Current behavior:

- the endpoint uses `Last-Event-ID` for replay position
- the run store keeps a bounded in-memory event buffer per run
- the stream closes when the run reaches a terminal state

The event stream is intentionally simple. It is a run-lifecycle feed, not a full tracing backend.

## Error Model

The server returns Spring `ProblemDetail` responses for bad requests and conflicts.

Examples covered by tests:

- invalid workflow JSON -> `400`
- invalid webhook signature -> `401`
- oversized request body -> `413`

## Configuration

The current server properties are:

```yaml
tramai:
  server:
    max-request-body-bytes: 1048576
    max-run-history-size: 1000
    sse-event-buffer-size: 100
    max-audit-entries: 10000
    webhooks:
      secret: ""
      max-request-body-bytes: ${tramai.server.max-request-body-bytes}
```

The server also expects standard Spring Boot HTTP configuration such as `server.port`.

## Dashboard Relationship

The dashboard is a separate module.

Current code-level behavior:

- `tramai-dashboard` serves static assets under `/dashboard/**`
- `/tramai-settings.js` exposes runtime config for the SPA
- `tramai.dashboard.enabled=false` disables both the SPA and the server-side worker/schedule/audit endpoints currently grouped with it

That grouping is an implementation choice in the current repo, not a deep architectural truth.

## What To Watch

A few current constraints matter when operating this module:

- run history is in-memory unless your workflow persistence handles state separately
- cancellation is cooperative, not hard-stop external-process management for every step kind
- the OpenAPI document is generated from registered workflow names and route shapes, not from full request/response schemas

## Related Pages

- [Workflow Scheduling](./scheduling.md)
- [MCP Integration](./mcp.md)
- [Platform Operations](./platform.md)
- [SPEC-014: TramAI Server](../specs/spec-014-server.md)
