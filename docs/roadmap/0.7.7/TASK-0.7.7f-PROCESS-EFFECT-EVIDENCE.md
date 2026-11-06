# D7-B1 — Durable Process Effect Evidence (Shell E7) Design Contract

**Status:** Completed & Ratified  
**Base:** `release/0.7.0` at `d1dc1a4dcbf1f6cf3bbb00222bb133e69530cfc8` (PR #584 merged)  
**Implementation Branch:** `task/0.7.7f-d7-b1-shell-process-evidence`  
**Scope:** Durable process effect evidence, lifecycle ownership, crash-window qualification, and non-zero exit semantics for Shell execution (E7).

---

## 1. Context & Motivation

EPIC-0.7.7 established durable cancellation authority and atomic admissions across all outbound effect boundaries (E1–E9) and step boundaries (E11), with recovery re-execution fenced by lease ownership (E10). Milestone D7-A extended this to per-effect child evidence provenance and step-attempt correlation for outbound HTTP (E1/E2).

Prior to D7-B1, Shell step execution (`ShellWorkflowStep`, E7) obtained an atomic process permit (`WorkflowProcessAdmission.admitProcess(...)`) from the checkpoint counter, but recorded no per-effect child evidence in `WorkflowEffectEvidenceRecordStore`, carried no `checkpointGeneration` on its permit, and lacked synchronous step-attempt correlation.

Process effects introduce physical boundaries absent in HTTP:
1. **OS Process Tree Ownership:** Spawning an operating system process creates external state (`Process`, PID, open file descriptors, child subprocesses) that outlives JVM coroutine scopes unless explicitly attached to a bounded lifecycle.
2. **Process Lifecycle vs Evidence Ordering:** Suspendable evidence writes (`DISPATCHED`, `COMPLETED`, `UNCERTAIN`) must never create an orphaned process window if a database write throws or the coroutine is cancelled.
3. **Execution Completion vs Domain Exit Code:** A process that runs to termination with exit code `!= 0` represents a fully realized process effect (`COMPLETED` in the evidence store), even though `ShellStep` may reject the output as a step failure (`NON_ZERO_EXIT`).

This contract ratifies the authoritative evidence model for Shell E7, addresses the lifecycle ownership invariants, defines the crash-window qualification matrix, and establishes the reusable foundation for future CLI agent evidence (E5/E6).

---

## 2. Process Evidence States & Identity

### Authoritative Process States

Every governed Shell E7 execution attempt is observable in exactly one of these states:

| State | Semantic Guarantee |
| --- | --- |
| `ADMITTED` | ADMITTED proves durable effect authorization and child-record creation. No process acquisition has been confirmed in durable evidence. The process may nevertheless have started if execution was interrupted before the next evidence transition. |
| `DISPATCHED` | `ProcessBuilder.start()` succeeded under `NonCancellable`, OS process handle acquired, `CancellableProcessLifecycle` attached to caller `Job`, and streams opened. |
| `COMPLETED` | The OS process exited normally, streams were captured, exit code was obtained, and `lifecycle.terminateAndAwait()` completed. |
| `UNCERTAIN` | Admission occurred, but process start failed (`IOException`), or execution was cancelled, timed out, or threw an unexpected exception. |

### Record Identity & Key Schema

A per-effect child record is identified by:

$$\text{Key} = (workflowName, workflowId, checkpointGeneration, admissionSeq)$$

- `workflowName`, `workflowId`: Owning workflow identification.
- `checkpointGeneration`: Generation string from `WorkflowProcessPermit` (canonically empty string `""` when absent), preventing collision across multi-generation checkpoint lifecycles.
- `admissionSeq`: The durable admission sequence returned by `WorkflowCheckpointStore.admit()`.

**Fields:**
- `key`: Compound primary key.
- `stepName`: Attributed step name.
- `stepAttemptId`: Authoritative parent attempt ID from `PersistedStepAttempt`.
- `effectKind`: `PROCESS_EFFECT_KIND` (`"process"`).
- `state`: `ADMITTED`, `DISPATCHED`, `COMPLETED`, `UNCERTAIN`.
- `createdAtEpochMillis`: Monotonic clock sample at record creation.
- `completedAtEpochMillis`: Monotonic clock sample at normal completion (null until `COMPLETED`).
- `diagnosticSummary`: Bounded, sanitized failure preview (no raw arguments, environment variables, stdout, stderr, or secrets).

---

## 3. Key Architectural Decisions

### 3.1 Non-Zero Exit Code Distinction

A non-zero process exit code (e.g. exit 1, 127, 255) represents an observed process outcome following complete execution. 
- In the process evidence store, the effect is recorded as **`COMPLETED`**: the OS process was successfully spawned, executed its instructions to natural termination, closed its streams, and yielded an exit status.
- In `ShellWorkflowStep`, `config.failOnNonZeroExit` evaluates the exit code and may raise a `WorkflowShellException` with `WorkflowStepFailureCode.NON_ZERO_EXIT`.
- **Invariant:** The step-level business failure must **not** retroactively downgrade the process evidence record to `UNCERTAIN` or `START_FAILED`.

### 3.2 Process Lifecycle Ownership vs Evidence Writes

To prevent orphaned processes:
1. `lifecycle = CancellableProcessLifecycle(process)` and `registration = lifecycle.attachTo(currentCoroutineContext().job)` are established immediately upon process acquisition.
2. The entire post-acquisition lifecycle — including the transition to `DISPATCHED`, stream capture, and process exit awaiting — is bounded by `withTimeoutOrNull(config.timeoutSeconds.seconds)` inside a `try ... finally` block.
3. If writing `DISPATCHED` stalls indefinitely, the configured execution deadline (`config.timeoutSeconds`) fires, initiates process termination via `lifecycle.requestTermination()`, and triggers `finally { lifecycle.terminateAndAwait() }`. A stalled store write can never indefinitely delay process cleanup even when no external cancellation occurs.
4. If writing `DISPATCHED` or `COMPLETED` throws an exception (e.g. JDBC outage) or is interrupted by cancellation:
   - The `finally` block executes `lifecycle.terminateAndAwait()`, forcefully terminating and reaping the process tree.
   - Bounded process cleanup occurs **before** attempting secondary uncertainty persistence (`recordUncertainFailure`).
   - No orphan process is left running on the host system.
5. In `recordUncertainFailure`, evidence updates execute with a cooperative suspension deadline (`withTimeoutOrNull(5_000L)` under `NonCancellable`). If secondary cancellation occurs from the evidence store, it is suppressed on the primary failure rather than replacing it, preserving the primary exception identity.

### 3.3 Fail-Closed Lineage Attribution

When D7 evidence tracking is enabled (`evidenceStore != null`):
- `requireEvidenceLineageIfEnabled(workflowName, workflowId, name, processAdmission != null, stepAttempt)` executes **before** calling `admitProcess`.
- If `processAdmission == null` or `stepAttempt == null`: `WorkflowEvidenceResolutionException` is thrown immediately.
- No admission counter is incremented, no permit is granted, and no process is launched.

### 3.4 Ungoverned Execution Compatibility

For ordinary ungoverned shell workflows (`workflow.run(...)` or `workflow.resume(...)` without governed attribution):
- `currentEffectEvidenceStore()` is `null`.
- No lineage check is performed.
- No evidence records are written.
- Ungoverned execution remains zero-overhead and backward-compatible.

---

## 4. Crash-Window & Adversarial Matrix (Shell E7)

| Window | Scenario | Forced Interleaving | Expected Evidence Verdict |
| --- | --- | --- | --- |
| **W1** | Crash after durable admission, before child record write | Injected failure on `recordEvidence()` after `admitProcess()` commits | `admission_seq` advanced on checkpoint; child store empty; `classifyAdmission` returns `Unclassified` (outcome uncertain) |
| **W2** | Crash between `ADMITTED` record and process start | Injected failure after `recordEvidence(ADMITTED)` before `startOwnedProcess()` | Durable JDBC evidence remains `ADMITTED`; process never started |
| **W3** | Crash after process completion, before `COMPLETED` write | Injected failure on `updateEvidence(COMPLETED)` after process has exited | Durable evidence remains `DISPATCHED`; never reported as `COMPLETED` |
| **W4** | Pre-admission cancellation / refusal | Run cancelled before `admitProcess()` | Admission refused; no evidence record created; process never launched |
| **W5** | Launch failure (`IOException`) | Executable not found or permission denied | `ADMITTED` transitions directly to `UNCERTAIN`; never `DISPATCHED` |
| **W6** | Post-launch evidence failure | `updateEvidence(DISPATCHED)` throws | Process tree terminated and awaited; failure surfaced; no orphaned processes |
| **W7** | Timeout or cancellation during execution | Process runs past deadline or coroutine cancelled | Evidence updated to `UNCERTAIN` under `NonCancellable`; process tree terminated |
| **W8** | Step retry after failure | First attempt fails, second attempt retries | Second attempt receives fresh `attemptId` and fresh `admission_seq`; distinct child records |

---

## 5. Qualification Plan (P1–P11 & M1–M6)

### Integration Scenarios (P1–P11)
- **P1 (Normal Execution):** Governed worker with JDBC stores executes Shell step -> `COMPLETED`.
- **P2 (Pre-admission Refusal):** Cancelled run refuses process admission -> no evidence, no process.
- **P3 (Missing Lineage):** Governed run without attempt throws `WorkflowEvidenceResolutionException` before admission.
- **P4 (W1 Crash):** Post-admission failure leaves unclassified sequence in checkpoint.
- **P5 (W2 Crash):** Post-`ADMITTED` failure leaves `ADMITTED` in JDBC; process never launched.
- **P6 (Launch Failure):** Non-existent binary transitions `ADMITTED` -> `UNCERTAIN`, never `DISPATCHED`.
- **P7 (Post-Launch Evidence Failure):** Failure during `DISPATCHED` write terminates process tree.
- **P8 (Timeout / Cancellation):** Timed out or cancelled process transitions to `UNCERTAIN`; process terminated.
- **P9 (Non-Zero Exit):** Process exiting with code 1 leaves evidence `COMPLETED` while step fails `NON_ZERO_EXIT`.
- **P10 (Step Retry):** Retried step generates distinct admission sequences and attempt IDs.
- **P11 (Ungoverned Compatibility):** Ungoverned run executes normally without evidence writes.

### Targeted Mutation Proofs (M1–M6)
- **M1:** Bypass missing-lineage validation -> caught by P3.
- **M2:** Write `DISPATCHED` before process acquisition returns -> caught by P6 / W2.
- **M3:** Bypass `ADMITTED` persistence before process launch -> caught by P5.
- **M4:** Bypass process cleanup after post-launch evidence failure -> caught by P7.
- **M5:** Write `COMPLETED` on cancellation or timeout -> caught by P8.
- **M6:** Reuse effect admission sequence -> caught by P10.
