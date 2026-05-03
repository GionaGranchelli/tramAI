# TASK-037G: Partitioning and Rebalancing

- Status: planned
- Priority: medium
- Primary spec: [SPEC-016](../../specs/spec-016-distributed-execution.md)
- Parent task: [TASK-037](../tasks/task-037.md)
- Last updated: 2026-05-03

## Purpose

Provide an optional stable partitioning scheme that assigns workflow runs to specific workers, reducing lease contention and enabling predictable distribution. When worker count changes, the partition map rebalances with minimal churn.

## Scope

- Stable hash algorithm: `hash(workflowId) % partitionCount` producing a partition index, where `partitionCount` is a configurable constant
- Worker group membership: each worker calculates `hash(workflowId) % partitionCount % activeWorkerCount` and only claims workflows assigned to its index
- `partitionCount` configuration parameter on `WorkerConfig`, defaulting to `1` (no partitioning — all workers compete for all workflows)
- Rebalance behavior: when `activeWorkerCount` changes (a worker joins or leaves), each worker recomputes its assigned partitions; a workflow whose hash now maps to a different owner becomes claimable by the new owner
- `PartitionAssignmentStrategy` interface: `assign(workflowId, activeWorkers)` → `workerId?` allowing custom strategies
- Default implementation: `ModHashPartitionStrategy` using `Math.abs(workflowId.hashCode()) % partitionCount % workers.size`
- Tests for even distribution: given N workflows and M workers, each worker claims approximately N/M workflows
- Tests for no duplicate owners: at any point, each workflow should be claimable by exactly one worker

## Exit Criteria

- [ ] `ModHashPartitionStrategy` distributes workflows evenly across workers
- [ ] No duplicate owners: each workflow is claimable by exactly one worker at a time
- [ ] Rebalance on worker join/leave redirects workflows without double-claim windows
- [ ] `partitionCount = 1` disables partitioning (backward compatible)
- [ ] `PartitionAssignmentStrategy` is extensible via the interface
- [ ] Tests prove even distribution and no duplicate ownership
