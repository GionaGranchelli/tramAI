# TASK-007: Add OpenTelemetry observability integration

- Status: done
- Priority: medium
- Primary spec: [SPEC-004](../../specs/spec-004-observability.md)
- Related ADRs: [ADR-006](../../adr/adr-006.md), [ADR-012](../../adr/adr-012.md)
- Last updated: 2026-04-18

## Rationale

Tramai's observability claim needs a concrete implementation path that works automatically when OTel is present and disappears cleanly when it is not.

## Scope

- add span wrapping around provider execution
- map Tramai metadata to GenAI semantic attributes
- emit parse-failure events
- implement no-op behavior when OTel is absent
- keep observability optional at the dependency level and separate from minimal standalone packaging

## Definition Of Done

- provider execution emits spans with expected attributes
- parse failures show up as events
- no-op execution path works without OTel dependencies at runtime
- observability can be added without forcing its dependencies into `tramai-standalone`

## Notes

The observability module should remain optional and thin around the core execution path.
