# TASK-030: Implement MCP Server Adapter

- Status: done
- Priority: high
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Related ADRs:
- Last updated: 2026-05-03

## Purpose

Expose TramAI workflows as MCP tools so Hermes, Codex CLI, Copilot CLI, and
Gemini CLI can call them directly.

## Scope

- JVM-based MCP server implementation (MCP Kotlin/Java SDK)
- `list_workflows` tool returning workflow names + JSON Schema input/output
- `run_workflow` tool accepting name + JSON state, returning workflow ID
- `resume_workflow` tool resuming by ID
- `get_workflow_status` tool inspecting a run
- Auto-generate JSON Schema from typed workflow state
- Support stdio transport (for local Hermes/agents) and SSE transport
- MCP adapter translates MCP JSON-RPC calls into REST API calls internally

## Exit Criteria

- [ ] `hermes mcp test tramai-server` discovers all registered workflows
- [ ] `run_workflow` starts a workflow and returns a valid ID
- [ ] `get_workflow_status` returns current step and state for a running workflow
- [ ] Stdio transport works with Hermes MCP config
- [ ] SSE transport works for remote MCP clients
- [ ] JSON Schema input validation returns clear errors for invalid state
