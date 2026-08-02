# PR #216 — Complete subprocess and OS-lock cancellation contracts

Epic 1.1 finalization. Branch: fix/0.6.0-process-lock-cancellation (from master).

## Current gaps (surveyed)

1. **ShellStep.executeCommand** — no cancellation handler; on parent cancellation the
   `coroutineScope` waits for blocked stdout/stderr readers forever (process keeps running).
   Termination only happens on timeout.
2. **AgentCliSupport.executeAgentCli** — termination only in `finally` AFTER coroutineScope
   drains readers; parent cancellation hangs the same way. Duplicate termination policy
   (terminateAgentProcessTree) instead of shared lifecycle.
3. **SubprocessMcpTransportProvider** — fire-and-forget stderr drain; cleanup ordering
   can be delayed behind client.close(); termination not attached to cancellation handler.
4. **ProcessSupport.terminateProcessTree** — final `process.waitForUninterruptibly()`
   is unbounded; no cancellation handler; no stream closing; no survivor diagnostics;
   no idempotent lifecycle state.
5. **FileWorkflowCheckpointStore** — `withFileLockCancellable`/`Suspending` use
   `runInterruptible { channel.lock() }`; in-process cancellation proven, cross-process
   (another JVM holding the lock) NOT proven.

## Design

**Workstream A — ProcessSupport.kt:** `CancellableProcessLifecycle` (internal):
- `attachTo(job)`: attach-then-active-check; onCancelling handler calls `requestTermination()`
- `requestTermination()`: non-suspending, idempotent (atomic state CAS), never throws;
  snapshots tree, closes stdin/stdout/stderr (unblocks readers), destroys tree
- `awaitExit()`: cancellable wait via `process.onExit()` + suspendCancellableCoroutine;
  cancellation invokes requestTermination; races handled (onExit.isDone pre-check,
  exactly-one resume)
- `terminateAndAwait()`: NonCancellable+IO; graceful destroy → bounded wait →
  destroyForcibly → bounded wait → survivor report (PIDs); NO unbounded waitFor
- `ProcessCleanupResult(survivors, failures)` + `ProcessTreeSurvivorException(pids)`
- Keep existing helpers (`processTreeHandles`, `waitForHandlesToExitUninterruptibly`)
  as private/internal members

**Workstream B — ShellStep:** start → lifecycle.attachTo(job) → coroutineScope readers +
`lifecycle.awaitExit()` under withTimeout; timeout → terminateAndAwait + timeout event +
WorkflowShellException; parent cancellation → handler closes pipes (readers exit) →
scope completes → finally/terminateAndAwait; cleanup failures suppressed onto primary;
no completed event on cancellation.

**Workstream C — AgentCliSupport:** same lifecycle; remove terminateAgentProcessTree;
events (.started/.completed + duration) only for successful completion; parent
cancellation stays CancellationException; timeout stays AgentCliTimeoutException.

**Workstream D — MCP:** SubprocessMcpTransportProvider attaches lifecycle; cleanup
exactly-once logically (atomic state); stderr drain cancelled via handler; no reconnect
on cancellation; cleanup failure suppressed/recorded.

**Workstream E — cross-process lock:** test helper JVM main class holding
FileChannel.lock() on checkpoint.properties.lock with readiness marker; contract tests
for both withFileLockCancellable and withFileLockCancellableSuspending paths.

## Files
- allowed: ProcessSupport.kt, ShellStep.kt, AgentCliSupport.kt, McpStep.kt, test/**, docs
- forbidden: WorkflowRecoveryController.kt, WorkflowPersistence.kt, StepAttemptRecord.kt,
  api dump, build-logic/**, config/quality/**, .github/**, modules outside orchestration

## Verification
targeted suite x5 (flakiness), full :tramai-orchestration:test, verifyCancellationSafety,
apiCheck, verifyPr -PchangeClass=runtime-behaviour
