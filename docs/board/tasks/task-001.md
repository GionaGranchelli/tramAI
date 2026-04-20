# TASK-001: Define core annotations and operation metadata model

- Status: done
- Priority: high
- Primary spec: [SPEC-001](../../specs/spec-001-core-engine.md)
- Related ADRs: [ADR-001](../../adr/adr-001.md), [ADR-002](../../adr/adr-002.md)
- Last updated: 2026-04-18

## Rationale

The engine cannot execute anything until the annotation model and the internal representation of an AI operation are clearly defined.

## Scope

- define initial annotations for service and operation declaration
- define operation metadata extraction requirements
- define validation rules for invalid service definitions

## Definition Of Done

- annotation surface is documented and stable enough for M1
- operation metadata model exists as a concrete implementation target
- invalid usage cases are listed for unit tests

## Notes

This task establishes the contract consumed by proxy generation and dispatch.
