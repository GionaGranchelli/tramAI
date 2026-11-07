# D7-B2 — Durable Agent CLI Process Evidence (Codex E5 & Hermes E6) Design Contract

**Status:** Completed & Ratified  
**Base:** `release/0.7.0` at `2041fdc71ded5e386305e4e052a87e7b71d2353f`  
**Implementation Branch:** `task/0.7.7g-d7-b2-agent-cli-process-evidence`  
**Scope:** Durable process effect evidence, lifecycle ownership, crash-window qualification, and truthful execution guarantees for built-in Agent CLI boundaries (Codex E5 and Hermes E6).

---

## 1. Context & Motivation

EPIC-0.7.7 established durable cancellation authority and atomic admissions across all outbound effect boundaries (E1–E9) and step boundaries (E11), with recovery re-execution fenced by lease ownership (E10). Milestone D7-A ratified per-effect child evidence provenance for outbound HTTP (E1/E2), and Milestone D7-B1 ratified durable process effect evidence for Shell execution (E7).

Built-in agent execution in TramAI comprises two CLI process boundaries:
- **E5:** Codex CLI (`CodexWorkflowStep`)
- **E6:** Hermes CLI (`HermesWorkflowStep`)

Both boundaries share a single execution point: `executeAgentCli()` in `AgentCliSupport.kt`.

Prior to D7-B2, `executeAgentCli()` acquired an unverified process permit without recording per-effect child evidence in `WorkflowEffectEvidenceRecordStore`, lacked synchronous step-attempt correlation, and did not guarantee truthful execution states across process start failures, timeouts, and cancellations.

D7-B2 instruments this shared boundary once, guaranteeing that both Codex and Hermes inherit identical admission, lineage, state-transition, crash-safety, timeout, and cleanup guarantees without duplicating process lifecycle logic.

---

## 2. Process Evidence States & Identity

### Authoritative Process States

Every governed Agent CLI (E5/E6) execution attempt is observable in exactly one of these states:

| State | Semantic Guarantee |
| --- | --- |
| `ADMITTED` | Proves durable effect authorization and child-record creation. No process acquisition has been confirmed in durable evidence. The process may nevertheless have started if execution was interrupted before the next evidence transition. |
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
- `diagnosticSummary`: Bounded, sanitized failure preview (no raw arguments, prompt text, environment variables, stdout, stderr, or secrets).

---

## 3. Key Architectural Decisions

### 3.1 Shared Agent CLI Boundary (`AgentCliSupport.kt`)

Rather than duplicating process evidence management across `CodexStep.kt` and `HermesStep.kt`, evidence orchestration is implemented directly within `executeAgentCli()`.
- `CodexWorkflowStep` and `HermesWorkflowStep` assemble their CLI arguments and delegate execution to `executeAgentCli()`.
- `AgentCliRequest` exposes an internal `processStarter: (ProcessBuilder) -> Process` lambda (defaulting to `{ it.start() }`), allowing deterministic testing of launch failures and crash windows without physical process invocation.
- Both Codex and Hermes step definitions remain clean and focused on prompt assembly and output parsing.

### 3.2 Non-Zero Exit Code & Downstream Validation Semantics

1. **Non-Zero Exit Codes:**
   - When an agent CLI process terminates with an exit code `!= 0`, the OS process effect itself has fully completed. The streams were read to EOF and the process terminated naturally.
   - The process evidence record transitions to **`COMPLETED`**.
   - Downstream, `executeAgentCli()` returns an `AgentCliExecution` with the exit code. The calling step (Codex or Hermes) throws a `WorkflowCodexException` or `WorkflowHermesException` with `WorkflowStepFailureCode.NON_ZERO_EXIT`.
   - **Invariant:** A business-level or step-level failure must never retroactively downgrade the process evidence record to `UNCERTAIN`.

2. **Downstream Output Validation:**
   - If an agent produces output that violates security policies (e.g. prompt extraction or secret leakage detected by `DefaultOutputValidator`), the step throws `OUTPUT_REJECTED`.
   - The process evidence record remains **`COMPLETED`** because the process effect successfully ran to natural completion.

### 3.3 Process Lifecycle Ownership vs Evidence Writes

To prevent orphaned processes:
1. `lifecycle = CancellableProcessLifecycle(process)` and `registration = lifecycle.attachTo(currentCoroutineContext().job)` are established immediately upon process acquisition.
2. The entire post-acquisition execution — including transition to `DISPATCHED`, stream capture, and process exit awaiting — is bounded by `withTimeoutOrNull(timeoutSeconds.seconds)` inside a `try ... finally` block.
3. Within `withTimeoutOrNull`, the timeout scope's cancellation is directly registered with `lifecycle.attachTo(currentCoroutineContext().job)`. When the deadline fires, `requestTermination()` is invoked immediately on the cancelling timeout scope, closing the pipes and terminating descendants before structured concurrency awaits child coroutines. This guarantees bounded cleanup even when output readers remain blocked on open streams during execution timeouts.
4. If writing `DISPATCHED` stalls indefinitely, the execution deadline fires, initiates process termination via `lifecycle.requestTermination()`, and triggers `finally { lifecycle.terminateAndAwait() }`. A stalled store write can never indefinitely delay process cleanup.
5. If writing `DISPATCHED` or `COMPLETED` throws an exception (e.g. JDBC outage) or is interrupted by cancellation:
   - The `finally` block executes `lifecycle.terminateAndAwait()`, forcefully terminating and reaping the process tree.
   - Bounded process cleanup occurs **before** attempting secondary uncertainty persistence (`recordUncertainFailure`).
   - No orphan process is left running on the host system.
