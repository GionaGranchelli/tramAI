# TASK-003: Define base provider contracts and exception hierarchy

- Status: todo
- Priority: high
- Primary spec: [SPEC-001](../../specs/spec-001-core-engine.md)
- Related ADRs: [ADR-008](../../adr/adr-008.md)
- Last updated: 2026-04-18

## Rationale

The engine needs a stable provider boundary and clear failure model before real integrations are added.

## Scope

- define `ModelProvider`, request, and response contracts
- define base Aurora exception hierarchy
- define engine-facing timeout and configuration failure semantics

## Definition Of Done

- provider contract is small, stable, and coroutine-native
- exception types map clearly to configuration, provider, timeout, and structured failures
- core engine code can depend on these contracts without provider-specific leakage

## Notes

This task should keep the provider boundary simple enough for future modules and community extensions.
