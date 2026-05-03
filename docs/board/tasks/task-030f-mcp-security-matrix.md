# TASK-030F: MCP Server Security and Compatibility Matrix

- Status: planned
- Priority: high
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Parent task: [TASK-030](../tasks/task-030.md)
- Last updated: 2026-05-03

## Purpose

Define and enforce the security, compatibility, and testing boundary for the MCP server. This task produces the tool exposure policy, redaction rules, a transport compatibility matrix, and a set of golden protocol fixtures used across all transport implementations.

## Scope

- Tool exposure allowlist/denylist configuration (which workflows surface as MCP tools)
- Redaction policy for sensitive state fields in workflow JSON Schema and status output
- Transport compatibility matrix clearly documenting which features each transport supports
- Golden protocol fixtures: canonical JSON-RPC request/response pairs for every MCP method
- Cross-transport conformance tests running the golden fixtures against all transports
- Security test: unlisted workflows must not appear in `list_tools` or be callable
- Security test: redacted fields must not appear in tool schemas or status responses

## Exit Criteria

- [ ] Allowlist configuration limits `list_tools` to only exposed workflows
- [ ] Denylist configuration suppresses specific workflows from `list_tools` and tool calls
- [ ] Attempting to call a denylisted workflow returns `InvalidRequest` with access-denied detail
- [ ] Redacted fields are absent from JSON Schema input/output definitions
- [ ] Redacted fields are absent from `get_workflow_status` state snapshots
- [ ] Transport compatibility matrix documents supported features per transport
- [ ] Golden fixture file contains at least 10 canonical request/response pairs
- [ ] Cross-transport conformance tests pass the golden fixtures on stdio, HTTP/SSE, and in-process transports
