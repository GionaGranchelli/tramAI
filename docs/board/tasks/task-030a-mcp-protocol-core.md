# TASK-030A: MCP Protocol Core

- Status: planned
- Priority: high
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Parent task: [TASK-030](../tasks/task-030.md)
- Last updated: 2026-05-03

## Purpose

Implement the MCP protocol core layer that handles JSON-RPC message framing, request/response dispatch, tool abstraction, and error mapping to MCP error codes. This layer provides the foundation all transports (stdio, HTTP/SSE, in-process) build on.

## Scope

- JSON-RPC 2.0 request/response/notification message parsing and serialization
- Tool registration and discovery abstraction (`list_tools`, `tool_call`)
- MCP error code mapping (ParseError, InvalidRequest, MethodNotFound, InvalidParams, InternalError, etc.)
- Request ID tracking and response correlation
- Deterministic fake client for protocol-level unit tests (no transport dependency)
- Protocol version handshake support

## Exit Criteria

- [ ] JSON-RPC request deserialises to typed method + params with correct ID tracking
- [ ] Registered tools appear in `list_tools` responses
- [ ] Tool call dispatches through the abstraction and returns typed results
- [ ] Unknown methods return `MethodNotFound` (-32601) error responses
- [ ] Invalid params return `InvalidParams` (-32602) error responses with details
- [ ] Malformed JSON returns `ParseError` (-32700) responses
- [ ] Fake client produces deterministic request/response pairs in tests
- [ ] Protocol version negotiation round-trips correctly
