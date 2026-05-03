# TASK-030C: In-Process Workflow MCP Adapter

- Status: planned
- Priority: high
- Primary spec: [SPEC-014](../../specs/spec-014-server.md)
- Parent task: [TASK-030](../tasks/task-030.md)
- Last updated: 2026-05-03

## Purpose

Implement an in-process MCP adapter that maps MCP tool calls (list_workflows, run_workflow, resume_workflow, get_workflow_status) directly to the workflow registry without requiring a REST gateway. This is the default mode for embedding the MCP server inside the same JVM process.

## Scope

- `list_workflows` tool mapped to workflow registry enumeration
- `run_workflow` tool accepting workflow name + JSON state, delegating to registry start
- `resume_workflow` tool resuming a suspended workflow by ID
- `get_workflow_status` tool returning current step, state snapshot, and status
- Workflow result retrieval (completed or failed) with structured payloads
- Error translation from workflow runtime exceptions to MCP error responses
- Compatibility test suite run against a set of fake (deterministic) workflow implementations

## Exit Criteria

- [ ] `list_workflows` returns all registered workflows with names and schemas
- [ ] `run_workflow` starts a workflow and returns a valid workflow execution ID
- [ ] `resume_workflow` resumes a waiting workflow and returns updated status
- [ ] `get_workflow_status` returns: status enum, current step name, state snapshot, and timestamps
- [ ] Running a non-existent workflow returns `InvalidParams` with workflow-not-found detail
- [ ] Workflow runtime exceptions are mapped to `InternalError` with context
- [ ] All four tools pass the fake workflow compatibility test suite
