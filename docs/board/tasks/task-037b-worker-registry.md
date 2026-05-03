# TASK-037B: Worker Registry and Heartbeat

- Status: planned
- Priority: high
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Parent task: [TASK-037](../tasks/task-037.md)
- Last updated: 2026-05-03

## Purpose

Provide a registry where worker processes self-register and report liveness, enabling the pool to detect stale or crashed workers and redistribute their work.

## Scope

- Define `WorkerRegistry` interface: `register`, `heartbeat`, `unregister`, `listActive`, `listStale`
- Worker registration table row: worker ID, hostname, start timestamp, last heartbeat, version string, definition digest, status (ACTIVE / STALE / DEREGISTERED)
- Heartbeat write: upsert last heartbeat timestamp and optionally the worker version on a configurable interval
- Stale worker detection: `listStale(staleAfter)` returns workers whose last heartbeat is older than `staleAfter`
- Version and definition digest reporting: each heartbeat carries the Tramai library version and a hash of the workflow definitions loaded by that worker, enabling drift detection
- Unregister sets status to DEREGISTERED on graceful shutdown

## Exit Criteria

- [ ] `WorkerRegistry` interface fully defined
- [ ] `register` inserts a new row with ACTIVE status
- [ ] `heartbeat` updates last heartbeat timestamp and version metadata
- [ ] `listStale` correctly identifies workers past the stale threshold
- [ ] `unregister` marks the worker as DEREGISTERED
- [ ] Definition digest enables downstream drift detection across workers
