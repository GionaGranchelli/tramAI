# TASK-002: Implement runtime proxy creation and dispatch routing

- Status: todo
- Priority: high
- Primary spec: [SPEC-001](../../specs/spec-001-core-engine.md)
- Related ADRs: [ADR-001](../../adr/adr-001.md), [ADR-002](../../adr/adr-002.md), [ADR-008](../../adr/adr-008.md)
- Last updated: 2026-04-18

## Rationale

Aurora's primary abstraction depends on converting an annotated interface into a working runtime implementation.

## Scope

- create runtime proxy generation for `@AiService` interfaces
- inspect method signatures and classify supported return types
- route method calls into the engine execution path
- detect suspend methods correctly

## Definition Of Done

- a proxy can invoke a stub provider through the engine
- raw `String` and `Unit` routing paths are implemented
- unit tests cover suspend detection and routing behavior

## Notes

Keep proxy logic isolated from provider-specific concerns.
