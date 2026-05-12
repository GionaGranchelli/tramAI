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

## Phase 6: Scheduler (SPEC-013)

- M12: cron schedule DSL with in-process timer
- M13: delay step for mid-workflow pauses
- M14: durable scheduling with JDBC checkpoint store integration
- M15: timezone-aware and calendar-aware scheduling

Timeline: 1-2 weeks. [Board](./board/orchestrator-board.md#phase-6-scheduler-spec-013)

## Phase 7: Server (SPEC-014)

- M16: HTTP REST API for workflow management (start, resume, list, inspect, cancel)
- M17: MCP server adapter (workflows as MCP tools for Hermes/Codex/Copilot/Gemini)
- M18: webhook receiver for external event triggers
- M19: SSE streaming for live execution traces

Timeline: 2-3 weeks. [Board](./board/orchestrator-board.md#phase-7-server-spec-014)

## Phase 8: Agent Steps (SPEC-015)

- M20: HTTP step type for calling external APIs
- M21: Shell step type for CLI/script execution
- M22: MCP step type for calling MCP server tools
- M23: Hermes and Codex step types for delegating to AI agents

Timeline: 2-3 weeks. [Board](./board/orchestrator-board.md#phase-8-agent-steps-spec-015)

## Phase 9: Distributed Execution (SPEC-016)

- M24: worker pool with lease-based work stealing
- M25: step idempotency enforcement
- M26: graceful shutdown and failover

Timeline: 3-4 weeks. [Board](./board/orchestrator-board.md#phase-9-distributed-execution-spec-016)

## Phase 10: Platform (SPEC-017)

- M27: admin dashboard (workflow list, run history, run detail, worker list)
- M28: plugin system (step types, webhook adapters, UI tabs)
- M29: multi-tenancy (teams, API keys, rate limits, audit logs)

Timeline: 4-6 weeks. [Board](./board/orchestrator-board.md#phase-10-platform-spec-017)

## Phase 11: Security Hardening (SPEC-018)

- M30: secure defaults for ShellStep and McpStep (allowlists enforced at build time)
- M31: prompt injection defense framework (sanitizer, instruction defense, output validator)
- M32: security event observability (security-specific WorkflowObserver events)

Timeline: 1-2 weeks. [Board](./board/security-board.md)

## Current Delivery Snapshot

- Phase 1: implemented
- Phase 2: implemented
- Phase 3: implemented
- Phase 4: implemented
- Phase 5: implemented
- Phase 6: in progress (SPEC-013, TASK-025 — TASK-028)
- Phase 7: in design (SPEC-014, TASK-029 — TASK-032)
- Phase 8: in design (SPEC-015, TASK-033 — TASK-036)
- Phase 9: in design (SPEC-016, TASK-037)
- Phase 10: in design (SPEC-017, TASK-038 — TASK-039)
- Phase 11: in design (SPEC-018)

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
- Phase 5: implemented under `SPEC-011` and `TASK-016`
- Phase 6-10: documented under `SPEC-013` through `SPEC-017` and the [Orchestrator Board](../board/orchestrator-board.md)
