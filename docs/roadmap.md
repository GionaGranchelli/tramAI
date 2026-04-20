# Roadmap Summary

This summary mirrors the current implementation plan and is intended to anchor documentation to the planned delivery order.

## Phase 1: Foundation

- M1: core annotations, proxy generation, dispatch, provider contracts, exception hierarchy
- M2: structured output pipeline, schema generation, parsing, retry feedback loop
- M3: first real providers, provider routing, timeout and retry support

## Phase 2: Production-Ready

- M4: observability with OpenTelemetry
- M5: standalone module and Java-friendly API surface
- M6: Spring Boot adapter and configuration model

## Phase 3: Ecosystem

- M7: testing module and assertion helpers
- M8: full public documentation, live proof, publishing, and project hygiene

## Phase 4: Growth

- M9: raw text streaming support (`Flow<StreamChunk>`)
- M10: engine-owned tool calling orchestration
- M11: Spring Boot automatic tool discovery

## Phase 5: Production Hardening

Operational readiness, advanced resilience, PII masking, GraalVM support, and OTel metrics.

## Future Direction

Typed orchestration and coordination for multi-service AI workflows.

## Current Delivery Snapshot

- Phase 1: implemented
- Phase 2: implemented
- Phase 3: implemented
- Phase 4: implemented
- Phase 5: in progress

## Documentation Implication

The docs in this repository should grow with the roadmap:

- architecture and ADRs now
- phase-grouped specs and execution tasks for committed work
- API and configuration references when modules exist
- user guides and migration notes once features are implemented

## Current Documentation Coverage

- Phase 1: committed specs and tasks exist
- Phase 2: committed specs and tasks exist
- Phase 3: committed specs and tasks exist
- Phase 4: committed specs and tasks exist
- Phase 5: implementation is in progress under `SPEC-011` and `TASK-016`
- Future direction: orchestration prototype work is underway under `SPEC-012`, `ADR-017`, and `TASK-017`
