# TASK-020: Fix Workflow Observability Correlation

- Status: completed
- Priority: high
- Primary spec: [SPEC-012](../../specs/spec-012-orchestration-and-coordination.md)
- Related ADRs: [ADR-017](../../adr/adr-017.md), [ADR-007](../../adr/adr-007.md)
- Last updated: 2026-04-22

## Purpose

Ensure workflow-level observability remains correct under concurrent and repeated workflow execution.

## Scope

- stop keying active workflow observation state by bare `workflowId` alone
- define a stable run-correlation model for workflow spans and events
- verify resume, failure, and parallel-branch events retain correct attribution
- document the stable workflow span attributes and event names that users may rely on

## Definition Of Done

- concurrent or repeated runs cannot corrupt workflow span/event attribution
- workflow-level observability remains correct when the same `workflowId` appears in different workflow definitions
- tests cover collision scenarios and expected workflow event semantics
- observability docs match the actual stable workflow observer contract

## Notes

This task is about correctness first, not adding more telemetry volume.
Implemented by switching `OpenTelemetryWorkflowObserver` to a composite `(workflow name, workflow id)` active-run key, adding overlap tests for shared `workflowId` usage across different workflow definitions, and documenting the stable workflow correlation model in the user guides.
