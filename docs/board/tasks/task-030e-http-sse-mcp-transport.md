# TASK-030E: HTTP/SSE MCP Transport

- Status: planned
- Priority: high
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Parent task: [TASK-030](../tasks/task-030.md)
- Last updated: 2026-05-03

## Purpose

Implement the HTTP/SSE transport layer for remote MCP connections. This transport enables MCP clients outside the local JVM process (e.g. remote Hermes instances, Gemini CLI, Copilot CLI) to discover and invoke workflow tools over HTTP with server-sent events for streaming responses.

## Scope

- SSE endpoint for server-to-client message streaming (responses, notifications, tool progress)
- HTTP POST endpoint for client-to-server JSON-RPC requests
- Connection lifecycle management: open, ping/keep-alive, close
- SSE reconnect handling with `Last-Event-Id` and retry interval support
- Request size limits with clear rejection payloads for oversized payloads
- Concurrency limits per connection to prevent resource exhaustion
- CORS headers for web-based MCP clients
- Integration test simulating a remote MCP client connection lifecycle

## Exit Criteria

- [ ] Client connects via SSE and receives server capabilities
- [ ] Client sends a JSON-RPC request via HTTP POST and receives response via SSE
- [ ] Server sends periodic keep-alive pings over SSE
- [ ] Client reconnects after SSE disconnect using `Last-Event-Id`
- [ ] Request exceeding the size limit returns `413 Payload Too Large` with error detail
- [ ] Concurrent request limit per connection is enforced with `429 Too Many Requests`
- [ ] CORS headers allow cross-origin MCP clients
- [ ] Integration test verifies full connect-request-reconnect lifecycle
