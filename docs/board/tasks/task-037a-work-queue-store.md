# TASK-037A: Work Queue Store SPI

- Status: planned
- Priority: high
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Parent task: [TASK-037](../tasks/task-037.md)
- Last updated: 2026-05-03

## Purpose

Define the persistence contract for distributed workflow execution — a store interface and backing model that supports pending work discovery, atomic claim operations, and paginated polling queries used by worker processes.

## Scope

- Define `WorkQueueStore` interface with `claim`, `release`, `renew`, `listPending`, `listExpired` operations
- Model a pending work row: workflow run ID, status, lease holder, lease expiry, attempt count, worker version
- Implement atomic claim using conditional update on `(status = PENDING OR lease_expiry < NOW) AND lease_holder IS NULL`
- Support status transitions: PENDING → CLAIMED → RUNNING → COMPLETED / FAILED / EXPIRED
- Pagination: `listPending(pageSize, offset)` and polling queries: `listDue(since)` for workflows past their scheduled start
- Return `ClaimResult` sealed class: `Claimed(workflowRunId, fencingToken)` or `AlreadyClaimed`, `NotAvailable`

## Exit Criteria

- [ ] `WorkQueueStore` interface fully defined with all SPI methods
- [ ] Pending work model supports all required status transitions
- [ ] Atomic claim operation succeeds only when the row is eligible
- [ ] `listPending` returns paginated results correctly
- [ ] `listDue` returns only workflows past their scheduled start time
- [ ] `ClaimResult` distinguishes claimed vs. unavailable outcomes
