# TASK-016: Phase 5 - Production Hardening

- Status: in_progress
- Priority: high
- Primary spec: [SPEC-011](../../specs/spec-011-production-hardening.md)
- Related ADRs: [ADR-001](../../adr/adr-001.md), [ADR-008](../../adr/adr-008.md), [ADR-012](../../adr/adr-012.md), [ADR-016](../../adr/adr-016.md)
- Last updated: 2026-04-20

## Purpose

Transition Tramai from a "strong alpha" to a production-ready library by addressing operational resilience, security, and performance gaps.

## Scope

### 1. Advanced Resilience
- [x] **Rate Limit (429) Awareness**: Implement exponential backoff that honors `Retry-After` headers from model providers.
- [x] **Fallback Routing**: Support "Plan B" models (e.g., fallback to a faster/cheaper model if the primary fails).
- [x] **Circuit Breaking**: Prevent cascading failures when a provider is down.

## Current Focus

- add pluggable redaction and masking hooks
- add bundled cloud secret-store resolvers on top of the new secret reference SPI

### 2. Security & Compliance
- [ ] **PII Masking**: Add pluggable interceptors to redact sensitive data before it leaves the JVM.
- [ ] **Secret Management**: First-class integration with cloud secret stores (AWS/Vault) for API keys.

### 3. Performance & Optimization
- [x] **Native Image (GraalVM)**: Provide reflection and proxy configuration (or KSP generator) for native compilation.
- [x] **Response Caching**: Pluggable caching layer (Caffeine/Redis) for deterministic or frequent queries.

### 4. Observability & Cost Control
- [x] **OTel Metrics**: Export token usage and latency as OpenTelemetry metrics (Counters/Histograms) for alerting.
- [x] **Token Gating**: Implement hard/soft limits on token consumption per operation.

## Exit Criteria

- [ ] Rate limiting works correctly in high-concurrency simulations.
- [ ] Tramai-based applications can compile to GraalVM Native Images.
- [ ] Token usage metrics are visible in standard OTel collectors.
- [ ] Security hooks, secret sourcing, caching, and native-image support are covered by automated tests or smoke tests.
