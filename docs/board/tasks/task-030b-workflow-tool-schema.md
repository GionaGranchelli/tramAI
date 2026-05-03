# TASK-030B: Workflow Tool Schema Generation

- Status: planned
- Priority: high
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Parent task: [TASK-030](../tasks/task-030.md)
- Last updated: 2026-05-03

## Purpose

Auto-generate stable MCP tool names and JSON Schema input/output descriptions from typed workflow definitions. The schema layer ensures MCP clients always see valid, self-describing tool contracts regardless of how workflows are defined internally.

## Scope

- JSON Schema generation from workflow state types (input and output)
- Stable MCP tool name derivation with collision detection
- Escaping special characters in tool names
- Version suffix support for schema-breaking workflow changes
- Schema validation failure payloads with actionable error messages
- Integration tests comparing generated schemas against known workflow types

## Exit Criteria

- [ ] A workflow with typed state produces correct JSON Schema for input and output
- [ ] Two workflows with identical names produce collision-resolved tool names
- [ ] Special characters in workflow names are escaped to valid MCP tool identifiers
- [ ] Schema validation for missing required fields returns structured error payloads
- [ ] Schema validation for wrong field types returns structured error payloads
- [ ] Workflow version bumps produce distinct tool names with version suffixes
- [ ] Generated schemas pass JSON Schema meta-validation (draft-07)
