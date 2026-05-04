# Tramai Orchestrator Board

This board tracks the orchestrator platform buildout — Phases 6 through 10.
It sits alongside the [main board](./board.md) which tracks the existing core
phases.

- Board owner: maintainer
- Last updated: 2026-05-03
- Vision: [Orchestrator Vision](../architecture/orchestrator-vision.md)

## How To Use This Board

- Specs define requirements and acceptance criteria.
- Tasks define concrete implementation work.
- Board status reflects delivery progress, not product definition.
- Phases build on each other — complete Phase 6 before starting Phase 7, etc.

## Delivery Snapshot

- Phase 6 (Scheduler): complete ✅
- Phase 7 (Server): complete ✅
- Phase 8 (Agent Steps): complete ✅
- Phase 9 (Distributed): in design
- Phase 10 (Platform): in design

## Blocked

- None

## Phase 6: Scheduler (SPEC-013)

| Task | Priority | Status |
|------|----------|--------|
| [TASK-025: Implement Cron Schedule DSL and In-Process Timer](./tasks/task-025.md) | high | done |
| [TASK-026: Implement Delay Step](./tasks/task-026.md) | high | done |
| [TASK-027: Add Durable Scheduling with JDBC Checkpoint Integration](./tasks/task-027.md) | high | done |
| [TASK-028: Add Timezone and Calendar-Aware Scheduling](./tasks/task-028.md) | medium | done |

## Phase 7: Server (SPEC-014)

| Task | Priority | Status |
|------|----------|--------|
| [TASK-029: Implement REST API for Workflow Management](./tasks/task-029.md) | high | done |
| [TASK-030: Implement MCP Server Adapter](./tasks/task-030.md) | high | done |
| [TASK-031: Implement Webhook Receiver](./tasks/task-031.md) | medium | done |
| [TASK-032: Implement SSE Streaming for Live Traces](./tasks/task-032.md) | medium | done |

## Phase 8: Agent Steps (SPEC-015)

| Task | Priority | Status |
|------|----------|--------|
| [TASK-033: Implement HTTP Step Type](./tasks/task-033.md) | high | done |
| [TASK-034: Implement Shell Step Type](./tasks/task-034.md) | high | done |
| [TASK-035: Implement MCP Step Type](./tasks/task-035.md) | high | done |
| [TASK-036: Implement Hermes and Codex Agent Step Types](./tasks/task-036.md) | medium | done |

## Phase 9: Distributed Execution (SPEC-016)

| Task | Priority | Status |
|------|----------|--------|
| [TASK-037: Implement Worker Pool with Lease-Based Work Stealing](./tasks/task-037.md) | high | planned |

## Phase 10: Platform (SPEC-017)

| Task | Priority | Status |
|------|----------|--------|
| [TASK-038: Implement Admin Dashboard](./tasks/task-038.md) | medium | planned |
| [TASK-039: Implement Plugin System and Multi-Tenancy](./tasks/task-039.md) | medium | planned |

## Traceability

| Task | Primary spec |
|------|-------------|
| TASK-025 | SPEC-013 |
| TASK-026 | SPEC-013 |
| TASK-027 | SPEC-013 |
| TASK-028 | SPEC-013 |
| TASK-029 | SPEC-014 |
| TASK-030 | SPEC-014 |
| TASK-031 | SPEC-014 |
| TASK-032 | SPEC-014 |
| TASK-033 | SPEC-015 |
| TASK-034 | SPEC-015 |
| TASK-035 | SPEC-015 |
| TASK-036 | SPEC-015 |
| TASK-037 | SPEC-016 |
| TASK-038 | SPEC-017 |
| TASK-039 | SPEC-017 |
