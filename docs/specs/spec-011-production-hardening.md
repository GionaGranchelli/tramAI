# SPEC-011: Production Hardening

- Status: in_progress
- Owner: maintainer
- Last updated: 2026-04-20
- Related roadmap milestone: Phase 5
- Related ADRs: [ADR-001](../adr/adr-001.md), [ADR-008](../adr/adr-008.md), [ADR-012](../adr/adr-012.md), [ADR-013](../adr/adr-013.md), [ADR-015](../adr/adr-015.md), [ADR-016](../adr/adr-016.md)
- Related docs: [Roadmap Summary](../roadmap.md), [Execution Board](../board/board.md)

## Problem

Tramai now covers the core request lifecycle, structured outputs, observability hooks, framework adapters, streaming, and tool calling. That is enough to validate the library shape, but not enough to call the project production-ready for backend workloads that need stronger resilience, security, operational controls, and deployment portability.

## Scope

- resilience improvements around retry pacing, fallback routing, and provider health
- security controls that reduce the chance of leaking sensitive data to upstream providers
- runtime and deployment support needed for native-image and production operations
- metrics and cost controls that make Tramai observable beyond tracing alone

## Non-Goals

- broad agent-framework features
- long-lived memory systems
- provider-specific operational behavior exposed as the primary public API
- shipping every possible production feature in one milestone

## Functional Requirements

- Tramai must support more disciplined handling of rate limits, including honoring provider retry hints where available.
- Tramai must provide an explicit fallback routing mechanism rather than hidden provider-selection heuristics.
- Tramai must provide a way to prevent repeated calls into an unhealthy provider when failures indicate a sustained outage.
- Tramai must support pluggable request/response interception for redaction or masking of sensitive data before provider transport.
- Tramai must provide a path for production applications to source credentials without hard-coding secrets into application code.
- Tramai must expose metrics for latency and token usage suitable for standard OpenTelemetry collection pipelines.
- Tramai must support explicit token-consumption controls so applications can fail fast when cost policies are exceeded.
- Tramai must provide a documented and testable path toward GraalVM native-image compatibility.

## Quality Requirements

- Production-hardening features must preserve Tramai's framework-agnostic core boundaries.
- Operational safety behavior must be explicit and testable rather than magical.
- Resilience features must fail loudly with actionable debugging context.
- Security features must be opt-in where they introduce policy decisions, but easy to wire into existing runtimes.
- Native-image support must not depend on undocumented manual steps.

## Design Notes

- Resilience policy belongs in engine-owned orchestration rather than provider-specific ad hoc behavior.
- Security interception must not blur module boundaries by smuggling provider concerns into consumer APIs.
- Metrics should complement tracing rather than replace it.
- Native-image support may require generated metadata, narrowed reflection usage, or both.
- Streaming failover is allowed only before the first token is emitted. Once a stream has produced user-visible output, Tramai must surface a terminal error rather than splice together partial results from multiple providers.

## Current Focus

- pluggable request/response interception for redaction and masking
- production secret resolvers beyond the built-in `env:` and `file:` schemes

## Acceptance Criteria

- Automated tests cover retry pacing, fallback selection, and provider-health protection behavior.
- Automated tests cover sensitive-data redaction hooks and token-budget enforcement.
- Token usage and latency metrics are emitted through the observability module in a collector-friendly shape.
- A documented native-image path exists and is validated by automated build or smoke-test coverage.

## Risks and Follow-Ups

- Retry pacing and fallback behavior can easily become provider-biased if the abstraction is too transport-specific.
- PII masking and secret management need clear extension points to avoid overcommitting the core API too early.
- Native-image support may pressure proxy and reflection usage across multiple modules and may require follow-up ADR work.
