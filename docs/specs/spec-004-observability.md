# SPEC-004: Observability Integration

- Status: implemented
- Owner: maintainer
- Last updated: 2026-04-18
- Related roadmap milestone: M4
- Related ADRs: [ADR-006](../adr/adr-006.md), [ADR-012](../adr/adr-012.md)
- Related docs: [Architecture Overview](../architecture/overview.md)

## Problem

Tramai claims to be observability-native. That claim is only credible if every AI call emits useful OpenTelemetry data automatically when OTel is present, without penalizing users who do not use it.

## Scope

- Optional `tramai-observability` module
- Span creation around provider execution
- Mapping of Tramai execution metadata to OTel GenAI semantic conventions
- Parse failure events recorded on spans
- No-op behavior when OTel APIs are absent

## Non-Goals

- A custom metrics backend
- Logging as the primary observability mechanism
- Mandatory OTel dependency in core modules

## Functional Requirements

- Every provider call must be wrapped in a span when observability is enabled.
- Spans must include provider identity, requested model, response model, token usage, operation identity, retry attempt, and structured parse success.
- Structured parse failures must be emitted as span events rather than top-level span exceptions by default.
- When OTel is not available, Tramai must use a no-op path without changing application behavior.
- Observability wiring must not require an extra feature flag when the module and OTel APIs are present.
- Observability must remain opt-in at the dependency level and must not be a mandatory transitive dependency of `tramai-standalone`.

## Quality Requirements

- The no-op path should add negligible overhead.
- Observability code must not leak into the core module dependency graph as a hard requirement.
- The observability story must distinguish clearly between runtime auto-enable behavior and dependency-level opt-in.
- Tests must assert span names, attributes, and parse-failure event recording using OTel test utilities.

## Design Notes

- Automatic enablement is part of the product design, not just a convenience feature.
- Observability should sit around provider execution and not force providers themselves to understand tracing concerns.
- Auto-enable means "automatic once the observability module is present," not "always bundled through the standalone artifact."

## Acceptance Criteria

- A real provider call generates a span with the expected GenAI attributes in a local test setup.
- A structured parse failure records an event with failure context.
- Running without OTel on the classpath does not fail and does not require configuration changes.
- `tramai-standalone` can be used without pulling `tramai-observability` transitively.

## Risks and Follow-Ups

- OTel semantic conventions may evolve and require versioned compatibility decisions.
- Token usage metadata may not be equally available from every provider.
