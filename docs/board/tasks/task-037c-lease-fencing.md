# TASK-037C: Lease Fencing Semantics

- Status: planned
- Priority: high
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Parent task: [TASK-037](../tasks/task-037.md)
- Last updated: 2026-05-03

## Purpose

Define and implement the lease lifecycle for work items — claim, renew, release, and expire — with fencing tokens that prevent stale or partitioned workers from mutating checkpoint state after losing their lease.

## Scope

- Lease operations on `WorkQueueStore`: `claim(workflowRunId, workerId, leaseDuration)` → `ClaimResult.FencingToken`, `renew(workflowRunId, fencingToken, leaseDuration)`, `release(workflowRunId, fencingToken)`, `forceExpire(workflowRunId)`
- Fencing tokens: monotonically increasing integer per workflow run row, returned on successful claim, required for all subsequent mutating operations
- Stale renewal rejection: `renew` fails if the fencing token does not match the current lease holder's token
- Stale checkpoint write rejection: checkpoint store operations accept a fencing token parameter; writes with a stale (non-matching) token are rejected with `StaleLeaseException`
- Lease expiry model: `claim` selects rows where `(status = PENDING) OR (status = RUNNING AND lease_expiry < NOW)`, enabling crash recovery
- `forceExpire` clears the lease holder and fencing token without requiring the original token (for administrative use)

## Exit Criteria

- [ ] `claim` returns a fencing token on success and `null` / `NotAvailable` when the row is actively claimed
- [ ] `renew` succeeds with the correct token and fails with mismatched token
- [ ] `release` succeeds with the correct token and clears the lease
- [ ] Checkpoint writes with a stale token are rejected with `StaleLeaseException`
- [ ] `claim` picks up expired leases (lease_expiry < NOW) as expected for crash recovery
- [ ] `forceExpire` clears lease without requiring the fencing token
