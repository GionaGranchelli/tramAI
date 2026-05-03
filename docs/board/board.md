# Tramai Execution Board

This board is the execution view for specs-driven development in Tramai.

- Board owner: maintainer
- Last updated: 2026-04-22

## How To Use This Board

- Specs define requirements and acceptance criteria.
- Tasks define concrete implementation work.
- Board status reflects delivery progress, not product definition.

## Delivery Snapshot

- Phase 1: done
- Phase 2: done
- Phase 3: done
- Phase 4: done
- Phase 5: done

## In Progress

- [TASK-012: Execute 0.1.0 Release Operations and Credibility Closure](./tasks/task-012.md)
- [TASK-013: Final 0.1.0 Release Execution Summary](./tasks/task-013.md)

## Blocked

- None

## Done

### Orchestration Stabilization

- [TASK-018: Promote Orchestration From Experimental To Stable](./tasks/task-018.md)
- [TASK-019: Harden Workflow Resume Compatibility](./tasks/task-019.md)
- [TASK-020: Fix Workflow Observability Correlation](./tasks/task-020.md)
- [TASK-021: Tighten Parallel Execution Bounds](./tasks/task-021.md)
- [TASK-022: Expand Orchestration Stability Test Matrix](./tasks/task-022.md)
- [TASK-023: Freeze Stable Orchestration API Surface](./tasks/task-023.md)
- [TASK-024: Promote Orchestration In Code And Public Docs](./tasks/task-024.md)

### Phase 4: Capabilities

- [TASK-014: Implement Streaming Responses](./tasks/task-014.md)
- [TASK-015: Implement Tool Calling](./tasks/task-015.md)

### Phase 5: Production Hardening

- [TASK-016: Phase 5 - Production Hardening](./tasks/task-016.md)

### Future Design

- [TASK-017: Design Typed Orchestration and Coordination](./tasks/task-017.md)

### Phase 1: Foundation

- [TASK-001: Define core annotations and operation metadata model](./tasks/task-001.md)
- [TASK-002: Implement runtime proxy creation and dispatch routing](./tasks/task-002.md)
- [TASK-003: Define base provider contracts and exception hierarchy](./tasks/task-003.md)
- [TASK-004: Build schema generation and response parsing pipeline](./tasks/task-004.md)
- [TASK-005: Add structured retry loop and failure diagnostics](./tasks/task-005.md)
- [TASK-006: Implement Anthropic and Ollama providers with routing](./tasks/task-006.md)

### Phase 2: Production-Ready

- [TASK-007: Add OpenTelemetry observability integration](./tasks/task-007.md)
- [TASK-008: Build standalone runtime, Kotlin DSL, and Java entry points](./tasks/task-008.md)
- [TASK-009: Build Spring Boot autoconfiguration and configuration binding](./tasks/task-009.md)

### Phase 3: Ecosystem

- [TASK-010: Build testing utilities and documentation baseline](./tasks/task-010.md)
- [TASK-011: Reconcile Delivery Docs and Freeze 0.1.0 MVP Scope](./tasks/task-011.md)

## Planned But Not Scheduled

- Conversation memory remains roadmap-only design work for now.
- The **Orchestrator Platform** (Phases 6-10) is tracked on a separate
  [Orchestrator Board](./orchestrator-board.md) with 5 specs (SPEC-013 through
  SPEC-017) and 15 tasks (TASK-025 through TASK-039).

## Traceability

| Task | Primary spec |
|---|---|
| `TASK-001` | `SPEC-001` |
| `TASK-002` | `SPEC-001` |
| `TASK-003` | `SPEC-001` |
| `TASK-004` | `SPEC-002` |
| `TASK-005` | `SPEC-002` |
| `TASK-006` | `SPEC-003` |
| `TASK-007` | `SPEC-004` |
| `TASK-008` | `SPEC-005` |
| `TASK-009` | `SPEC-006` |
| `TASK-010` | `SPEC-007`, `SPEC-008` |
| `TASK-011` | `SPEC-008` |
| `TASK-012` | `SPEC-008` |
| `TASK-014` | `SPEC-009` |
| `TASK-015` | `SPEC-010` |
| `TASK-016` | `SPEC-011` |
| `TASK-017` | `SPEC-012` |
| `TASK-018` | `SPEC-012` |
| `TASK-019` | `SPEC-012` |
| `TASK-020` | `SPEC-012` |
| `TASK-021` | `SPEC-012` |
| `TASK-022` | `SPEC-012` |
| `TASK-023` | `SPEC-012` |
| `TASK-024` | `SPEC-012` |
