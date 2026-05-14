# SPEC-016a: Distributed Execution — Remaining Gaps (Post-Audit)

- Status: proposed (audit completed 2026-05-14)
- Owner: maintainer
- Related specs: [SPEC-016 Distributed Execution](../specs/spec-016-distributed-execution.md)
- Parent task: [TASK-037](../board/tasks/task-037.md) (core implemented)
- Related docs: [Orchestrator Vision](../architecture/orchestrator-vision.md)

---

## 1. Executive Summary

TASK-037 (Worker Pool with Lease-Based Work Stealing) was implemented in full scope: `TramaiWorker.kt` (849 lines), `StepAttemptRecord.kt`, `WorkerConfig.kt`, `WorkerRegistryStore.kt`, `WorkflowLease.kt`, `JdbcWorkflowLeaseStore.kt`, `FileWorkflowLeaseStore.kt`, and `TramaiWorkerTest.kt` (1039 lines, 15+ tests).

However, the sub-tasks (037A–037H) were never reconciled against the implementation. 3 sub-tasks are fully covered (037B, 037C, 037D, 037E = DONE). 4 have gaps (037A, 037F, 037G, 037H = PARTIAL).

This document defines the remaining work to close all sub-tasks.

---

## 2. Audit Summary

| Sub-task | Status | What's Left |
|----------|--------|-------------|
| 037A Work Queue Store | PARTIAL | Minor — no `ClaimResult` sealed class, no `listPending`/`listExpired`, no pagination. The existing `WorkflowLeaseStore` + `WorkflowCheckpointCatalog` covers the same need. **Close as WONTFIX** — current abstraction is superior for the lease-based model. |
| 037B Worker Registry | DONE | No work needed |
| 037C Lease Fencing | DONE | No work needed |
| 037D Worker Poll Loop | DONE | No work needed |
| 037E Crash Recovery | DONE | No work needed |
| 037F Graceful Shutdown | PARTIAL | Add JVM shutdown hook, add shutdown observer events |
| 037G Partitioning | PARTIAL | Extract `PartitionAssignmentStrategy` interface, extract `ModHashPartitionStrategy` class, add rebalance event |
| 037H Distributed Observability | PARTIAL | Add `OpenTelemetryTramaiWorkerObserver`, add log-based fallback, add missing events (heartbeat, lease renewed, contested, abandoned) |

---

## 3. Remaining Work

### TASK-037F-REMAINING: Shutdown Hook + Shutdown Events

**Files to modify:**
- `TramaiWorker.kt` (tramai-orchestration)
- `TramaiWorkerObserver.kt` (tramai-orchestration, extract from TramaiWorker.kt)

**Changes:**
1. In `TramaiWorker.start()`, store the shutdown hook thread reference: `shutdownHook = Thread { runBlocking(Dispatchers.IO) { shutdown() } }`, register via `Runtime.getRuntime().addShutdownHook(shutdownHook)`. Add the hook **before** the poll loop starts.
2. In `shutdown()`: after drain completes, remove the shutdown hook via `Runtime.getRuntime().removeShutdownHook(shutdownHook)` to prevent double-execution on clean shutdown. Guard `addShutdownHook` with a static registry or shutdown-manager singleton when multiple workers run in the same JVM — register all hooks, or use a single hook that iterates a static `List<TramaiWorker>`.
3. Document caveats in KDoc:
   - `shutdown()` may be called by both the shutdown hook and `close()`. The `shutdownStarted` CAS guard ensures only one proceeds.
   - If the JDBC `DataSource` is already closed by another shutdown handler, `leaseStore.release()` may hang. Mitigation: wrap the release in `withTimeoutOrNull(drainTimeoutMillis)` and log a warning on timeout.
4. Add observer events: `onShutdownStarted()`, `onDrainProgress(done: Int, pending: Int)`, `onShutdownComplete()`

**Effort:** 0.25d

### TASK-037G-REMAINING: Partition Strategy Interface

**Files to create:**
- `PartitionAssignmentStrategy.kt` (tramai-orchestration)

**Files to modify:**
- `TramaiWorker.kt` — extract partitioning from `ownsPartition()` into the strategy

**Design:**

