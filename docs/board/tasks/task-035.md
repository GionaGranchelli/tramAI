# TASK-035: Implement MCP Step Type

- Status: done
- Priority: high
- Primary spec: [SPEC-015](../../specs/spec-015-agent-steps.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Add a first-class MCP step type so workflows can call tools on any MCP server
(a11y audits, browser screenshots, DaisyUI component docs, etc.) as a step.

## Scope

- `mcpStep(name) { ... }` DSL extension
- Configuration: server name, tool name, arguments, timeout
- MCP server configuration (stdio or TCP) as part of TramaiServer config
- Connect → call tool → disconnect lifecycle per step
- Tool result returned as raw JSON or typed value
- Timeout at the tool call level
- Reconnect on transient failures (if server restarts)
- Error handling: tool not found, server unreachable, invalid arguments
- OpenTelemetry events: tool name, server name, duration, result size

## Exit Criteria

- [ ] MCP step calls a locally configured MCP server tool and returns result
- [ ] MCP step with invalid arguments returns a descriptive error
- [ ] MCP step times out if the tool exceeds the configured timeout
- [ ] MCP step reconnects if the server disconnects mid-call
- [ ] OpenTelemetry span captures server name, tool name, and duration
