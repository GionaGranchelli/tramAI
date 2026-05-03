# TASK-032: Implement SSE Streaming for Live Traces

- Status: done
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

- [x] SSE stream sends events for each step of a running workflow
- [x] Reconnecting with `Last-Event-Id` does not miss any events
- [x] Stream closes when the workflow completes or is cancelled
- [x] Multiple clients can subscribe to the same workflow run simultaneously
- [x] Events are JSON-encoded and include all required fields

## Implementation Notes

- SSE events built via Jackson serialization (no string interpolation)
- sseEvents buffer independent from canonical run history
- emitter.send() outside synchronized(monitor) to prevent lock contention
- cancel/markResuming transitions routed through event() for SSE dispatch
- Execution Job attached to runs; cancel() signals via job.cancel()
- Transaction wrapper applied to getSchedule() for consistency

## Review

Reviewed by Copilot (gpt-5.4): 5/6 checks passed. One false positive on
markResuming event routing — status transition is atomic inside lock, event
dispatch follows outside. Effective PASS.
