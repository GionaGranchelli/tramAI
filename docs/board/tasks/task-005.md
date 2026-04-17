# TASK-005: Add structured retry loop and failure diagnostics

- Status: todo
- Priority: high
- Primary spec: [SPEC-002](../../specs/spec-002-structured-output.md)
- Related ADRs: [ADR-003](../../adr/adr-003.md), [ADR-009](../../adr/adr-009.md)
- Last updated: 2026-04-18

## Rationale

Parsing without recovery is not enough. Aurora needs a robust correction loop and explicit failure reporting for structured outputs.

## Scope

- add validation feedback retry behavior
- support configurable `maxRetries`
- define `StructuredOutputException` payload details
- ensure retry state is visible to later observability hooks
- consume structured failure results from `aurora-structured` without embedding parsing logic in the engine

## Definition Of Done

- malformed first responses can recover on later attempts
- exhausted retries produce a typed exception with debugging context
- engine-level retry decisions are driven by structured result data rather than engine-owned parsing rules
- tests cover retry count, feedback flow, and terminal failure

## Notes

This task should align error payloads with future observability and testing support.
The engine owns retry orchestration even when the retry is triggered by structured-output failure feedback.
