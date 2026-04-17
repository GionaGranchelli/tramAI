# Aurora Execution Board

This board is the execution view for specs-driven development in Aurora.

- Board owner: maintainer
- Last updated: 2026-04-18

## How To Use This Board

- Specs define requirements and acceptance criteria.
- Tasks define concrete implementation work.
- Board status reflects delivery progress, not product definition.

## Phase View

- Phase 1: `TASK-001` to `TASK-006`
- Phase 2: `TASK-007` to `TASK-009`
- Phase 3: `TASK-010`
- Phase 4: roadmap only for now, no committed tasks yet

## Backlog

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

### Phase 4: Growth

- No committed tasks yet
- Add Phase 4 tasks only after a corresponding Phase 4 spec exists

## In Progress

- None

## Blocked

- None

## Done

- Initial documentation scaffold in `docs/`
- Initial ADR set for v1 architectural decisions
- Initial milestone-driven spec set in `docs/specs/`

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

## Traceability By Phase

### Phase 1

| Task | Primary spec |
|---|---|
| `TASK-001` | `SPEC-001` |
| `TASK-002` | `SPEC-001` |
| `TASK-003` | `SPEC-001` |
| `TASK-004` | `SPEC-002` |
| `TASK-005` | `SPEC-002` |
| `TASK-006` | `SPEC-003` |

### Phase 2

| Task | Primary spec |
|---|---|
| `TASK-007` | `SPEC-004` |
| `TASK-008` | `SPEC-005` |
| `TASK-009` | `SPEC-006` |

### Phase 3

| Task | Primary spec |
|---|---|
| `TASK-010` | `SPEC-007`, `SPEC-008` |