6. In `recordUncertainFailure`, evidence updates execute with a cooperative suspension deadline (`withTimeoutOrNull(5_000L)` under `NonCancellable`). If secondary cancellation occurs from the evidence store, it is suppressed on the primary failure rather than replacing it. When the primary failure is itself a `CancellationException`, only a sanitized, cause-free marker (`SanitizedEvidenceDiagnosticException`) is attached to prevent internal persistence diagnostic leakage onto cancellation control flow.

### 3.4 Fail-Closed Lineage Attribution

When D7 evidence tracking is enabled (`evidenceStore != null`):
- `requireEvidenceLineageIfEnabled(workflowName, context.workflowId, stepName, processAdmission != null, stepAttempt)` executes **before** calling `admitProcess`.
- If `processAdmission == null` or `stepAttempt == null`: `WorkflowEvidenceResolutionException` is thrown immediately.
- No admission counter is incremented, no permit is granted, and no process is launched.

### 3.5 Ungoverned Execution Compatibility

For ordinary ungoverned agent workflows (`workflow.run(...)` or `workflow.resume(...)` without governed attribution):
- `currentEffectEvidenceStore()` is `null`.
- No lineage check is performed.
- No evidence records are written.
- Ungoverned execution remains zero-overhead and backward-compatible.

### 3.6 Sensitive Data Isolation

The process evidence store intentionally omits prompt text, CLI arguments, environment variables, raw stdout, and raw stderr. Only bounded, sanitized diagnostics appear in `diagnosticSummary` upon failure, preventing secret leakage or prompt poisoning data from entering the evidence store.

---

## 4. Crash-Window & Adversarial Matrix (Codex E5 & Hermes E6)

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

## 5. Qualification Plan (C1–C16 & M1–M8)

### Integration Scenarios (`WorkflowAgentCliProcessEffectEvidenceTest.kt`)

- **C1 (Normal Codex Execution):** Governed worker with JDBC stores executes Codex step -> `COMPLETED`.
- **C2 (Normal Hermes Execution):** Governed worker with JDBC stores executes Hermes step -> `COMPLETED`.
- **C3 (Missing Lineage):** Governed run without step attempt throws `WorkflowEvidenceResolutionException` before admission.
- **C4 (Pre-admission Cancellation):** Cancelled run throws before effect admission without dispatch or false evidence.
- **C5 (W1 Crash Window):** Post-admission failure commits checkpoint admission while child record write fails.
- **C6 (W2 Crash Window):** Crash before process launch leaves durable JDBC evidence `ADMITTED`; process never launched.
- **C7 (Launch Failure):** Non-existent binary transitions `ADMITTED` -> `UNCERTAIN` without false `DISPATCHED`.
- **C8 (W3 Crash Window):** Crash during completion update leaves durable JDBC evidence `DISPATCHED`.
- **C9 (Non-Zero Exit Code):** Non-zero exit code records `COMPLETED` evidence while step fails `NON_ZERO_EXIT`.
- **C10 (Post-Launch Evidence Stall):** Stalled `DISPATCHED` write triggers execution deadline and bounded cleanup without external cancellation.
- **C11 (Timeout Scope Unblocks Reader):** Timeout scope cancellation unblocks output capture when root process has exited but stream reader remains blocked.
- **C12 (Parent Cancellation & Diagnostics):** Cancellation cleans up process tree before uncertainty persistence; secondary store errors are sanitized.
- **C13 (Step Retry Distinctness):** Retried step generates distinct admission sequences and attempt IDs.
- **C14 (Ungoverned Compatibility):** Ungoverned workflow executes Codex and Hermes normally with zero evidence writes.
- **C15 (Sensitive Data Isolation):** Prompt text, arguments, secrets, and raw output do not leak into evidence records.
- **C16 (Downstream Output Validation):** Output rejection leaves process evidence `COMPLETED`.
- **Integration Test:** Multi-step pipeline with both Codex and Hermes invocations sharing parent attempt context produces distinct admission sequences.

### Targeted Mutation Proofs (M1–M8)

- **M1:** Bypass missing-lineage validation -> killed by C3 (`AssertionError` at line 273).
- **M2:** Skip `ADMITTED` persistence before launch -> killed by C5/C6 (`AssertionError` at line 347 and line 421).
- **M3:** Write `DISPATCHED` before launch completes -> killed by C7 (`AssertionFailedError` at line 485).
- **M4:** Bypass process cleanup on store stall -> killed by C10 (`TimeoutCancellationException` at line 42).
- **M5:** Remove timeout-scope lifecycle registration -> killed by C11 (`TimeoutCancellationException` at line 42).
- **M6:** Mark timeout or cancellation `COMPLETED` -> killed by C12 (`AssertionError` at line 829).
- **M7:** Reuse effect admission sequence across attempts -> killed by C13 (`WorkflowCodexException: Evidence record already exists` at line 945).
- **M8:** Mark a successful non-zero exit process `UNCERTAIN` -> killed by C9 (`AssertionFailedError` at line 583).
