# D7-A — Per-Effect Evidence Provenance & Correlation Design Contract

**Status:** Ratified design & architectural contract.
**Base:** `release/0.7.0` at `ee9860bf93af354f12633664dd74e6f5c7eef7ed` (PR #583).
**Scope:** Per-effect child evidence provenance, step-attempt correlation, and crash-window qualification.

---

## 1. Context & Motivation

EPIC-0.7.7 established durable cancellation authority and atomic admissions across all outbound effect boundaries (E1–E9) and step boundaries (E11), with recovery re-execution fenced by lease ownership (E10).

However, as diagnosed in Phase 1 of D7-A:
1. `admission_seq` on the checkpoint row is a scalar counter providing durable proof that a grant occurred, but carries zero effect metadata or attempt attribution.
2. Step-attempt identity creation in `ExecutionTracker` historically ran asynchronously inside `WorkerExecutionObserver.onStepStarted`, creating a concurrency race between step execution and attempt persistence.
3. Direct `Workflow.run()` executions lacked any attempt tracker.

This contract specifies the authoritative, crash-aware evidence model for instrumented workflow effects, resolves Gate A and Gate B, formalizes dispatch semantics, and defines the phased implementation path.

---

## 2. Gate Resolutions

### Gate A: Classification of Admission Sequences (Option A1 Ratified)

- **Authority Linearization Point:** `admission_seq` on the workflow checkpoint row remains the single linearization point and existence proof of an authorized grant.
- **Separate Per-Effect Evidence Storage:** Child evidence records are stored in a dedicated per-effect evidence store (`WorkflowEffectEvidenceRecordStore`), subordinate to the checkpoint counter.
- **Explicit Non-Atomicity:** Evidence recording is explicitly non-atomic with `admission_seq`.
- **Conservative Classification Rule:**
  - A durable admission sequence $N$ is classified as an effect grant if and only if a corresponding valid child record exists.
  - A separately recorded step admission can classify a step grant.
  - Any admission sequence $N$ that lacks a classified record in the store is definitively **`UNCLASSIFIED` / `UNCERTAIN`**.
  - **Invariant:** The runtime and observers must **never infer effect type or classification from sequence gaps**.

### Gate B: Synchronous Step-Attempt Lineage Attribution

- **Single Identity Authority:** Attempt IDs originate exclusively from the orchestration composition boundary (`StepAttemptIdentitySource`). `WorkflowRunner` must never independently fabricate attempt IDs while `ExecutionTracker` mints another.
- **Synchronous Attempt Establishment:** Top-level governed steps invoke `StepAttemptAuthority.beginAttempt(...)` synchronously after E11 step admission succeeds and before `step.execute(request)`. The returned `PersistedStepAttempt` guarantees that the `STARTED` attempt record is already durably written to `StepAttemptRecordStore`.
- **Coroutine Context Propagation (`StepAttemptScope`):** The confirmed `PersistedStepAttempt` is installed into the coroutine context. All child effects (HTTP, MCP, Process, External) inherit this exact attempt identity.
- **Nested Step Attribution:** Nested steps inherit their enclosing top-level step's attempt identity without creating unrelated attempt records.
- **Direct Run Truthfulness:** If D7 evidence tracking is enabled on a direct run without an explicitly configured attempt store, execution fails fast before obtaining an effect grant. Ungoverned runs remain untouched.

---

## 3. The Evidence Contract

### Evidence States

Every effect attempt is observable in exactly one of these states:

| State | Meaning |
| --- | --- |
| `ADMITTED` | Durable admission sequence was obtained from the authority and child evidence record was persisted. |
| `DISPATCHED` | The permit was locally consumed and the invocation was handed off to the transport/process boundary. |
| `COMPLETED` | The instrumented invocation returned normally to the runtime. |
| `UNCERTAIN` | An admission occurred, but due to cancellation, crash, or transport error, execution state cannot be proven. |

### Record Identity & Key Schema

A per-effect child record is identified by:

$$\text{Key} = (workflowName, workflowId, checkpointGeneration, admissionSeq)$$

- `workflowName`, `workflowId`: Owning workflow identification.
- `checkpointGeneration`: Protects against sequence collisions across checkpoint re-creations or multi-generation lifecycles. (Legacy null generations are canonically encoded as empty string `""`).
- `admissionSeq`: The durable admission sequence returned by `WorkflowCheckpointStore.admit()`.

**Fields:**
- `key`: Compound primary key.
- `stepName`: Attributed step name.
- `stepAttemptId`: Authoritative parent attempt ID.
- `effectKind`: Discriminator (e.g. `HTTP`, `PROCESS`, `MCP_TOOL`).
- `state`: `ADMITTED`, `DISPATCHED`, `COMPLETED`, `UNCERTAIN`.
- `createdAtEpochMillis`: Monotonic clock sample at record creation.
- `completedAtEpochMillis`: Timestamp of normal completion.
- `diagnosticSummary`: Sanitized failure or uncertainty diagnostic (no raw SQL/payloads).

---

## 4. Qualified Dispatch Semantics

To avoid overclaiming remote physical execution while remaining truthful across crashes:

1. **`ADMITTED` is NOT Dispatch:** A granted permit and written `ADMITTED` record proves only that authority permitted the invocation.
2. **`DISPATCHED` Means Local Transport Handoff Observed:**
   - A durable `DISPATCHED` update is made immediately upon local permit consumption at the transport call boundary.
   - It certifies: *"The runtime consumed the single-use permit and invoked the transport callable."*
   - It does **NOT** certify that bytes traversed the physical network or that the remote server processed the payload.
3. **`COMPLETED` Means Local Invocation Returned Normally:**
   - Recorded when the transport call returns a response without throwing.
   - It does **NOT** imply that an external system durably committed the business effect.
4. **Crashes in the Gap:**
   - A crash between `ADMITTED` and transport handoff leaves an `ADMITTED` record. On recovery, because the counter advanced, the effect is treated as `UNCERTAIN` and never assumed not to have executed.
   - A crash between transport return and `COMPLETED` write leaves a `DISPATCHED` record, correctly signaling that dispatch was initiated.

---

## 5. Crash-Window & Adversarial Matrix

| Window | Scenario | Forced Interleaving | Expected Evidence Verdict |
| --- | --- | --- | --- |
| **W1** | Crash after durable admission, before child record write | Latch after `admit()`, inject crash | `admission_seq` advanced; no child record; classified as `UNCLASSIFIED / UNCERTAIN` |
| **W2** | Crash between `ADMITTED` record and local dispatch | Latch after child record write, throw error | Evidence remains `ADMITTED`; recovery treats outcome as `UNCERTAIN` |
| **W3** | Crash after transport call returns, before `COMPLETED` record | Fake transport returns, crash before store update | Evidence remains `DISPATCHED`; never reported as completed or unexecuted |
| **W4** | Cancellation before admission | Cancel committed before step/effect `admit()` | Admission refused; transport never called; no child record written |
| **W5** | Cancellation between admission and dispatch | Admit succeeds, cancel commits before handoff | Permit not consumed; evidence marked `UNCERTAIN`; transport not called |
| **W6** | Step retry after transient failure | First attempt fails, second attempt retries | Second attempt receives fresh `attemptId` and fresh `admission_seq`; distinct child records |
| **W7** | Multi-effect step | Step executes two sequential HTTP calls | Two distinct child records under same `attemptId` with strictly monotonic sequences |

---

## 6. Implementation Phasing

1. **`D7-A1` — Synchronous Step-Attempt Identity Foundation (Standalone PR):**
   - Introduce `StepAttemptAuthority`, `PersistedStepAttempt`, and `StepAttemptScope`.
   - Refactor `ExecutionTracker` to synchronously establish and persist `StepAttemptRecord` at step entry.
   - Decouple `WorkerExecutionObserver.onStepStarted` to be observational only.
   - Ensure complete backward compatibility and zero test regressions across existing worker suites.
2. **`D7-A2` — Per-Effect Child Evidence Storage & Contracts:**
   - Define `WorkflowEffectEvidenceRecordStore` and in-memory / JDBC implementations.
   - Implement compound key retrieval and unclassified sequence reporting.
3. **`D7-A3` — HTTP Dispatch Vertical Slice & Adversarial Suite:**
   - Instrument `HttpWorkflowStep.executeRequest` with child evidence lifecycle.
   - Execute all mandatory adversarial cases and mutation kills.
