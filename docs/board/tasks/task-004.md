# TASK-004: Build schema generation and response parsing pipeline

- Status: done
- Priority: high
- Primary spec: [SPEC-002](../../specs/spec-002-structured-output.md)
- Related ADRs: [ADR-003](../../adr/adr-003.md), [ADR-004](../../adr/adr-004.md), [ADR-009](../../adr/adr-009.md)
- Last updated: 2026-04-18

## Rationale

Structured output is Tramai's main product differentiator, so schema generation and parsing need to become concrete before provider work broadens.

## Scope

- implement custom Jackson-based schema generation
- map Kotlin nullability and Tramai annotations into schema output
- cache schema per operation
- implement raw text to JSON extraction and deserialization
- define the structured result contract returned to `tramai-engine`

## Definition Of Done

- supported return types produce deterministic schema
- parser handles plain JSON and common markdown fence wrappers
- structured failures are returned through a result contract instead of leaking parsing logic into the engine
- unit tests cover nullable fields, nested types, and malformed payload extraction

## Notes

Keep provider-specific native structured output out of this first pass.
Do not let this task pull retry policy into `tramai-structured`.