```kotlin
suspend fun interface PartitionAssignmentStrategy {
    suspend fun ownsPartition(workflowId: String, workerId: String, activeWorkers: List<String>): Boolean
}
```

Note: `ownsPartition` is `suspend` because the current implementation calls `workerRegistryStore?.listActiveWorkers()` under the hood. The interface takes `activeWorkers: List<String>` as a parameter (the caller provides the snapshot), so downstream strategies can be pure functions. The caller (`TramaiWorker`) remains responsible for fetching the live worker list.

```kotlin
class ModHashPartitionStrategy : PartitionAssignmentStrategy {
    override fun ownsPartition(workflowId: String, workerId: String, activeWorkers: List<String>): Boolean {
        val hash = sha256High64(workflowId)
        val index = hash.toInt().ushr(1) % activeWorkers.size
        return workerId == activeWorkers[index]
    }
}
```

Default: `ModHashPartitionStrategy`. Users can inject a custom strategy via `WorkerConfig`.

**Effort:** 0.25d

### TASK-037H-REMAINING: OpenTelemetry Worker Observer + Missing Events

**Files to create:**
- `LoggingTramaiWorkerObserver.kt` (tramai-orchestration) — structured log output fallback
- `OpenTelemetryTramaiWorkerObserver.kt` (tramai-observability) — OTel bridge for worker events

**Files to modify:**
- `TramaiWorkerObserver` interface in `TramaiWorker.kt` — add missing events
- `TramaiWorker.kt` — fire new events at appropriate lifecycle points
- `OpenTelemetryAttributes.kt` (tramai-observability) — add worker-specific attributes

**Missing events to add:**
- `onWorkerHeartbeat(workerId, uptimeMillis, claimedCount)`
- `onLeaseRenewed(workflowId, workerId, newExpiry)`
- `onLeaseContested(workflowId, claimantWorkerId, currentWorkerId)`
- `onWorkflowAbandoned(workflowId, workerId, lastStep, timeoutMillis)`

**OTel attributes to add:**
- `tramai.worker.id`
- `tramai.worker.version`
- `tramai.lease.duration_ms`
- `tramai.partition.index`

**Effort:** 0.5d

---

## 4. Implementation Order

| Phase | Task | Effort | Deps |
|-------|------|--------|------|
| 1 | Shutdown hook + shutdown events | 0.25d | TASK-037 |
| 2 | Partition strategy interface | 0.25d | TASK-037 |
| 3 | Distributed observability completion | 0.5d | TASK-037 |

Total remaining effort: **1.0 day**

---

## 5. File Manifest

### Modify
- `tramai-orchestration/.../TramaiWorker.kt` — shutdown hook, new events, extract partitioning
- `tramai-orchestration/.../WorkerConfig.kt` — add `partitionStrategy` optional field
- `tramai-observability/.../OpenTelemetryAttributes.kt` — worker attributes

### Create
- `tramai-orchestration/.../PartitionAssignmentStrategy.kt` — interface + ModHashPartitionStrategy
- `tramai-orchestration/.../LoggingTramaiWorkerObserver.kt` — structured log fallback
- `tramai-observability/.../OpenTelemetryTramaiWorkerObserver.kt` — OTel bridge for worker events

### Tests
- `tramai-orchestration/.../TramaiWorkerTest.kt` — add shutdown hook test, partition strategy test
- `tramai-observability/.../OpenTelemetryTramaiWorkerObserverTest.kt` — new OTel bridge tests

---

## 6. Acceptance Criteria

- [ ] SIGTERM triggers graceful shutdown (drain, release, unregister)
- [ ] Shutdown observer events fire: onShutdownStarted, onDrainProgress, onShutdownComplete
- [ ] `PartitionAssignmentStrategy` interface is extensible
- [ ] `ModHashPartitionStrategy` is the default, produces same distribution as current SHA-256 logic
- [ ] Worker heartbeat event fires on each heartbeat cycle
- [ ] Lease renewed/contested/abandoned events fire at correct lifecycle points
- [ ] `OpenTelemetryTramaiWorkerObserver` bridges all worker events to OTel spans with correct attributes
- [ ] `LoggingTramaiWorkerObserver` outputs structured key=value logs when OTel is absent
- [ ] All existing TramaiWorkerTest (15+ tests) still pass
