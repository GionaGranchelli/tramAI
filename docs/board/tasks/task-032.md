# TASK-032: Implement SSE Streaming for Live Traces

- Status: planned
- Priority: medium
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Allow clients to subscribe to live execution events for a workflow run via
Server-Sent Events.

## Scope

- `GET /workflows/{name}/runs/{id}/events?since=N` — SSE endpoint
- Events: step started, step completed, step failed, workflow completed,
  workflow failed
- Each event includes: step name, duration, status, error message (if any)
- Reconnection support via `Last-Event-Id` header
- Configurable event buffer size
- Connection lifecycle: open, stream, close on workflow completion or cancel

## Exit Criteria

- [ ] SSE stream sends events for each step of a running workflow
- [ ] Reconnecting with `Last-Event-Id` does not miss any events
- [ ] Stream closes when the workflow completes or is cancelled
- [ ] Multiple clients can subscribe to the same workflow run simultaneously
- [ ] Events are JSON-encoded and include all required fields
