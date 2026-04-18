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

Demand-gated work includes streaming, tool calling, memory, KSP generation, and additional providers and framework adapters.

## Current Delivery Snapshot

- Phase 1: implemented
- Phase 2: implemented
- Phase 3: in progress
  The codebase already contains the testing module, documentation base, example projects, and publication wiring, but release hardening and credibility work still remain before a first public MVP release.
- Phase 4: design started, execution not yet scheduled
  Streaming and tool calling now have committed specs and ADRs. Memory remains roadmap-only design work for now.

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
- Phase 4: committed specs exist for streaming and tool calling, but no execution tasks are scheduled yet
