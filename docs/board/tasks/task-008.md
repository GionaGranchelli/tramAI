# TASK-008: Build standalone runtime, Kotlin DSL, and Java entry points

- Status: done
- Priority: medium
- Primary spec: [SPEC-005](../../specs/spec-005-standalone-java-api.md)
- Related ADRs: [ADR-005](../../adr/adr-005.md), [ADR-008](../../adr/adr-008.md), [ADR-011](../../adr/adr-011.md), [ADR-012](../../adr/adr-012.md)
- Last updated: 2026-04-18

## Rationale

The standalone runtime is the canonical product form and the basis for later adapters.

## Scope

- compose the standalone runtime module
- add Kotlin DSL builder
- add Java builder entry point
- support explicit blocking service interfaces for Java and non-coroutine consumers
- keep standalone packaging minimal and observability-free by default
- define BOM expectations

## Definition Of Done

- Kotlin users can configure and create services without a framework
- Java users can build Tramai and call a blocking service interface path
- standalone packaging does not pull observability dependencies transitively
- standalone packaging does not pull framework dependencies

## Notes

Treat standalone as the reference runtime, not a secondary artifact.
Do not promise generated `*Blocking` methods unless Tramai adopts an explicit generation mechanism.
