# TASK-030D: Stdio MCP Transport

- Status: planned
- Priority: high
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Parent task: [TASK-030](../tasks/task-030.md)
- Last updated: 2026-05-03

## Purpose

Implement the stdio transport layer that wraps the MCP protocol core in a subprocess-compatible stdin/stdout JSON-RPC message loop. This is the primary transport for local tool clients like Hermes, Codex CLI, and Claude Code.

## Scope

- Line-delimited JSON-RPC message reading from stdin
- Response and notification writing to stdout
- Stderr for diagnostics and logging (never protocol messages)
- Graceful shutdown via SIGTERM and EOF handling
- Message framing with newline-delimited JSON (one message per line)
- Timeout handling for slow tool invocations
- Client compatibility smoke test using a real MCP stdio client driver

## Exit Criteria

- [ ] Server reads JSON-RPC requests from stdin and writes responses to stdout
- [ ] Stderr output does not interfere with protocol messages
- [ ] Server shuts down cleanly on EOF (stdin close)
- [ ] Server shuts down cleanly on SIGTERM
- [ ] `list_tools` request/response round-trip completes within 500ms
- [ ] Tool call with valid params returns correct result over stdio
- [ ] Tool call with invalid params returns error over stdio
- [ ] Smoke test passes against a real MCP client driver (e.g. `mcp-cli`)
